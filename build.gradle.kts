import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.ByteBuffer
import java.nio.ByteOrder

plugins {
    kotlin("jvm") version "2.1.20"
    application
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

// ABI → (Rust target, ELF e_machine). The two 64-bit ABIs: Google Play
// requires 64-bit, so a 32-bit-only device is the app's abiFilters to refuse,
// not a library to build.
val abis = mapOf(
    "arm64-v8a" to ("aarch64-linux-android" to 183),
    "x86_64" to ("x86_64-linux-android" to 62),
)

// The same crate, lock and release profile as the host build, once per ABI,
// linked by the NDK's own clang for `androidMinSdk` (gradle.properties). The
// NDK is `ndkVersion` from the Android SDK at ANDROID_HOME; r28 is the first
// whose linker aligns a shared library's segments to 16 KB by default.
abstract class CargoAndroidBuild : DefaultTask() {
    @get:Input abstract val minSdk: Property<Int>
    @get:Input abstract val targets: MapProperty<String, String>
    @get:Internal abstract val ndk: DirectoryProperty
    @get:Internal abstract val crate: DirectoryProperty
    @get:OutputDirectory abstract val jniLibs: DirectoryProperty
    @get:Inject abstract val exec: ExecOperations
    @get:Inject abstract val fs: FileSystemOperations

    init { outputs.upToDateWhen { false } }

    @TaskAction
    fun build() {
        check(ndk.get().asFile.isDirectory) { "no NDK at ${ndk.get().asFile}; set ANDROID_HOME and install it with sdkmanager \"ndk;<ndkVersion>\"" }
        // The NDK ships one x86_64 toolchain for macOS, Apple silicon included.
        val host = if (System.getProperty("os.name").startsWith("Mac")) "darwin-x86_64" else "linux-x86_64"
        val bin = ndk.get().dir("toolchains/llvm/prebuilt/$host/bin")
        fs.delete { delete(jniLibs) }
        targets.get().forEach { (abi, triple) ->
            exec.exec {
                workingDir(crate)
                commandLine("cargo", "build", "--locked", "--release", "--target", triple)
                environment(
                    "CARGO_TARGET_${triple.uppercase().replace('-', '_')}_LINKER",
                    bin.file("$triple${minSdk.get()}-clang").asFile.path,
                )
            }
            fs.copy {
                from(crate.get().file("target/$triple/release/libykt.so"))
                into(jniLibs.get().dir(abi))
            }
        }
    }
}

// The proof the build made a phone's library: for each ABI, a 64-bit ELF for
// that ABI's machine, every loadable segment aligned to at least 16 KB. A host
// library copied in by mistake fails the machine, an r27 link the alignment.
abstract class VerifyNativeLibs : DefaultTask() {
    @get:InputDirectory abstract val jniLibs: DirectoryProperty
    @get:Input abstract val machines: MapProperty<String, Int>

    @TaskAction
    fun verify() {
        machines.get().forEach { (abi, machine) ->
            val path = "$abi/libykt.so"
            val file = jniLibs.get().file(path).asFile
            check(file.isFile) { "$path is missing from ${jniLibs.get().asFile}" }
            val elf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            check(elf.getInt(0) == 0x464c457f && elf.get(4).toInt() == 2 && elf.getShort(18).toInt() == machine) {
                "$path is not a 64-bit ELF for $abi (e_machine ${elf.getShort(18)}, want $machine)"
            }
            val phoff = elf.getLong(32).toInt()
            val phentsize = elf.getShort(54).toInt()
            val loads = (0 until elf.getShort(56).toInt()).map { phoff + it * phentsize }.filter { elf.getInt(it) == 1 }
            check(loads.isNotEmpty()) { "$path has no loadable segment" }
            loads.forEach {
                val align = elf.getLong(it + 48)
                check(align >= 16384) { "$path has a segment aligned to $align bytes; Android needs 16 KB" }
            }
        }
    }
}

// The per-ABI libraries for an Android app's jniLibs/, under build/jniLibs.
val buildAndroid by tasks.registering(CargoAndroidBuild::class) {
    minSdk = providers.gradleProperty("androidMinSdk").map(String::toInt)
    targets = abis.mapValues { it.value.first }
    ndk = layout.dir(providers.environmentVariable("ANDROID_HOME")
        .orElse(providers.environmentVariable("ANDROID_SDK_ROOT")).orElse("\$ANDROID_HOME")
        .zip(providers.gradleProperty("ndkVersion")) { sdk, version -> File("$sdk/ndk/$version") })
    crate = layout.projectDirectory
    jniLibs = layout.buildDirectory.dir("jniLibs")
}

val verifyAndroid by tasks.registering(VerifyNativeLibs::class) {
    jniLibs = buildAndroid.flatMap { it.jniLibs }
    machines = abis.mapValues { it.value.second }
}
tasks.named("check") { dependsOn(verifyAndroid) }

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

// `test` runs the unit tests against the host build. A consumer can compile
// its own tests against the binding into the same run without forking the
// build: -PextraTestSources=<dir>.
providers.gradleProperty("extraTestSources").orNull?.let { sourceSets["test"].kotlin.srcDir(it) }

application {
    applicationDefaultJvmArgs = listOf("-Djna.library.path=${file("target/release").absolutePath}")
    mainClass.set("MainKt")
}
tasks.named("run") { dependsOn(buildNative) }
