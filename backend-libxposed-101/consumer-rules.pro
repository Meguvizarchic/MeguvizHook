# The core bridge is looked up by name so a newer adapter can still load with an older core.
-keep class io.github.jichuo1.meguviz.hook.core.CoreAfterBridge { *; }
-keep interface io.github.jichuo1.meguviz.hook.core.DirectAfterDispatchChannel { *; }
