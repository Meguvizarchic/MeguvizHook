# The optional evidence API is absent in published Ref 1.0.0; these rules must also work there.
-keepclassmembers class io.github.jichuo1.meguviz.ref.api.evidence.ResolutionReceipt {
    public *** interruptionOrigin();
}
-keep enum io.github.jichuo1.meguviz.ref.api.evidence.ResolutionReceipt$InterruptionOrigin { *; }
