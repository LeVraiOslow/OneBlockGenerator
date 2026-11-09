plugins {
    java
}

group = "fr.obgenerator"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    // Copie ici les jars de ton serveur : Vault, SuperiorSkyblock2, PlaceholderAPI.
    // Nexo, EssentialsX et RoseStacker sont appeles par reflexion, sans dependance de compilation.
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
