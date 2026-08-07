rootProject.name = "LK-GardenShop"

include("gardenshop-core")
include("gardenshop-bukkit")

/*
 * ItemBridge is developed alongside this plugin, so when its source sits next to this checkout
 * Gradle builds it directly and substitutes it for the published artifact. Nothing to publish
 * between an edit there and a build here.
 *
 * Conditional so a clone without it still resolves the dependency from JitPack. `dev.lk:itembridge`
 * is the coordinate either way -- the substitution matches on group and name, not on where it came
 * from.
 */
if (file("../LK-ItemBridge/settings.gradle.kts").exists()) {
    includeBuild("../LK-ItemBridge")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
        maven("https://mvn.lumine.io/repository/maven-public/") { name = "lumine" }
        maven("https://jitpack.io") { name = "jitpack" }
        maven("https://repo.extendedclip.com/content/repositories/placeholderapi/") { name = "placeholderapi" }
        maven("https://repo.codemc.io/repository/creatorfromhell/") { name = "vaultunlocked" }
    }
}
