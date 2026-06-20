# daemon

Go / eBPF binder transaction tracer that runs as root on Android arm64
and streams events to the viewer app over `127.0.0.1:47291`.

For project overview, build steps, the wire protocol, the pairing
state machine, the symbolizer, and the diff against upstream, see the
top-level [README](../README.md) ([中文](../README_zh-CN.md)).

## Local iteration

```bash
make all     # bpf2go regenerate + cross-compile arm64
make clean   # remove generated bindings + binary
```

`make all` produces `daemon/btrace`, which the Gradle `buildDaemon` task
picks up and stages into the APK assets. For daemon-only smoke tests:

```bash
adb push btrace /data/local/tmp/btrace
adb shell su -c "/data/local/tmp/btrace -l 127.0.0.1:47291"
```

## Upstream

The BPF capture core (`binder_transaction.byte.c` + ringbuf chunking)
originates from [null-luo/btrace](https://github.com/null-luo/btrace);
the original design writeup is at
<https://bbs.kanxue.com/thread-281895.htm>. The on-disk chunking
rationale is preserved in [doc/BPF Ring Buffer Chunking.md](doc/BPF%20Ring%20Buffer%20Chunking.md).

The libbpf headers under `headers/` are vendored from
[libbpf/libbpf v0.6.1](https://github.com/libbpf/libbpf) (BSD-2-Clause,
see `headers/LICENSE.BSD-2-Clause`). Refresh with `headers/update.sh`.
