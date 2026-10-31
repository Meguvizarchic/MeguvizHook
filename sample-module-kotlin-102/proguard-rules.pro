# The framework instantiates the entry class reflectively from META-INF/xposed/java_init.list.
-keep class * extends io.github.libxposed.api.XposedModule {
    <init>();
}
-keepclassmembers class * extends io.github.libxposed.api.XposedModule {
    public void onModuleLoaded(...);
    public void onPackageLoaded(...);
    public void onPackageReady(...);
    public void onSystemServerStarting(...);
}

# Public SDK contract types stay readable in diagnostics and stack traces.
-keep,allowobfuscation class io.github.jichuo1.meguviz.hook.api.** { *; }

# Framework API is compileOnly: it is provided by the runtime, not packaged into this APK.
-dontwarn io.github.libxposed.**
-dontnote io.github.libxposed.**
