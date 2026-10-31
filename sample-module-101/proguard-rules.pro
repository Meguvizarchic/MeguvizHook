# Keep only the reflectively instantiated entry and its lifecycle methods, not the whole package.
-keep class * extends io.github.libxposed.api.XposedModule {
    <init>();
}
-keepclassmembers class * extends io.github.libxposed.api.XposedModule {
    public void onModuleLoaded(...);
    public void onPackageLoaded(...);
    public void onPackageReady(...);
    public void onSystemServerStarting(...);
}

-keep,allowobfuscation class io.github.jichuo1.meguviz.hook.api.** { *; }

# Framework API is compileOnly and supplied by the runtime.
-dontwarn io.github.libxposed.**
-dontnote io.github.libxposed.**
