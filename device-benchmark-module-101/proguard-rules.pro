-keep class * extends io.github.libxposed.api.XposedModule {
    <init>();
}
-keepclassmembers class * extends io.github.libxposed.api.XposedModule {
    public void onPackageReady(...);
}
-keep,allowobfuscation class io.github.jichuo1.meguviz.hook.api.** { *; }
# Keep measured callback implementations distinct; R8 lambda merging adds an unplanned switch.
-keep class io.github.jichuo1.meguviz.hook.benchmark101.BenchmarkEntry$Measurement* { *; }
-keep class io.github.jichuo1.meguviz.hook.benchmark101.BenchmarkEntry$Observ* { *; }
-dontwarn io.github.libxposed.**
-dontnote io.github.libxposed.**
