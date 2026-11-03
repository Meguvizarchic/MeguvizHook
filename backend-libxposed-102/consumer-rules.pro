# The core bridge is looked up by name so a newer adapter can still load with an older core.
-keep class io.github.jichuo1.meguviz.hook.core.CoreAfterBridge { *; }
-keep interface io.github.jichuo1.meguviz.hook.core.DirectAfterDispatchChannel { *; }
# The flyweight chain bridge is resolved the same way; its channel and accessor types are part of
# the lookup, so keeping only some of the three names would look like an inconsistent core.
-keep class io.github.jichuo1.meguviz.hook.core.CoreChainBridge { *; }
-keep interface io.github.jichuo1.meguviz.hook.core.ChainDispatchChannel { *; }
-keep interface io.github.jichuo1.meguviz.hook.core.spi.ChainAccessor { *; }
