import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.20"
}

group = "ai.kidgloves"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.java.dev.jna:jna:5.17.0")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.kotest:kotest-property:5.9.1")
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// The native library: Cargo builds `target/release/libykt.so` (the cdylib
// that re-exports yniffi) and the bindings are generated from the UDL of the
// yniffi checkout Cargo pinned. Cargo is its own incremental build, so both
// tasks run every time and cost nothing when nothing changed.
val buildNative by tasks.registering(Exec::class) {
    commandLine("cargo", "build", "--locked", "--release")
}

val generateBindings by tasks.registering(Exec::class) {
    commandLine("scripts/generate-bindings.sh", layout.buildDirectory.dir("generated/uniffi").get().asFile.path)
    outputs.dir(layout.buildDirectory.dir("generated/uniffi"))
    outputs.upToDateWhen { false }
}

// The per-ABI libraries for an Android app's jniLibs/: needs cargo-ndk and
// ANDROID_NDK_HOME. Not on the path of `test`, which loads the host build.
val buildAndroid by tasks.registering(Exec::class) {
    commandLine(
        "cargo", "ndk", "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64",
        "-o", "src/main/jniLibs", "build", "--locked", "--release",
    )
}

sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("generated/uniffi"))
tasks.named("compileKotlin") { dependsOn(generateBindings) }

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    dependsOn(buildNative)
    systemProperty("jna.library.path", layout.projectDirectory.dir("target/release").asFile.path)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

// `test` is the whole tier: P1-P8, the entry-point sweep, the leak test.
// A consumer can compile its own tests against the binding into the same
// run without forking the build: -PextraTestSources=<dir>.
providers.gradleProperty("extraTestSources").orNull?.let { sourceSets["test"].kotlin.srcDir(it) }
