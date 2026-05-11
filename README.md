> Read this in [English](README.md) | [中文](README_zh-CN.md)

# BinderTracer

A two-process Android Binder tracing toolkit. A root-only Go / eBPF daemon
captures every `binder_transaction` at kernel level; a Kotlin / Compose
viewer app decodes Parcels, reassembles AIDL / HIDL method names,
symbolicates the call stack, and surfaces live traffic for any selected
uid.

**Status: experimental research tool.** Requires a rooted Android arm64
device with kernel 5.12+ (Android 13 / GKI 5.15+ recommended).

---

## Acknowledgements

The BPF capture loop in `daemon/binder_transaction.byte.c` and the
original ringbuf chunking design originate from
**[null-luo/btrace](https://github.com/null-luo/btrace)** (writeup:
<https://bbs.kanxue.com/thread-281895.htm>). This repository extends
that foundation into a full client-server architecture with
symbolication, reverse Parcel decoding, and a Compose UI; see
[Relationship to upstream](#relationship-to-upstream) for the diff.

The libbpf headers under `daemon/headers/` are vendored from
**[libbpf/libbpf v0.6.1](https://github.com/libbpf/libbpf)** under
BSD-2-Clause (see `daemon/headers/LICENSE.BSD-2-Clause`). Refresh with
`daemon/headers/update.sh`.

---

## What you get

- **Live binder traffic for any uid**, with per-call latency, request ↔
  reply pairing, and filters on interface / method / stack module.
- **Method names, not just transaction codes** — the viewer
  reassembles AIDL / HIDL via Java reflection
  (`ServiceManagerCatalog`), a prebaked `methods.json`, and an
  8-stage Parcel decoder pipeline.
- **Symbolicated kernel + user stacks** captured per transaction via
  TLV records (32 kframes + 32 uframes per call).
- **Resilient SYNC ↔ oneway pairing**, including cookie-sideband
  reactivation when the binder driver hands out `cookie==0` to the
  main event before the kprobe sees it.

---

## Architecture

![BinderTracer architecture: kernel, daemon, app stack](imgs/01-architecture-en.drawio.png)

The daemon never speaks to the screen. It blasts decoded events over a
local TCP socket; everything user-visible — interface name resolution,
method-signature lookup, hex dumps, request/reply joining, filtering,
the overlay — lives in the app.

---

## Build & run

### Prerequisites

| | Version |
| --- | --- |
| Go (host) | 1.22+ |
| LLVM clang (host, for `bpf2go`) | 15+ |
| JDK | 17 |
| Android Studio | Hedgehog 2023.1+ (or just `./gradlew`) |
| Android device | arm64, rooted (Magisk / KernelSU), kernel 5.12+ |
| Min SDK / Target SDK / Compile SDK | 26 / 34 / 34 |

Toolchain libraries: Kotlin 1.9.20, Compose BOM 2023.08.00 (compiler
extension 1.5.4), Hilt 2.50, Navigation Compose 2.7.6, Material3.

### Build

```bash
./gradlew assembleDebug
```

The Gradle wiring runs `daemon/Makefile`'s `all` target — `bpf2go`
regenerates `bpf_arm64_bpfel.{go,o}`, then `GOOS=linux GOARCH=arm64
CGO_ENABLED=0 go build` produces `daemon/btrace` — and stages the
ELF binary into the APK as an asset (see `app/build.gradle.kts:buildDaemon`).

Daemon-only iteration:

```bash
cd daemon && make all       # bpf2go + go build
cd daemon && make clean     # remove generated bindings + binary
```

### Run

1. Install the APK and grant: **notification**, **draw over other
   apps** (Settings → Apps → Special access), and accept the
   foreground-service prompt.
2. Open the app. On first start it copies `btrace` out of APK
   assets, `chmod 0755`s it into `/data/local/tmp/btrace`, then
   `su -c` launches it with a one-shot session token. The daemon
   `connect()`s back to `127.0.0.1:47291` and echoes the token in
   its first ACK to prove the connection belongs to *this* launch
   (defends against orphaned daemons from a previous run).
3. **Apps** screen → pick a package → **Monitor**. Live events
   stream in. Tap any row for the request / response / call-stack
   detail sheet.

Runtime files on the device:

| Path | What |
| --- | --- |
| `/data/local/tmp/btrace` | daemon binary |
| `/data/local/tmp/btrace.pid` | singleton lock |
| `/data/local/tmp/btrace.log` | if `-f` was passed |

---

## Relationship to upstream

`null-luo/btrace` is a single-binary CLI that prints binder events as
text to stdout. This repository keeps the BPF capture core but
otherwise rewrites everything around it.

| Area | Upstream `null-luo/btrace` | This repo |
| --- | --- | --- |
| BPF programs | one (`raw_tp/binder_transaction`) | three sharing one compilation unit: raw_tp main, cookie kprobe sideband, raw_tp sideband fallback |
| Output | text to stdout | length-prefixed binary frames over TCP |
| Stack capture | none | TLV stack-trace records (32 kframes + 32 uframes) with 5-level fail-open guard |
| Symbolication | none | kallsyms + `/proc/<pid>/maps` LRU + per-`.so` ELF `.symtab`/`.dynsym` LRU with degraded-quality flag |
| Pairing | none | SYNC ≤ 5 s and oneway ≤ 1 s pending windows, cookie-sideband reactivation, late-sideband recall |
| Method resolution | none | `ServiceManagerCatalog` reflection table + `methods.json` + 8-stage Parcel decoder + persistent signature cache |
| Frontend | — | Android app: Compose UI, foreground service, overlay, filter / pause / clear |

---

## Internals

### Wire protocol

Length-prefixed binary frames over TCP, big-endian:

```
+--------+--------+--------+--------+--------+--------+...+--------+
| Magic  | Type   |     Length (4 bytes)            |   Payload   |
+--------+--------+--------+--------+--------+--------+...+--------+
 1 byte   1 byte           4 bytes                    Length bytes
```

`Magic = 0xBD`. `Type` selects the message: `0x01 SetTarget` (app →
daemon), `0x02 Pause`, `0x03 Resume`, `0x04 Shutdown`, `0x10 Ack`,
`0x11 Error`, `0x20 BinderEvent`. Max payload is 1 MiB (`MaxPayloadLen`
in `protocol.go`).

### Binder event payload + TLV stack trace

![TLV stack-trace 5-level fail-open guard pipeline](imgs/02-tlv-guard-en.drawio.png)

Each `0x20 BinderEvent` payload starts with a 60-byte fixed header
(version `0x02`, ext_flags, timestamp ns, pid, uid, code, flags,
data_size, is_reply, target_kind, to_pid, to_uid, target_ref, pair_id),
followed by `data_size` bytes of raw Parcel, optionally followed by a
TLV (`type=0x01 STACK_TRACE`, 4-byte BE length, then the TLV body).

If `ext_flags & HAS_STACK_TLV` is set, decoding goes through five
guards (spec § 4.4.4). All five are *fail-open*: a malformed TLV
silently drops the stack but never the binder event itself.

| Guard | Check | On failure |
| --- | --- | --- |
| G1 | `payloadLen ≥ 60` (base header complete) | counted, payload rejected |
| G2 | `payloadLen ≥ 60 + data_size` (Parcel complete) | counted, payload rejected |
| G3 | `HAS_STACK_TLV` bit set | normal fast path (no TLV) |
| G4 | TLV header (1 B type + 4 B BE length) fits | counted, event returned without stack |
| G5 | `tlv_length ≤ payloadLen − baseEnd − 5` (no overflow) | counted, event returned without stack |

Counters are exposed via `TLVGuardSnapshot()` for the daemon's
1 Hz telemetry tick.

### BPF programs

Three programs in one compilation unit, sharing
`trace_event_map` / `pending_map` / `ringbuf_overflow_map`:

| File | Hook | Role |
| --- | --- | --- |
| `binder_transaction.byte.c` | raw_tp `binder_transaction` | main capture, base record + chunked Parcel |
| `cookie_kprobe_binder_transaction.byte.c` (`#include`d) | kprobe `binder_transaction` | cookie sideband — fixes the case where the binder driver assigns `cookie==0` to the main record before the kprobe runs |
| `raw_tp_binder_transaction.byte.c` (`#include`d) | raw_tp sideband | sideband fallback when the cookie path can't fire |

Compiled with `-mcpu=v3` (BPF ISA v3, kernel 5.12+). The on-wire
record format mixes a 1-byte `kind` tag (`EVT_KIND_MAIN=1`,
`EVT_KIND_SIDEBAND=2`, `EVT_KIND_COOKIE=3`) with the chunk index, and
a magic-prefixed `STACK_RECORD_MAGIC = 0xDEADBEEFDEADBEEF` for stack
records — picked so it cannot collide with any plausible
`pid|uid` low-byte pattern.

### Pairing state machine (`event_collector.go`)

![Pairing state machine: SYNC, oneway, cookie sideband paths](imgs/03-pairing-fsm-en.drawio.png)

The driver dispatches the main event and the sideband (which carries
to_pid / to_uid / target_ref) on separate hooks; the daemon glues them.
Four LRUs back the FSM:

| LRU | TTL | Capacity | Purpose |
| --- | --- | --- | --- |
| `pendingMain` | 5 s | 16384 | SYNC main waiting for sideband |
| `pendingOneway` | 1 s | 4096 | oneway sideband that arrived first |
| `recentOnewayEmit` | 1 s | 4096 | trace late sideband for already-emitted oneway main |
| `pendingMainByTxid` (cookie path) | 5 s | shares `pendingMain` cap | SYNC with `cookie==0`, awaiting kprobe sideband |

`stateMachineSweepPeriod = 250 ms`. `pendingStackTTL` is pinned to
`staleBufferTTL = 5 s` so large multi-chunk Parcels under ringbuf
pressure don't get their stack record evicted as an orphan
(see comment block in `event_collector.go`).

### Symbolizer (`symbolizer.go`)

![Symbolizer dual-LRU: kernel kallsyms vs user-space pid maps + ELF symbols](imgs/04-symbolizer-en.drawio.png)

- **Kernel stacks**: load `/proc/kallsyms` once at startup, sort by
  address, binary-search for each PC.
- **User stacks**: an LRU of 128 pids holds parsed
  `/proc/<pid>/maps`. Cache hits within 100 ms are served directly;
  older hits trigger a `stat` of `/proc/<pid>/stat` field 22
  (`starttime`) to detect pid reuse — `mtime` is **not** used,
  see comments around `kallsymsLoadedCount`. A second LRU of 128
  ELF symbol tables resolves PC → symbol; `.symtab` is preferred,
  `.dynsym` falls back with a `DEGRADED` quality flag.

Quality flags propagate to the app: `FULL` (.symtab hit), `FP_ONLY`
(frame pointer captured but no symbol), `DEGRADED` (.dynsym hit /
no symbol match), `FAILED` (process exited / maps unreadable).

### App-side parsing pipeline (`com.btrace.viewer.parser`)

![ParcelParser 8-stage decoder pipeline](imgs/05-parcel-pipeline-en.drawio.png)

When a frame arrives:

1. `BinderEvent.fromPayload` deserializes header + Parcel + TLV.
2. `ParcelParser.decodePipeline` runs an **8-stage decoder**
   pipeline (`decoders/*Decoder.kt`), each stage trying a different
   strategy — special codes, reply, target ref, AIDL O/P/Q, HIDL
   descriptor, raw ASCII heuristic — and returning early on first
   confident match. `extractInterfaceName` and the legacy code path
   have been deprecated; `sniffArgumentTypes` survives as a
   last-ditch type guess.
3. `MethodResolver` maps `(interface, code)` → method signature using
   `methods.json` (built-in for AOSP system services) plus runtime
   reflection of any class loaded from the target app's APK
   (`AppClassLoaderRegistry` + `AidlPDecoder` etc.).
4. `ServiceManagerCatalog` reflects every registered system service
   on first use and provides `target@0xN` fallback when only a
   raw binder handle is known.
5. `BinderHandleResolver` reverse-maps a binder handle to the owner
   process name, used to populate "to package" in the detail view.
6. `TransactionPairer` joins request → reply by `pair_id`; the
   monitor list hides matched replies to reduce visual noise (see
   commit `b7fe6f7`).
7. `PersistentSignatureCache` writes resolved AIDL / HIDL signatures
   to disk. Capacity bounds are exposed via `BuildConfig` so a
   future RemoteConfig hookup can tune them without redeploying:
   `SIG_CACHE_MAX_ENTRIES=256`, `SIG_CACHE_MAX_PACKAGES=64`,
   `SIG_CACHE_MAX_BYTES_PER_PACKAGE=4096`,
   `SIG_CACHE_TOTAL_SOFT_LIMIT_BYTES=262144`.

---

## Project layout

```
.
├── app/                              Android viewer
│   └── src/main/java/com/btrace/viewer/
│       ├── data/      EventRepository, AppRepository, SocketClient,
│       │              SettingsRepository, BoundedEventBuffer
│       ├── di/        AppModule (Hilt)
│       ├── model/     BinderEvent, StackTrace, AppInfo + codecs
│       ├── navigation AppNavigation (Compose Nav)
│       ├── overlay/   floating-window controller + permission helper
│       ├── parser/    decoder pipeline, signature cache,
│       │              ServiceManagerCatalog, TransactionPairer
│       ├── root/      RootManager, BTraceManager, mount-namespace
│       ├── service/   MonitoringService + session controller
│       ├── ui/        Compose screens: Apps / Monitor / Settings
│       └── utils/     CLogUtils, MountNamespaceVerifier
│
├── daemon/                           Go / eBPF daemon (arm64)
│   ├── *.byte.c                      BPF C programs (raw_tp + kprobe)
│   ├── headers/                      vendored libbpf headers (BSD-2)
│   ├── daemon.go / main.go           lifecycle + flag parsing
│   ├── event_collector.go            ringbuf reader, FSM, LRUs
│   ├── socket_server.go              TCP client, framed protocol
│   ├── protocol.go                   frame + payload + TLV codec
│   └── symbolizer.go                 kallsyms / maps / ELF symbols
│
└── docs/  (local only, gitignored)   internal specs and review notes
```

---

## License

Licensed under the [Apache License, Version 2.0](LICENSE). Third-party
attributions live in [`NOTICE`](NOTICE).

A few file-level exceptions to be aware of when redistributing:

- `daemon/binder_transaction.byte.c` carries an in-file
  `Dual MIT/GPL` declaration (required for loading into the Linux
  kernel) and derives from the now-unmaintained upstream
  [`null-luo/btrace`](https://github.com/null-luo/btrace), which ships
  no top-level LICENSE file. See `NOTICE` for the full attribution.
- `daemon/headers/` is vendored from libbpf v0.6.1 under BSD-2-Clause;
  keep `daemon/headers/LICENSE.BSD-2-Clause` intact when redistributing.
