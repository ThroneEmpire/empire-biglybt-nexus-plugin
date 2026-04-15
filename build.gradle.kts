plugins {
    id("com.gradleup.shadow") version "9.4.1"
    java
}

group = "com.empire.nexus"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    // BiglyBT plugin API - copy BiglyBT.jar from your BiglyBT installation
    // into libs/ before building.
    compileOnly(fileTree("libs") { include("*.jar") })

    // Bundled into the shaded plugin JAR.
    implementation("com.google.code.gson:gson:2.10.1")
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    withType<JavaCompile> {
        options.release.set(21)
    }

    // BiglyBT should load the shaded JAR as the plugin artifact.
    jar {
        enabled = false
    }

    shadowJar {
        archiveBaseName.set("nexus")
        archiveClassifier.set("")
        archiveVersion.set("")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}

// Convenience task: copy the built JAR into BiglyBT's plugins directory.
// Override with: ./gradlew deployPlugin -PbiglybtPlugins=/your/path
val biglybtPlugins: String by extra {
    findProperty("biglybtPlugins")?.toString()
        ?: "${System.getProperty("user.home")}/.biglybt/plugins/nexus"
}

tasks.register<Copy>("deployPlugin") {
    dependsOn(tasks.shadowJar)
    from(tasks.shadowJar.get().archiveFile)
    into(biglybtPlugins)
    doFirst { mkdir(biglybtPlugins) }
    doLast { println("Deployed -> $biglybtPlugins") }
}
