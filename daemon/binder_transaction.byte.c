//go:build ignore

// vmlinux.h 提供内核 BTF 类型(struct binder_proc / binder_context / binder_transaction_data /
// task_struct / cred / pt_regs / user_pt_regs / 各 enum 等),配合 bpf_core_read.h 让 bpf2go 在
// 编译时插入 BTF 重定位记录,运行时由内核 libbpf 在加载阶段对照设备本地 /sys/kernel/btf/vmlinux
// 重定位字段偏移 —— 这就是 spec § 6.1 + § 8.3 所说的 CO-RE。
//
// 注意:vmlinux.h 已经定义了 __u8/u8/__u32/u32/binder_uintptr_t/binder_size_t/各 BPF_MAP_TYPE_*
// enum,因此原来的 common.h(自带一份精简 typedef + bpf_helpers.h)不再 include,避免重复定义。
// 仍直接 include bpf_helpers.h(SEC / bpf_map_lookup_elem / bpf_probe_read 等 helper)和
// bpf_tracing.h(arm64 PT_REGS_PARMx 宏)。
#include "vmlinux.h"
#include "bpf_helpers.h"
#include "bpf_core_read.h"
#include "bpf_tracing.h"

char __license[] SEC("license") = "Dual MIT/GPL";

#define CHUNK_SIZE 0x400
#define MAX_CHUNKS 16
#define USER_ADDR_MASK 0x00FFFFFFFFFFFFFFULL

// binder_dev 枚举值,与 Go 端 / app 端 BinderDev 常量保持同步
#define BINDER_DEV_UNKNOWN  0
#define BINDER_DEV_BINDER   1
#define BINDER_DEV_HWBINDER 2
#define BINDER_DEV_VNDBINDER 3

// target_kind 本期固定写 UNKNOWN(union 无法稳定区分 handle / ptr)
#define TARGET_KIND_UNKNOWN 0

// ============================================================
// P4-B-2/3 融合方案 C(spec § 5.6):cookie 桥接 + raw_tp 一站式
// ============================================================
//
// === entry kprobe early-return 路径与 spec § 6.2 不变量的对账(B3 修复后)===
//
// daemon 端必须满足 `sideband_orphan ≤ ringbuf_overflow_main`(spec § 5.6.3)。
// entry kprobe 的所有 early return 路径处理:
//
//   路径                                   | pending_map | cookie | ringbuf | 是否会派生孤儿
//   ---------------------------------------|-------------|--------|---------|-----------------
//   conf == NULL                           | 未写        | 未分配 | 不发    | 否(clean 早退)
//   tr == NULL                             | 未写        | 未分配 | 不发    | 否(clean 早退)
//   uid filter 不命中                      | 未写        | 未分配 | 不发    | 否(clean 早退)
//   oversize (total_chunks > MAX_CHUNKS)   | 未写        | 已消耗 | 不发    | 否(raw_tp 反查 miss)
//   reply 路径                             | 不写        | 不分配 | 主事件  | 否(走 kernel_debug_id)
//   chunk N reserve 失败                   | 已写        | 已消耗 | 部分    | 是,被 ringbuf_overflow_main +1 吸收
//
// 关键发现(B3 修复要点):pending_map 写入**必须**延后到 oversize 检查之后、
// chunk-loop ringbuf submit 之前。否则 oversize 路径会留下"无主"cookie,raw_tp
// 反查到后上送孤儿 sideband,daemon 落 sideband_orphan;但 oversize 不计 ringbuf
// overflow,验收等式直接破。
//
// === ringbuf 上送两类事件,统一在第 1 字节用 kind 区分 ===
//
//   * EVT_KIND_MAIN     —— 来自 kprobe 的主事件(payload + cookie + tx_flags)
//   * EVT_KIND_SIDEBAND —— 来自 raw_tp 的 sideband(cookie + debug_id + to_pid + to_uid)
// daemon ringbuf reader 看第一个字节决定按 binder_trace_event 还是
// binder_sideband_event 解析。
#define EVT_KIND_MAIN     1
#define EVT_KIND_SIDEBAND 2
#define EVT_KIND_COOKIE   3

// ringbuf overflow 计数器槽位(BPF_MAP_TYPE_PERCPU_ARRAY,daemon 周期 read 汇总)
#define RB_OVF_SLOT_MAIN     0
#define RB_OVF_SLOT_SIDEBAND 1
#define RB_OVF_SLOT_COUNT    2

// ============================================================
// 调用栈展示 P0(spec § 4.1.2):双 record 布局,base 不动,新增 stack record
// ============================================================
//
// 调用栈深度上限。32 是 binder 典型 5-10 帧的 3-6× 余量,单 stack record 大小 =
// 8(magic) + 8(tid) + 4(pid) + 4(tid) + 2 + 2 + 4 + 256 + 256 = 544 B。
#define KSTACK_MAX_DEPTH 32
#define USTACK_MAX_DEPTH 32

// stack record magic:取值域不可达保证(高位 0xDEADBEEF 远超 pid + uid 任何可能取值,
// 详见 spec § 4.1.2)。daemon 端按前 8 字节 magic 唯一识别 stack record vs base record。
//
// 注意:与本文件 `binder_trace_event` 的首字节 kind = EVT_KIND_MAIN(1)/SIDEBAND(2)
// 互不干扰 —— stack record 走 magic 路径(高位 0xDEADBEEF),base record 走 kind
// 字节(低 8 位是 1 / 2)。daemon 端先尝试 magic 匹配,不匹配再按 kind 分流。
#define STACK_RECORD_MAGIC 0xDEADBEEFDEADBEEFULL

// 与 Go 端 bpfBinderTraceEvent struct 内存布局必须严格一致(binary.Read 按 Go struct 解 ringbuf record)。
// 注意:to_uid 后面会补 padding(reserved2)让 u64 字段对齐到 8。
// chunk_data 是变长尾巴,必须放最后。
//
// kind 放第一字节,与 sideband 公用首字节,便于 daemon 单 ringbuf 分流。
struct binder_trace_event {
    u8  kind;       // EVT_KIND_MAIN
    u8  is_reply;
    u8  binder_dev;
    u8  target_kind;
    u32 pid;
    u32 uid;
    u32 code;
    u32 flags;          // tx_flags(binder_transaction_data.flags),供 daemon 区分 oneway / sync
    u64 data_size;
    u64 transaction_id;
    u64 chunk_index;
    u64 cookie;         // P4-B-2:BPF map 自增的桥接 cookie,与 sideband 配对
    u32 to_pid;
    u32 to_uid;
    u32 reserved2;      // 8-byte padding for following u64
    u64 target_ref;
    u64 pair_id;
    u8  chunk_data[CHUNK_SIZE];
};

// raw_tp 一站式上送的 sideband:cookie + debug_id + to_pid + to_uid
struct binder_sideband_event {
    u8  kind;                  // EVT_KIND_SIDEBAND
    u8  to_uid_unsupported;    // 1 = GKI 12-5.10 binder_proc 无 cred 字段,to_uid 走降级
    u8  reserved0;
    u8  reserved1;
    u32 reserved2;             // 8-byte padding for following u64
    u64 cookie;
    u64 kernel_debug_id;
    u32 to_pid;
    u32 to_uid;
};

// cookie kprobe 上送的 sideband:cookie + tid + transaction_id_hint。
// daemon 端按 transaction_id_hint 把 cookie 注入对应主事件的 meta.cookie 字段
// (主事件 BPF 端写 0)。
//
// transaction_id_hint 由主 program 通过 tid_to_txid_map 桥接(精确关联键);
// lookup miss 时 cookie program fallback 用 bpf_ktime_get_ns(),daemon 端可
// fuzzy 匹配,但首选精确。
struct binder_cookie_sideband {
    u8  kind;                  // EVT_KIND_COOKIE
    u8  reserved0;
    u8  reserved1;
    u8  reserved2;
    u32 reserved3;             // 8-byte padding for following u64
    u64 cookie;
    u64 transaction_id_hint;
    u32 tid;
    u32 reserved4;
};

struct {
    __uint(type, BPF_MAP_TYPE_RINGBUF);
    __uint(max_entries, 1 << 24);
} trace_event_map SEC(".maps");

struct trace_config {
    u32 uid;
};

struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __uint(key_size, sizeof(u32));
    __uint(value_size, sizeof(struct trace_config));
    __uint(max_entries, 1);
} trace_config_map SEC(".maps");

// P4-B-2:current_tid -> cookie 桥接 pending map。
// raw_tp 在 trace_binder_transaction() 调用点反查 pending_map[current_tid] 得到
// entry kprobe 留下的 cookie,作为 sideband 的关联键。
// LRU_HASH 容量满时自动淘汰最旧项,新项插入始终成功(spec § 5.6.4):
//   被淘汰旧 cookie 对应 raw_tp 反查会 miss → sideband 跳过 → 主记录 5s TTL evict
//   → daemon 落 entry_early_return(已被现有 bucket 吸收,不引入新 metric)。
struct {
    __uint(type, BPF_MAP_TYPE_LRU_HASH);
    __uint(key_size, sizeof(u32));
    __uint(value_size, sizeof(u64));
    __uint(max_entries, 4096);
} pending_map SEC(".maps");

// P4-B-2:ringbuf overflow 拆分计数(main / sideband 各一槽)。
// BPF reserve 失败时按事件类型分别 +1,daemon 周期 read 汇总(spec § 5.6.3 ringbuf 级联段)。
struct {
    __uint(type, BPF_MAP_TYPE_PERCPU_ARRAY);
    __uint(key_size, sizeof(u32));
    __uint(value_size, sizeof(u64));
    __uint(max_entries, RB_OVF_SLOT_COUNT);
} ringbuf_overflow_map SEC(".maps");

// 主 program 与旁路 cookie program 之间的 transaction_id 关联通道:
//   tid → transaction_id (= bpf_ktime_get_ns())
//
// 设计:主 program 在过滤通过后、chunk loop 之前**单次**写入 tid_to_txid_map[tid] =
// transaction_id;旁路 cookie program 读出这个 transaction_id,与 cookie 一起上送
// binder_cookie_sideband。daemon 端直接按 transaction_id 把 cookie 注入对应主事件
// 的 meta.cookie 字段(无需 fuzzy 时间窗匹配)。
//
// 单次 map_update 在 chunk loop 之外,对 verifier 复杂度影响极小(单一 helper call
// 不放大状态空间),实测同时与基础 chunk loop 一起 ≤ baseline P4-B-1 的复杂度。
//
// LRU_HASH 容量 4096:每条目 12 字节 ≈ 50 KB,不浪费内核内存;tid 复用极少,
// 高 TPS 下顶多 evict 极旧的 stale 条目,daemon 端会把 stale cookie 视为 sideband_orphan
// 沉默吸收(等价于 raw_tp 的 pending_map 路径)。
//
// 注意:本 map **必须**与 pending_map 区别开 —— pending_map 是 raw_tp 的 cookie
// 反查通道(tid → cookie),tid_to_txid_map 是 cookie 旁路的 transaction_id 反查
// 通道(tid → transaction_id),语义、写入时机、读取者都不同,不要合并复用。
struct {
    __uint(type, BPF_MAP_TYPE_LRU_HASH);
    __uint(key_size, sizeof(u32));
    __uint(value_size, sizeof(u64));
    __uint(max_entries, 4096);
} tid_to_txid_map SEC(".maps");

// stack_trace_event:与 base record 共用 trace_event_map ringbuf;daemon 端按前 8 字节
// magic == STACK_RECORD_MAGIC 区分(spec § 4.1.2)。布局必须与 Go 端 bpfStackTraceEvent 一致。
//
// 字段排列保证 8 字节对齐:8 (magic) + 8 (tid) + 4 (pid) + 4 (tid) + 2+2+4 + 256 + 256 = 544 B。
struct stack_trace_event {
    u64 magic;                          // = STACK_RECORD_MAGIC,用于 daemon 区分 record 类型
    u64 transaction_id;                 // 与同一 binder_transaction 的 base record 一致(关联键)
    u32 pid;
    u32 tid;
    s16 kstack_depth;                   // -1 = bpf_get_stack 失败,>= 0 则为实际帧数
    s16 ustack_depth;                   // 同上
    u32 reserved;                       // 8 字节对齐
    u64 kstack[KSTACK_MAX_DEPTH];       // 内核栈 PC 数组
    u64 ustack[USTACK_MAX_DEPTH];       // 用户栈 PC 数组
};

static __always_inline u64 get_transaction_id() {
    u64 id = bpf_ktime_get_ns();
    return id;
}

static __always_inline u64 untag_user_addr(u64 addr) {
    return addr & USER_ADDR_MASK;
}

static __always_inline void inc_overflow(u32 slot) {
    u64 *cnt = bpf_map_lookup_elem(&ringbuf_overflow_map, &slot);
    if (cnt) {
        // PERCPU_ARRAY,无并发 → 直接 ++ 即可;用 __sync_fetch_and_add 也无害,这里用普通自增减一个原子开销
        (*cnt)++;
    }
}

// Force emitting struct event into the ELF.
const struct binder_trace_event *unused_trace_event __attribute__((unused));
const struct trace_config *unused_trace_config __attribute__((unused));
const struct binder_sideband_event *unused_sideband_event __attribute__((unused));
const struct binder_cookie_sideband *unused_cookie_sideband __attribute__((unused));
const struct stack_trace_event *unused_stack_trace_event __attribute__((unused));

SEC("kprobe/binder_transaction")
int kprobe_binder_transaction(struct pt_regs *ctx) {

    u32 config_key = 0;
    struct trace_config* conf = bpf_map_lookup_elem(&trace_config_map, &config_key);
    if (conf == NULL) {
        return 0;
    }

    // P3:不再丢弃 reply 帧,reply 形参作为新字段上送(spec § 3.2.2 / § 6.1)
    int reply = PT_REGS_PARM4(ctx);
    u8 is_reply = reply ? 1 : 0;

    // PARM3 是内核侧 binder_transaction_data 指针(用 vmlinux.h 提供的类型)
    struct binder_transaction_data *tr = (struct binder_transaction_data *)PT_REGS_PARM3(ctx);
    if (!tr) {
        bpf_printk("kprobe_binder_transaction error: tr is null");
        return 0;
    }

    // ============================================================
    // P4-B-1:reply 端从 thread->transaction_stack->debug_id 取稳定 pair_id
    // 2026-05-09:同时取原 request 的 sender_euid,用于把 reply 帧也按"逻辑发起方"
    // 过滤(spec docs/superpowers/specs/2026-05-09-daemon-reply-uid-filter-design.md)
    // ============================================================
    u64 kernel_debug_id = 0;
    u32 orig_sender_uid = 0;
    if (reply) {
        struct binder_thread *thread = (struct binder_thread *)PT_REGS_PARM2(ctx);
        if (thread) {
            struct binder_transaction *in_reply_to = BPF_CORE_READ(thread, transaction_stack);
            if (in_reply_to) {
                kernel_debug_id = (u64)BPF_CORE_READ(in_reply_to, debug_id);
                // sender_euid 是 kuid_t = struct { uid_t val; },GKI 5.10+ 必有
                // (SELinux 校验自 Android 7+ 一直在用)。BPF_CORE_READ 在字段缺失时
                // BTF relocation 会让加载阶段直接失败,真机 GKI 5.15 已验证字段在。
                orig_sender_uid = BPF_CORE_READ(in_reply_to, sender_euid).val;
            }
        }
        // transaction_stack == NULL 极罕见(reply 时 thread 必持有 in_reply_to,见
        // binder_thread_read 的 BR_REPLY 路径),orig_sender_uid 退化为 0,
        // 后续过滤会把这条 reply 静默丢弃 —— 等价于改造前的现状,不引入回归。
    }

    u32 current_uid = bpf_get_current_uid_gid() >> 32;

	u32 current_pid = bpf_get_current_pid_tgid() >> 32;
    u32 current_tid = (u32)(bpf_get_current_pid_tgid() & 0xFFFFFFFF);
    u64 transaction_id = get_transaction_id();

    // ============================================================
    // tx_flags 提取(spec § 5.6.3 强制规范):
    //   入口 kprobe 取 PARM3(struct binder_transaction_data *tr),
    //   统一用 BPF_CORE_READ(tr, flags) 读 binder_transaction_data.flags,
    //   不允许裸偏移(跨 GKI 不安全),不允许走 t->flags(t 在 entry 时还未分配)。
    //   TF_ONE_WAY = 0x01 是后面 daemon "oneway vs 同步" 分流的唯一信号源。
    // ============================================================
    u32 code = BPF_CORE_READ(tr, code);
    u32 flags = BPF_CORE_READ(tr, flags);
    u64 data_size = BPF_CORE_READ(tr, data_size);

    // target_ref:从 tr->target 这个 union 直接读 u64;target_kind 本期固定 UNKNOWN
    u64 target_ref = 0;
    bpf_probe_read(&target_ref, sizeof(u64), &(tr->target));

    // ============================================================
    // P4-A:binder_dev 落值
    // ============================================================
    u8 binder_dev = BINDER_DEV_UNKNOWN;
    struct binder_proc *sender_proc = (struct binder_proc *)PT_REGS_PARM1(ctx);
    if (sender_proc) {
        const char *ctx_name = BPF_CORE_READ(sender_proc, context, name);
        if (ctx_name) {
            char first_ch = 0;
            bpf_probe_read_kernel(&first_ch, 1, ctx_name);
            if (first_ch == 'b') {
                binder_dev = BINDER_DEV_BINDER;
            } else if (first_ch == 'h') {
                binder_dev = BINDER_DEV_HWBINDER;
            } else if (first_ch == 'v') {
                binder_dev = BINDER_DEV_VNDBINDER;
            }
        }
    }

    // ============================================================
    // 主 program 不写 to_pid / to_uid:真值由 raw_tp sideband 在 § 5.6 融合方案下
    // 回填到 daemon userspace 合并事件。
    // ============================================================
    u32 to_pid = 0;
    u32 to_uid = 0;

    // 单向过滤:按"逻辑发起方"匹配,而不是按 kprobe 触发线程的 current_uid。
    //   - request 路径(reply == 0):current_uid 即 sender uid,沿用原语义
    //   - reply 路径(reply == 1):current_uid 是 server 进程 uid(系统服务),与
    //     conf->uid 对不上;改用 orig_sender_uid(原 request 的 sender_euid)。
    //
    // spec docs/superpowers/specs/2026-05-09-daemon-reply-uid-filter-design.md。
    //
    // 旁路 cookie kprobe 在 reply 路径直接 early return(见 cookie_kprobe_..byte.c:44),
    // 不参与 reply 帧;非 reply 路径上 effective_uid == current_uid,与 cookie kprobe
    // 完全一致,sideband_orphan ≤ ringbuf_overflow_main 等式不变。
    u32 effective_uid = reply ? orig_sender_uid : current_uid;
    if ((conf->uid != 0) && (conf->uid != effective_uid)) {
        return 0;
    }

    // 主 program 不分配 cookie / 不写 pending_map(spec daemon-bpf-load-known-issue
    // 通用方案)。cookie 由旁路 kprobe_binder_transaction_cookie SEC 分配并以
    // EVT_KIND_COOKIE sideband 上送,daemon 端按 transaction_id 把 cookie 注入主事件
    // meta。这里 cookie 字段统一写 0,binder_trace_event struct 字段保持不变以维持
    // Go / Kotlin 端的 binary 兼容(回填 task spec 不允许动 struct 字段)。
    u64 cookie = 0;

    // 主 / 旁路 cookie program 之间的关联键写入:tid_to_txid_map[tid] = transaction_id。
    // 旁路 cookie program 在同一 syscall context 内紧随其后触发(perf event chain),
    // 立即读出此 transaction_id 与 cookie 一起上送 binder_cookie_sideband,daemon 端
    // 直接按 transaction_id 配对(无需 fuzzy 时间窗匹配)。
    //
    // 单次 map_update 在 chunk loop 之外,verifier 复杂度影响极小。
    // m3 修复:reply 路径**不**写 tid_to_txid_map —— cookie program 在 reply
    // 路径直接 early return(不分配 cookie),所以这条 entry 永远不会被 cookie
    // program 读取,留下来只会污染 LRU 容量(把之前真有用的 tid→txid 映射 evict)。
    if (!reply) {
        bpf_map_update_elem(&tid_to_txid_map, &current_tid, &transaction_id, BPF_ANY);
    }

    union {
        struct {
            binder_uintptr_t buffer;
            binder_uintptr_t offsets;
        } ptr;
        __u8 buf[8];
    } data;
    bpf_probe_read(&data, sizeof(data), &(tr->data));

    u32 total_chunks = (data_size + CHUNK_SIZE - 1) / CHUNK_SIZE;

    if (total_chunks > MAX_CHUNKS) {
        // oversize 早退:cookie 旁路已经从分配器消耗了一个号 + 写了 pending_map,
        // raw_tp 反查到后会上送 sideband,daemon 端因为没有对应主事件,5s TTL 后
        // 落 sideband_orphan(spec § 5.6.3)。这是已知降级路径,不引入新 bucket。
        bpf_printk("kprobe_binder_transaction error: data size is too long：%d",data_size);
        return 0;
    }


    // ============================================================
    // P0-call-stack:全量 stack record(spec § 4.1.3)
    // ============================================================
    // 在所有 base chunk 之前先 reserve+submit 一条 stack record,daemon 读 ringbuf 时 stack
    // 必先于同事务的 base chunk 到达 → 暂存到 pendingStack[transaction_id],随后第一个 base chunk
    // 到达即可合并。
    //
    // 关键不变量:bpf_get_stack 失败 / stack reserve 失败都不阻塞 base submit。
    // 没有任何分支会因抓栈失败而 return 0(回填 BLOCKER 2)。
    {
        struct stack_trace_event *st = bpf_ringbuf_reserve(&trace_event_map,
                                                           sizeof(struct stack_trace_event), 0);
        if (st) {
            st->magic = STACK_RECORD_MAGIC;
            st->transaction_id = transaction_id;
            u64 pidtgid = bpf_get_current_pid_tgid();
            st->pid = pidtgid >> 32;
            st->tid = (u32)pidtgid;
            st->reserved = 0;

            // 不再抓内核栈:在 Magisk 真机上 /proc/kallsyms 不可读
            // (kallsyms_loaded count=0),内核栈只能是 raw 地址 0xFFFFFF... 对业务定位
            // 无价值。节省 1 次 bpf_get_stack helper 调用(~25 µs/事件)+ 后续 daemon
            // 二分查找 32 帧 ~32 µs 反符号化开销。kstack_depth=0 让 daemon 端
            // symbolize 时跳过 KFrames(已是 if (st.KstackDepth >= 0) 路径,for 循环
            // 0 次)。ringbuf record 大小 544 B 不变(struct 字段未删,保持 Go 端
            // bpfStackTraceEvent ABI 兼容,后续若要省 256 B/事件 ringbuf 流量再做
            // struct 改造)。
            st->kstack_depth = 0;

            long uret = bpf_get_stack(ctx, st->ustack,
                                      USTACK_MAX_DEPTH * sizeof(u64),
                                      BPF_F_USER_STACK);
            st->ustack_depth = (uret < 0) ? -1 : (s16)(uret / sizeof(u64));

            // submit 永不阻塞;失败也不影响后续 base 路径
            bpf_ringbuf_submit(st, 0);
        }
        // stack reserve 失败 → 只丢这条 stack record,继续跑 base 路径
    }

    for (u32 i = 0; i < MAX_CHUNKS; i++) {

		if (i >= total_chunks) {
			return 0;
    	}

        struct binder_trace_event *binder_transaction_event = bpf_ringbuf_reserve(&trace_event_map, sizeof(struct binder_trace_event), 0);

        if (!binder_transaction_event) {
            // ringbuf 满 → 主事件丢失。本期主 program 不再调 inc_overflow:
            // chunk loop 16 次完全展开后,verifier 跟踪 inc_overflow 内部
            // bpf_map_lookup_elem 的双分支(NULL / non-NULL)+ 后续指令路径,
            // 累积状态空间超过 1M 限制(spec daemon-bpf-load-known-issue 二轮调研定论)。
            //
            // ringbuf_overflow_main 的失踪暂时由 daemon 周期性扫 ringbuf
            // BPF_RINGBUF_AVAIL_DATA 间接软告警替代(后续 spec 演进项)。
            // sideband 路径仍会调 inc_overflow(在旁路 SEC 内,无 chunk loop 复杂度问题)。
            bpf_printk("kprobe_binder_transaction error: failed to reserve ring buffer space");
            return 0;
        }

        binder_transaction_event->kind = EVT_KIND_MAIN;
        binder_transaction_event->is_reply = is_reply;
        binder_transaction_event->binder_dev = binder_dev;
        binder_transaction_event->target_kind = TARGET_KIND_UNKNOWN;
        binder_transaction_event->pid = current_pid;
        binder_transaction_event->uid = current_uid;
        binder_transaction_event->code = code;
        binder_transaction_event->flags = flags;
        binder_transaction_event->data_size = data_size;
        binder_transaction_event->transaction_id = transaction_id;
        binder_transaction_event->chunk_index = i;
        binder_transaction_event->cookie = cookie;
        binder_transaction_event->to_pid = to_pid;
        binder_transaction_event->to_uid = to_uid;
        binder_transaction_event->reserved2 = 0;
        binder_transaction_event->target_ref = target_ref;
        // P4-B-1:reply 路径写真值(thread->transaction_stack->debug_id),request 路径仍为 0
        binder_transaction_event->pair_id = kernel_debug_id;

        u64 chunk_size = ((i + 1) * CHUNK_SIZE > data_size) ? (data_size - i * CHUNK_SIZE) : CHUNK_SIZE;
		unsigned probe_read_size = chunk_size < sizeof(binder_transaction_event->chunk_data) ? chunk_size : sizeof(binder_transaction_event->chunk_data);
        u64 user_buffer = untag_user_addr(data.ptr.buffer + i * CHUNK_SIZE);
        bpf_probe_read_user(binder_transaction_event->chunk_data, probe_read_size, (void *)user_buffer);

        bpf_ringbuf_submit(binder_transaction_event, 0);
    }

    return 0;
}

// ============================================================
// 旁路 kprobe/binder_transaction_cookie_path —— cookie 分配 + pending_map 写入 + cookie sideband
// ============================================================
//
// 把这条 SEC 拆成独立 program(同一 hook attach,kernel 4.20+ 多 program perf event
// chain),让主 chunk loop 能等价 P4-B-1 baseline(已知真机能加载),
// cookie 三件套在小型 program 里独立通过 verifier。
//
// 关键:SEC name 必须与主 program 不同(LLVM ELF 同名 SEC 会被合并),所以这里写
// `kprobe/binder_transaction_cookie_path`(伪函数名),daemon 端用 cilium/ebpf
// `link.Kprobe("binder_transaction", ...)` 显式指定 attach 到内核 binder_transaction
// 函数,SEC name 只用于 program type 识别(prefix `kprobe/`)。
//
// 见 cookie_kprobe_binder_transaction.byte.c。
#include "cookie_kprobe_binder_transaction.byte.c"

// ============================================================
// raw_tp/binder_transaction —— P4-B-2+3 一站式:
//   cookie 反查 + debug_id / to_pid / to_uid sideband 上送
// ============================================================
//
// 把 raw_tp 程序源码放在另一个文件里方便 review 切片;借助 #include 把它合到同一个
// 编译单元,所有 map(trace_event_map / pending_map / ringbuf_overflow_map)在
// 同一份 .o 共享,bpf2go 不需要做 map pinning。
#include "raw_tp_binder_transaction.byte.c"
