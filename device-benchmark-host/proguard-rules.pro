# The module hooks these methods by name; prevent renaming, removal and R8 optimization/inlining.
-keep class io.github.jichuo1.meguviz.hook.benchmarkhost.BenchmarkControl { *; }
-keep class io.github.jichuo1.meguviz.hook.benchmarkhost.BenchmarkTarget { *; }
-keep class io.github.jichuo1.meguviz.hook.benchmarkhost.ObservedBenchmarkTarget { *; }
-keep class io.github.jichuo1.meguviz.hook.benchmarkhost.B07Target { *; }
-keep class io.github.jichuo1.meguviz.hook.benchmarkhost.B08Target { *; }
-keep class io.github.jichuo1.meguviz.hook.benchmarkhost.B09Target { *; }
-keep class io.github.jichuo1.meguviz.hook.benchmarkhost.B10Target { *; }
