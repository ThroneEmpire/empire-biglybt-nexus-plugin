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
    // BiglyBT API (BiglyBT.jar)
    compileOnly(fileTree("libs") { include("*.jar") })

    implementation("com.google.code.gson:gson:2.13.2")
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    withType<JavaCompile> {
        options.release.set(21)
    }

    // We do not want an unshaded jar also.
    jar {
        enabled = false
    }

    shadowJar {
        archiveBaseName.set("Nexus_" + version.toString().split(".")[0])
        archiveClassifier.set("")
        archiveVersion.set("")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}