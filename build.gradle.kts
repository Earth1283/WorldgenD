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
    // #49: bytecode patch for OrionV3's reentrancy deadlock (see OrionPatchAgent.kt).
    // Zero net.minecraft/com.mojang references — a generic bytecode-editing library,
    // same "not Mojang's code" reasoning as concurrentutil above.
    implementation("org.javassist:javassist:3.30.2-GA")
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

// #49: standalone -javaagent jar (agent code + javassist bundled) so the reentrancy
// patch can be toggled per-run with a JVM flag, independent of the app's own classpath.
// Hand-rolled fat jar (no shadow plugin) — just this project's classes plus javassist's
// jar contents unpacked, filtered to the one class this agent actually needs.
tasks.register<Jar>("agentJar") {
    dependsOn("classes")
    archiveFileName.set("orion-agent.jar")
    from(sourceSets.main.get().output) {
        include("io/github/eath1283/worldgend/OrionPatchAgent*.class")
        include("io/github/eath1283/worldgend/MemoizingPredicate*.class")
    }
    from({
        configurations.getByName("runtimeClasspath")
            .filter { it.name.startsWith("javassist") }
            .map { zipTree(it) }
    })
    manifest {
        attributes(
            "Premain-Class" to "io.github.eath1283.worldgend.OrionPatchAgent",
            "Can-Retransform-Classes" to "true",
        )
    }
}