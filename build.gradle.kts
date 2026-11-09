plugins {
    java
}

group = "fr.obgenerator"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")                              // VaultAPI
    maven("https://repo.extendedclip.com/releases/")         // PlaceholderAPI
    maven("https://repo.bg-software.com/repository/api/")    // SuperiorSkyblock2
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
    compileOnly("me.clip:placeholderapi:2.11.6") { isTransitive = false }
    compileOnly("com.bgsoftware:SuperiorSkyblockAPI:2024.4") { isTransitive = false }
    // Nexo, EssentialsX et RoseStacker sont appeles par reflexion, sans dependance de compilation.
    // Jars optionnels supplementaires : depose-les dans libs/ (en local uniquement).
    compileOnly(fileTree("libs") { include("*.jar") })
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
