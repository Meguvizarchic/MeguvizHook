# 独立时钟诊断

这个测试应用没有依赖 Hook SDK 或 Xposed，不在后台自动采样。它只对显式指定组件、标准小写
UUID 和有界参数执行一次；每个进程只接受一次有效请求。`deviceTest` 为本地调试证书签名的测试
变体，不是发布制品。

```text
adb -s <指定设备> shell am start -W \
  -n io.github.jichuo1.meguviz.hook.clockdiag/.MainActivity \
  --es run_id <标准小写UUID> --ei warmup 20 --ei samples 100
```

每条 `MeguvizHookClock` 事件含 run ID、进程和线程 ID。顺序为 `start`、逐项 `warmup`、逐项
`sample`、`end`，原样记录每次相邻读取的 `wall_before_ns`、`cpu_before_ns`、`cpu_after_ns`、
`wall_after_ns`；日志输出不在这四次读取之间。`warmup` 范围为 0–100，`samples` 范围为 1–200。
未完成、缺失或截断的日志不是成功结果。

CPU 读取不可用或任一时钟倒退会报告失败并终止；读数相等合法。没有观测到正增量只能说本次窗口
未解析出正增量，不能说分辨率或成本为零。[Android 文档](https://developer.android.com/reference/android/os/Debug#threadCpuTimeNanos())
说明线程 CPU 时间以纳秒表示并不保证纳秒精度，不支持时返回 `-1`。墙钟和线程 CPU 是不同指标，
本应用不把两者差值归因到特定等待。

相邻 CPU 读数的差包含测试方式自身的影响，不能视为完整读钟开销、callback CPU 或逐调用延迟；
不自动减去开销、不填正式性能指标，也不冻结阈值。它不验证框架装载、Hook 或业务性能。
