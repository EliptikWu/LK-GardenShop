import java.security.MessageDigest

plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":gardenshop-core"))

    // Bundled, not server-provided: a library, not a plugin. No transitive dependencies -- it
    // reaches every item plugin by reflection -- so a server with only MythicMobs never loads the
    // ItemsAdder or Nexo code paths.
    implementation(libs.itembridge)

    compileOnly(libs.paper.api)
    compileOnly(libs.mythic.dist)
    compileOnly(libs.placeholderapi)

    // VaultAPI 1.7 declares org.bukkit:bukkit 1.13.1 as a compile dependency, which
    // collides with paper-api over the same capability. Only the net.milkbowl classes
    // are wanted here; Paper supplies the server API.
    compileOnly(libs.vault.api) {
        exclude(group = "org.bukkit", module = "bukkit")
    }

    // The Vault2 API, preferred at runtime when present: BigDecimal money and named
    // currencies, where classic Vault is double-only and single-currency.
    compileOnly(libs.vault.unlocked.api) {
        exclude(group = "org.bukkit", module = "bukkit")
    }

    // MythicCrucible is deliberately absent: the plugin touches no Crucible class, and
    // its published POM references an unresolved ${mythiccrucible.version} property that
    // breaks resolution. Crucible still has to be on the server -- it is what provides
    // the furniture the crops are made of -- it is just not needed to compile.

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    // Deliberately a newer Paper than the plugin compiles against: MockBukkit refuses
    // to start unless the API on its classpath is the exact version it was built for.
    // The plugin still only uses 1.21.4 API, so the runtime floor is unaffected.
    testImplementation(libs.paper.api.test)
    // Provides a real-enough Bukkit server so the sell flow can be tested against an
    // actual inventory: the refund-on-failed-payout path is the one thing in this
    // plugin that absolutely must not be verified by reading the code and hoping.
    testImplementation(libs.mockbukkit)
    // Lets ConsoleBannerTest render the startup banner exactly as the server console will.
    testImplementation(libs.adventure.ansi)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/**
 * Packages `resourcepack/` into the zip a client installs, and prints its SHA-1.
 *
 * `pack.mcmeta` must land at the ZIP ROOT, which is why this copies the folder's *contents*
 * rather than the folder. A pack nested one level down is silently ignored by the client.
 *
 * The archive is reproducible — the root build turns off timestamps and fixes file order for
 * every archive task — so the SHA-1 only changes when the art does. That matters: the client
 * caches by hash, and a zip that rehashed on every build would make every player re-download
 * the pack after every restart.
 */
val packZip = tasks.register<Zip>("packZip") {
    group = "build"
    description = "Packages resourcepack/ into a client-installable zip and prints its SHA-1."

    from(rootProject.layout.projectDirectory.dir("resourcepack")) {
        // Source documentation, not pack content.
        exclude("**/*.md")
    }
    archiveFileName.set("pack.zip")
    destinationDirectory.set(layout.buildDirectory.dir("pack"))

    doLast {
        val zip = archiveFile.get().asFile
        val digest = MessageDigest.getInstance("SHA-1")
        val sha1 = digest.digest(zip.readBytes()).joinToString("") { byte -> "%02x".format(byte) }

        // Written next to the zip so CI or a release script can pick it up without re-hashing.
        val hashFile = destinationDirectory.get().file("pack.sha1").asFile
        hashFile.writeText(sha1 + "\n")

        logger.lifecycle("")
        logger.lifecycle("Resource pack: ${zip.absolutePath}")
        logger.lifecycle("  size   ${zip.length() / 1024} KB")
        logger.lifecycle("  sha1   $sha1")
        logger.lifecycle("")
        logger.lifecycle("  Only needed for resource-pack.mode: external-url — the bundled and")
        logger.lifecycle("  self-hosted modes hash the file themselves at runtime.")
        logger.lifecycle("")
    }
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }

    // Rides along inside the jar so the plugin can serve its own pack with nothing to host.
    from(packZip)
}

/*
 * The plugin jar is built by shadowJar, not jar, because the bundled library has to be
 * RELOCATED and that is bytecode rewriting -- not something a Copy task can do.
 *
 * Why relocation is not optional: Bukkit's legacy plugin class loaders share classes with
 * each other, so if two plugins both shipped dev.lk.itembridge, whichever loaded first would
 * serve its copy to the other -- including its cached view of which item plugins exist.
 *
 * `jar` is disabled rather than left to produce a second archive. build/libs holding two
 * jars whose names differ by a classifier is how a server ends up loading the wrong one,
 * and that has already cost this project a debugging session.
 */
tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveBaseName.set("LKGardenShop")
    // Not "-all": this IS the plugin jar, it just happens to contain a shaded library.
    archiveClassifier.set("")

    relocate("dev.lk.itembridge", "dev.lk.gardenshop.libs.itembridge")

    // No minimizeJar. The library's per-plugin sources are only ever reached through a
    // registry, so a reachability analysis would strip every one of them and leave it able
    // to identify nothing.
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
