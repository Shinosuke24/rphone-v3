plugins {
    kotlin("jvm")
    application
    id("edu.sc.seis.launch4j") version "3.0.6"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.rphone.v3.desktop.MainKt")
}

dependencies {
    // Core module (shared business logic)
    implementation(project(":core"))

    // JavaFX (Windows runtime target)
    implementation("org.openjfx:javafx-base:22.0.1:win")
    implementation("org.openjfx:javafx-graphics:22.0.1:win")
    implementation("org.openjfx:javafx-controls:22.0.1:win")
    implementation("org.openjfx:javafx-fxml:22.0.1:win")
    
    // Serial communication for Windows
    implementation("com.fazecast:jSerialComm:2.10.4")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-javafx:1.7.3")
    
    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("org.slf4j:slf4j-simple:2.0.9")
}

tasks.named<JavaExec>("run") {
    doFirst {
        systemProperty("java.library.path", System.getProperty("java.library.path") ?: "")
    }
}

tasks.named("createExe") {
    dependsOn("shadowJar")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveFileName.set("desktop-all.jar")
}

launch4j {
    outfile = "rphone-v3-desktop.exe"
    mainClassName = "com.rphone.v3.desktop.MainKt"
    
    // Use shadow JAR output with all dependencies bundled
    jar = tasks.named<ShadowJar>("shadowJar").get().archiveFile.get().asFile.absolutePath
    
    // Use bundled Java runtime from jlink output (relative to EXE)
    bundledJrePath = "jre"
    headerType = "gui"
    jvmOptions = listOf("-Xms128m", "-Xmx1024m", "-Dfile.encoding=UTF-8")
}
