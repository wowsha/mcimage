plugins {
    `java-library`
    // `./gradlew runServer` downloads Paper 26.3 into ./run and starts it with the plugin installed.
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "io.github.wowsha"
version = "1.0.0"
description = "Turn base64-encoded images into map art items with shareable image IDs"

java {
    // Paper 26.x requires Java 25.
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
}

tasks {
    compileJava {
        options.encoding = Charsets.UTF_8.name()
        options.release.set(25)
    }

    javadoc {
        options.encoding = Charsets.UTF_8.name()
    }

    processResources {
        val props = mapOf(
            "version" to project.version.toString(),
            "description" to project.description.orEmpty(),
        )
        inputs.properties(props)
        filteringCharset = Charsets.UTF_8.name()
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    jar {
        archiveBaseName.set("MCImage")
    }

    runServer {
        minecraftVersion("26.3")
    }
}
