plugins {
    base
}

tasks.register("verifyCandidate") {
    group = "verification"
    description = "Compile, run and inspect independent consumers of the selected Maven metadata."
    dependsOn(":jvm:run", ":jvm:verifyResolvedDependencies")
    dependsOn(":backend101:assembleDebug", ":backend101:verifyResolvedDependencies")
    dependsOn(":backend102:assembleDebug", ":backend102:verifyResolvedDependencies")
    dependsOn(":refbridge:assembleDebug", ":refbridge:verifyResolvedDependencies")
}
