// Pure Java. Deliberately has NO dependency on Bukkit/Paper/Mythic/Vault so the
// pricing engine can be unit-tested without booting a server (NFR-01).
// PackageBoundaryTest enforces that invariant.

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
