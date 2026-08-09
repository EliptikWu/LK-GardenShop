import java.security.MessageDigest

// The root project deliberately does NOT apply the java plugin.
//
// It has no sources, so applying it produced an empty `build/libs/LK-GardenShop-<version>.jar`
// whose name differs from the real plugin jar
// (`gardenshop-bukkit/build/libs/LKGardenShop-<version>.jar`) by a single hyphen. Dropping
// that decoy into a server gets you:
//
//     does not contain a paper-plugin.yml or plugin.yml!
//
// Removing the plugin removes the jar task, so the trap cannot be laid again.

allprojects {
    group = "dev.lk"
    version = "1.0.1"
}

subprojects {
    apply(plugin = "java")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-processing"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            // PriceSweepTest prints the whole calibration sheet. Off by default so it
            // does not drown out normal runs:  ./gradlew test -PshowTestOutput
            showStandardStreams = providers.gradleProperty("showTestOutput").isPresent
        }
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

/**
 * Prints the one path worth copying to a server, so nobody has to guess which jar is the
 * plugin, and the SHA-256 to publish alongside a release.
 *
 * The hash is worth publishing because **this build is reproducible**: every archive task has
 * timestamps off and file order fixed, so the same source produces a byte-identical jar. That
 * turns the hash into something anyone can check, which is the only real defence against a
 * tampered re-upload — obfuscation does nothing there, since whoever repackages a jar never
 * needed to read it.
 *
 * Written in `sha256sum` format so `sha256sum -c LKGardenShop-<version>.jar.sha256` just works.
 */
tasks.register("pluginJar") {
    group = "build"
    description = "Builds the plugin and prints the jar to install with its SHA-256."

    // shadowJar, not jar: `jar` is disabled in gardenshop-bukkit because the plugin jar has
    // to relocate the bundled Adapter library.
    val jarTask = project(":gardenshop-bukkit").tasks.named("shadowJar")
    dependsOn(jarTask)

    doLast {
        val jar = jarTask.get().outputs.files.singleFile
        val sha256 = MessageDigest.getInstance("SHA-256").digest(jar.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }

        val hashFile = File(jar.parentFile, "${jar.name}.sha256")
        hashFile.writeText("$sha256  ${jar.name}\n")

        logger.lifecycle("")
        logger.lifecycle("Install this file:")
        logger.lifecycle("  ${jar.absolutePath}")
        logger.lifecycle("  ${jar.length() / 1024} KB")
        logger.lifecycle("")
        logger.lifecycle("Publish this with the release, so anyone can verify their download:")
        logger.lifecycle("  sha256  $sha256")
        logger.lifecycle("  file    ${hashFile.name}")
        logger.lifecycle("")
    }
}
