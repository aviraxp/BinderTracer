> 阅读其他语言:[English](README.md) | [中文](README_zh-CN.md)

# BinderTracer

一套双进程的 Android Binder 追踪工具:Root 权限的 Go / eBPF daemon 在内核层
捕获每一次 `binder_transaction`;Kotlin / Compose 编写的 viewer App 反解
Parcel、还原 AIDL / HIDL 方法名、反符号化调用栈,实时呈现任意指定 uid 的
binder 流量。

**项目状态:实验性研究工具。** 需要 root 的 Android arm64 设备,内核
5.12+(推荐 Android 13 / GKI 5.15+)。

---

## 上游归属

`daemon/binder_transaction.byte.c` 中的 BPF 抓取主回路、以及最初的 ringbuf
分片设计来自 **[null-luo/btrace](https://github.com/null-luo/btrace)**(方案
原文:<https://bbs.kanxue.com/thread-281895.htm>)。本仓库在此基础上扩展
为完整的 client-server 架构,加入了反符号化、Parcel 反解、Compose UI;
对比详见下方[相对上游的差异](#相对上游的差异)。

`daemon/headers/` 下的 libbpf 头文件来自
**[libbpf/libbpf v0.6.1](https://github.com/libbpf/libbpf)**,采用
BSD-2-Clause 协议(见 `daemon/headers/LICENSE.BSD-2-Clause`),可通过
`daemon/headers/update.sh` 重新拉取。

---

## 你能拿到什么

- **任意 uid 的实时 binder 流量**,带每次调用的延迟、请求 ↔ 应答配对,
  以及按接口名 / 方法名 / 调用栈所属模块过滤。
- **方法名而不仅是 transaction code**——viewer 通过 Java 反射
  (`ServiceManagerCatalog`)、内置 `methods.json`、以及 8 档 Parcel
  解码管线还原 AIDL / HIDL。
- **反符号化的内核 + 用户调用栈**,通过 TLV 记录附在每次事务上(每帧最多
  32 个 kframe + 32 个 uframe)。
- **强韧的 SYNC ↔ oneway 配对**,包含 cookie sideband 反向激活——当 binder
  驱动给主事件分配 `cookie==0` 而 kprobe 还没执行时,sideband 会反过来
  激活已暂存的主事件。

---

## 架构

![BinderTracer 架构图:内核、daemon、app 三层](imgs/01-architecture-zh.drawio.png)

daemon 不直接面向用户:它把解码后的事件通过本地 TCP 推到 App,所有用户可
见的能力——接口名解析、方法签名查表、hex dump、请求/应答联动、过滤、悬浮
窗——都在 App 端实现。

---

## 编译与运行

### 前置条件

| 组件 | 版本 |
| --- | --- |
| Go(主机) | 1.22+ |
| LLVM clang(主机,`bpf2go` 用) | 15+ |
| JDK | 17 |
| Android Studio | Hedgehog 2023.1+(也可直接用 `./gradlew`) |
| Android 设备 | arm64,已 root(Magisk / KernelSU),内核 5.12+ |
| Min / Target / Compile SDK | 26 / 34 / 34 |

依赖库版本:Kotlin 1.9.20、Compose BOM 2023.08.00(compiler extension
1.5.4)、Hilt 2.50、Navigation Compose 2.7.6、Material3。

### 编译

```bash
./gradlew assembleDebug
```

Gradle 会触发 `daemon/Makefile` 的 `all` 目标——`bpf2go` 重新生成
`bpf_arm64_bpfel.{go,o}`,然后 `GOOS=linux GOARCH=arm64 CGO_ENABLED=0
go build` 产出 `daemon/btrace`——并把 ELF 二进制作为 asset 打进 APK
(细节见 `app/build.gradle.kts:buildDaemon`)。

只迭代 daemon:

```bash
cd daemon && make all       # bpf2go + go build
cd daemon && make clean     # 清理生成的 binding 和二进制
```

### 运行

1. 安装 APK,并授权:**通知**、**显示在其他应用上层**(系统设置 → 应用 →
   特殊访问)、以及前台服务弹窗。
2. 打开 App。首次启动时它会从 APK assets 复制 `btrace` 出来,
   `chmod 0755` 后放到 `/data/local/tmp/btrace`,通过 `su -c` 启动并附带
   一次性 session token。daemon 反向 `connect()` 到 `127.0.0.1:47291`,
   并在第一个 ACK 回传该 token,以证明这次连接确实属于本次启动(防御
   上次 daemon 残留)。
3. 进 **Apps** 页面 → 选目标包 → **Monitor**。事件实时滚动,点击任意一
   行查看请求 / 响应 / 调用栈详情面板。

设备上的运行时文件:

| 路径 | 用途 |
| --- | --- |
| `/data/local/tmp/btrace` | daemon 二进制 |
| `/data/local/tmp/btrace.pid` | 单实例锁 |
| `/data/local/tmp/btrace.log` | 当 `-f` 指定时写入 |

---

## 相对上游的差异

`null-luo/btrace` 是单二进制 CLI,把 binder 事件以文本打到 stdout。本仓库
保留了 BPF 抓取核心,但其余几乎全部重写。

| 维度 | 上游 `null-luo/btrace` | 本仓库 |
| --- | --- | --- |
| BPF 程序 | 1 个(`raw_tp/binder_transaction`) | 3 个共享同一编译单元:raw_tp 主 + cookie kprobe sideband + raw_tp sideband 备用 |
| 输出 | 文本到 stdout | TCP 上的长度前缀二进制帧 |
| 调用栈 | 无 | TLV 栈帧记录(32 kframe + 32 uframe),5 级 fail-open guard |
| 符号化 | 无 | kallsyms + `/proc/<pid>/maps` LRU + 每个 .so 的 ELF `.symtab`/`.dynsym` LRU,带降级质量标记 |
| 配对 | 无 | SYNC ≤ 5 s 与 oneway ≤ 1 s 暂存窗口、cookie sideband 反向激活、晚到 sideband 回收 |
| 方法解析 | 无 | `ServiceManagerCatalog` 反射表 + `methods.json` + 8 档 Parcel 解码器 + 持久化签名缓存 |
| 前端 | — | Android App:Compose UI、前台服务、悬浮窗、过滤 / 暂停 / 清空 |

---

## 内部细节

### 帧协议

TCP 上的长度前缀二进制帧,大端序:

```
+--------+--------+--------+--------+--------+--------+...+--------+
| Magic  | Type   |     Length (4 bytes)            |   Payload   |
+--------+--------+--------+--------+--------+--------+...+--------+
 1 byte   1 byte           4 bytes                    Length 字节
```

`Magic = 0xBD`。`Type` 选择消息:`0x01 SetTarget`(App → daemon)、
`0x02 Pause`、`0x03 Resume`、`0x04 Shutdown`、`0x10 Ack`、`0x11 Error`、
`0x20 BinderEvent`。Payload 上限 1 MiB(`protocol.go:MaxPayloadLen`)。

### Binder 事件 + TLV 调用栈

![TLV 调用栈 5 级 fail-open guard 流程](imgs/02-tlv-guard-zh.drawio.png)

每个 `0x20 BinderEvent` payload 以 60 字节定长头开始(版本 `0x02`、
ext_flags、纳秒时间戳、pid、uid、code、flags、data_size、is_reply、
target_kind、to_pid、to_uid、target_ref、pair_id),后接 `data_size`
字节原始 Parcel,可选地再跟一个 TLV(`type=0x01 STACK_TRACE`、4 字节大端
length,然后是 TLV body)。

当 `ext_flags & HAS_STACK_TLV` 置位时,解码会经过 5 级 guard
(spec § 4.4.4)。**全部 fail-open**:畸形 TLV 只会丢栈,不会丢 binder 事
件本身。

| Guard | 检查 | 失败后 |
| --- | --- | --- |
| G1 | `payloadLen ≥ 60`(base 头完整) | 计数,丢弃 payload |
| G2 | `payloadLen ≥ 60 + data_size`(Parcel 完整) | 计数,丢弃 payload |
| G3 | `HAS_STACK_TLV` 位置位 | 正常快路径(无 TLV) |
| G4 | TLV 头(1 B type + 4 B BE length)完整 | 计数,事件返回但栈为空 |
| G5 | `tlv_length ≤ payloadLen − baseEnd − 5`(无越界) | 计数,事件返回但栈为空 |

计数器通过 `TLVGuardSnapshot()` 暴露,daemon 1 Hz 周期上报。

### BPF 程序

3 个程序共一个编译单元,共享 `trace_event_map` / `pending_map` /
`ringbuf_overflow_map`:

| 文件 | 挂点 | 角色 |
| --- | --- | --- |
| `binder_transaction.byte.c` | raw_tp `binder_transaction` | 主抓取,base record + 分片 Parcel |
| `cookie_kprobe_binder_transaction.byte.c`(`#include`) | kprobe `binder_transaction` | cookie sideband——修复 binder 驱动给主记录分配 `cookie==0` 而 kprobe 尚未执行的时序窗口 |
| `raw_tp_binder_transaction.byte.c`(`#include`) | raw_tp sideband | cookie 路径不可用时的 sideband 备用 |

编译选项 `-mcpu=v3`(BPF ISA v3,内核 5.12+)。on-wire 记录格式以 1 字节
`kind` tag 区分(`EVT_KIND_MAIN=1` / `EVT_KIND_SIDEBAND=2` /
`EVT_KIND_COOKIE=3`)+ chunk index;栈记录则用 magic 前缀
`STACK_RECORD_MAGIC = 0xDEADBEEFDEADBEEF`——故意取一个绝对不会与
任何 `pid|uid` 低位字节相撞的值。

### 配对状态机(`event_collector.go`)

![配对状态机:SYNC、Oneway、Cookie sideband 三路径](imgs/03-pairing-fsm-zh.drawio.png)

驱动把主事件和 sideband(后者携带 to_pid / to_uid / target_ref)分别分发到
两个 hook,daemon 负责拼合。FSM 后面挂 4 个 LRU:

| LRU | TTL | 容量 | 用途 |
| --- | --- | --- | --- |
| `pendingMain` | 5 s | 16384 | SYNC 主等 sideband |
| `pendingOneway` | 1 s | 4096 | oneway sideband 早到等主 |
| `recentOnewayEmit` | 1 s | 4096 | 已 emit 的 oneway 主追踪晚到 sideband |
| `pendingMainByTxid`(cookie 路径) | 5 s | 与 `pendingMain` 共用容量 | `cookie==0` 的 SYNC 等 kprobe sideband |

`stateMachineSweepPeriod = 250 ms`。`pendingStackTTL` 与
`staleBufferTTL = 5 s` 对齐——这样大型多 chunk Parcel 在 ringbuf 高压下
不会让栈记录被误清成孤儿(详见 `event_collector.go` 内的注释块)。

### Symbolizer(`symbolizer.go`)

![Symbolizer 双 LRU:内核 kallsyms 与用户态 pid maps + ELF 符号表](imgs/04-symbolizer-zh.drawio.png)

- **内核栈**:启动一次性载入 `/proc/kallsyms`、按地址排序,每个 PC 二分查
  找。
- **用户栈**:128 个 pid 的 LRU 缓存解析过的 `/proc/<pid>/maps`。100 ms
  内的命中直接用;超过 100 ms 的命中会触发一次 `/proc/<pid>/stat` 第 22
  字段(`starttime`)校验,以检测 pid 复用——**不**用 mtime,理由见
  `kallsymsLoadedCount` 附近的注释。第二个 LRU 缓存 128 个 .so 的 ELF
  符号表;`.symtab` 优先,`.dynsym` 降级回退,质量标 `DEGRADED`。

质量标会传到 App 侧:`FULL`(.symtab 命中)、`FP_ONLY`(只抓到 fp 没符
号)、`DEGRADED`(.dynsym 命中 / 没匹配上)、`FAILED`(进程退出 / maps 不
可读)。

### App 侧解析管线(`com.btrace.viewer.parser`)

![ParcelParser 8 档解码器管线](imgs/05-parcel-pipeline-zh.drawio.png)

每一帧到来时:

1. `BinderEvent.fromPayload` 反序列化头 + Parcel + TLV。
2. `ParcelParser.decodePipeline` 跑一条 **8 档解码器** 管线
   (`decoders/*Decoder.kt`),每档尝试一种策略——special code、reply、
   target ref、AIDL O/P/Q、HIDL descriptor、raw ASCII 启发——首次自信
   匹配立即返回。`extractInterfaceName` 等老路径已被废弃,
   `sniffArgumentTypes` 作为最末档类型猜测保留。
3. `MethodResolver` 把 `(interface, code)` 映射到方法签名,数据来自
   `methods.json`(预内置 AOSP 系统服务)+ 目标 App APK 中加载类的
   运行时反射(`AppClassLoaderRegistry` + `AidlPDecoder` 等)。
4. `ServiceManagerCatalog` 在首次使用时反射所有已注册系统服务,提供仅
   知道 raw binder handle 时的 `target@0xN` 兜底。
5. `BinderHandleResolver` 反查 binder handle 到 owner 进程名,详情页的
   "to package" 字段就来自这里。
6. `TransactionPairer` 按 `pair_id` 把请求 join 到应答;monitor 列表
   会隐藏已配对的 reply 减少视觉冗余(见 commit `b7fe6f7`)。
7. `PersistentSignatureCache` 把已解析的 AIDL / HIDL 签名落盘。容量上限
   通过 `BuildConfig` 暴露,以便后续 RemoteConfig 不发版调节:
   `SIG_CACHE_MAX_ENTRIES=256`、`SIG_CACHE_MAX_PACKAGES=64`、
   `SIG_CACHE_MAX_BYTES_PER_PACKAGE=4096`、
   `SIG_CACHE_TOTAL_SOFT_LIMIT_BYTES=262144`。

---

## 项目结构

```
.
├── app/                              Android viewer
│   └── src/main/java/com/btrace/viewer/
│       ├── data/      EventRepository、AppRepository、SocketClient、
│       │              SettingsRepository、BoundedEventBuffer
│       ├── di/        AppModule(Hilt)
│       ├── model/     BinderEvent、StackTrace、AppInfo + 编解码器
│       ├── navigation AppNavigation(Compose Nav)
│       ├── overlay/   悬浮窗控制器 + 权限助手
│       ├── parser/    解码管线、签名缓存、ServiceManagerCatalog、
│       │              TransactionPairer
│       ├── root/      RootManager、BTraceManager、mount namespace
│       ├── service/   MonitoringService + session controller
│       ├── ui/        Compose 屏幕:Apps / Monitor / Settings
│       └── utils/     CLogUtils、MountNamespaceVerifier
│
├── daemon/                           Go / eBPF daemon (arm64)
│   ├── *.byte.c                      BPF C 程序(raw_tp + kprobe)
│   ├── headers/                      vendored libbpf headers (BSD-2)
│   ├── daemon.go / main.go           生命周期 + flag 解析
│   ├── event_collector.go            ringbuf reader、FSM、LRU
│   ├── socket_server.go              TCP 客户端、帧协议
│   ├── protocol.go                   帧 + payload + TLV 编解码
│   └── symbolizer.go                 kallsyms / maps / ELF 符号表
│
└── docs/  (仅本地、被 gitignore)      内部 spec 与 review 笔记
```

---

## License

本项目采用 [Apache License 2.0](LICENSE);第三方组件的归属说明在
[`NOTICE`](NOTICE) 中。

二次分发时请注意以下文件级别的例外:

- `daemon/binder_transaction.byte.c` 内含 `Dual MIT/GPL` 声明(这是
  BPF 程序加载到 Linux 内核的必要声明),且源自上游
  [`null-luo/btrace`](https://github.com/null-luo/btrace);上游已停更
  且仓库根目录没有 LICENSE 文件,完整归属见 `NOTICE`。
- `daemon/headers/` 来自 libbpf v0.6.1,采用 BSD-2-Clause;二次分发请
  保留 `daemon/headers/LICENSE.BSD-2-Clause`。
