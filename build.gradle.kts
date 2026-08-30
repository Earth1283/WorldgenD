plugins {
    kotlin("jvm") version "2.4.0"
    application
}

group = "io.github.Eath1283"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Spottedleaf's standalone concurrency toolkit — used by Paper's Moonrise chunk
    // system, but zero net.minecraft/com.mojang references of its own (confirmed via
    // javap, see scientific-findings.md #23). Legitimate to depend on directly: it's
    // not Mojang's code, and using it doesn't touch a byte of the vanilla jar.
    implementation("ca.spottedleaf:concurrentutil:0.0.10") {
        // fastutil isn't referenced by anything WorldgenD actually uses from this
        // library (ReentrantAreaLock, confirmed via javap). Left on the classpath it
        // shadows the vanilla jar's own newer bundled fastutil, because ServerRuntime's
        // classloader parents to the system classloader — see scientific-findings.md #23.
        exclude(group = "it.unimi.dsi", module = "fastutil")
    }
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

tasks.register("printRuntimeClasspath") {
    dependsOn("classes")
    doLast { println(sourceSets.main.get().runtimeClasspath.asPath) }
}