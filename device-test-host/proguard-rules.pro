# The module entry resolves and calls these methods by name. Keep the methods and their bodies
# intact so R8 cannot inline the call under test or remove its counter side effect.
-keep class io.github.jichuo1.meguviz.hook.devicehost.ProbeTarget { *; }
