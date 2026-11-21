# 独立时钟诊断

本测试应用不依赖 Hook SDK 或 Xposed 框架，也不会在后台自动采样；仅在显式启动组件并传入标准小写 UUID 与合法参数时执行一次（每个进程只接收一次有效请求）。`deviceTest` 为本地调试证书签名的测试变体，非发布制品。

```text
adb -s <指定设备> shell am start -W \
  -n io.github.jichuo1.meguviz.hook.clockdiag/.MainActivity \
  --es run_id <标准小写UUID> --ei warmup 20 --ei samples 100
```

## 事件格式与约束

- 每条 `MeguvizHookClock` 日志包含 `run_id`、进程与线程 ID，按 `start`、逐项 `warmup`、逐项 `sample`、`end` 顺序输出，原样记录 `wall_before_ns`、`cpu_before_ns`、`cpu_after_ns` 与 `wall_after_ns`（日志打印不穿插在四次读取之间）。
- 参数范围：`warmup` 为 0–100，`samples` 为 1–200；日志未完成、缺失或截断均视为失败。
- 若线程 CPU 读取不可用或任一时钟发生倒退，应用直接报告失败并终止；相邻两次读数相等属于合法值。未观察到正增量只代表本次窗口未分辨出增量，不能说明时钟分辨率或耗时为零（参考 [Debug.threadCpuTimeNanos](https://developer.android.com/reference/android/os/Debug#threadCpuTimeNanos())）。

> **适用范围与验证说明**：相邻 CPU 读数差值包含测试采集自身影响，不代表完整读钟开销、回调 CPU 或单次调用延迟，也不自动扣除开销、冻结阈值或替代框架/Hook 业务性能验证。
