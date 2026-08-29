plugins {
    kotlin("jvm") version "2.4.0"
    application
}

group = "io.github.Eath1283"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("io.github.eath1283.worldgend.HeadlessWorldgenKt")
}

tasks.named<JavaExec>("run") {
    workingDir = layout.projectDirectory.asFile
    standardOutput = System.out
    findProperty("maxBgThreads")?.let { jvmArgs("-Dmax.bg.threads=$it") }
    findProperty("gcArgs")?.let { jvmArgs((it as String).split(" ")) }
}

tasks.test {
    useJUnitPlatform()
}