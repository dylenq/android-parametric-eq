import java.io.File
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Stable Compose set, chosen 2026-10-12 from dl.google.com maven metadata (stable releases only, no BOM).
val composeVersion = "1.10.6"
val material3Version = "1.4.0"
val activityComposeVersion = "1.13.0"
val lifecycleComposeVersion = "2.10.0"

android {
    namespace = "com.dylenq.eq"
    compileSdk = 37
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.dylenq.eq"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1-spike"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Studio-launched Gradle has no cargo on PATH; look in the usual toolchain dirs.
fun cargoBinDirs(): List<String> {
    val home = System.getProperty("user.home")
    return listOf(
        "$home/.cargo/bin",
        "$home/.rustup/toolchains/stable-aarch64-apple-darwin/bin",
        "$home/.rustup/toolchains/stable-x86_64-apple-darwin/bin",
    )
}

fun cargoExecutable(): String =
    cargoBinDirs().map { File(it, "cargo") }.firstOrNull { it.canExecute() }?.absolutePath
        ?: error("cargo not found in ${cargoBinDirs()}")

fun cargoPath(): String = cargoBinDirs().joinToString(":") + ":" + (System.getenv("PATH") ?: "")

abstract class CargoNdkTask : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations
    @get:Input abstract val cargo: Property<String>
    @get:Internal abstract val searchPath: Property<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val rustInputs: ConfigurableFileCollection
    @get:Input abstract val ndkHome: Property<String>
    @get:Internal abstract val repoRoot: DirectoryProperty
    @get:OutputDirectory abstract val jniOut: DirectoryProperty

    @TaskAction
    fun run() {
        exec.exec {
            workingDir = repoRoot.get().asFile
            environment("ANDROID_NDK_HOME", ndkHome.get())
            environment("PATH", searchPath.get())
            commandLine(
                cargo.get(), "ndk",
                "-t", "arm64-v8a", "-t", "x86_64", "-t", "armeabi-v7a",
                "-P", "28", "-o", jniOut.get().asFile.absolutePath,
                "build", "--release", "-p", "eq-core",
            )
        }
    }
}

abstract class BindgenTask : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations
    @get:Input abstract val cargo: Property<String>
    @get:Internal abstract val searchPath: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NAME_ONLY) abstract val library: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NAME_ONLY) abstract val uniffiToml: RegularFileProperty
    @get:Internal abstract val repoRoot: DirectoryProperty
    @get:OutputDirectory abstract val outDir: DirectoryProperty

    @TaskAction
    fun run() {
        exec.exec {
            workingDir = repoRoot.get().asFile
            environment("PATH", searchPath.get())
            commandLine(
                cargo.get(), "run", "--release", "-p", "uniffi-bindgen", "--",
                "generate", "--library", library.get().asFile.absolutePath,
                "--language", "kotlin", "--no-format",
                "--out-dir", outDir.get().asFile.absolutePath,
            )
        }
    }
}

val repoRootDir = rootProject.layout.projectDirectory

val cargoNdk = tasks.register<CargoNdkTask>("cargoNdkEqCore") {
    rustInputs.from(
        repoRootDir.dir("core/src"),
        repoRootDir.file("core/Cargo.toml"),
        repoRootDir.file("core/uniffi.toml"),
        repoRootDir.file("Cargo.toml"),
        repoRootDir.file("Cargo.lock"),
    )
    cargo.set(provider { cargoExecutable() })
    searchPath.set(provider { cargoPath() })
    ndkHome.set(androidComponents.sdkComponents.ndkDirectory.map { it.asFile.absolutePath })
    repoRoot.set(repoRootDir)
    jniOut.set(layout.buildDirectory.dir("generated/eq-core/jniLibs"))
}

val uniffiBindings = tasks.register<BindgenTask>("uniffiBindingsEqCore") {
    cargo.set(provider { cargoExecutable() })
    searchPath.set(provider { cargoPath() })
    library.set(cargoNdk.flatMap { it.jniOut.file("arm64-v8a/libeq_core.so") })
    uniffiToml.set(repoRootDir.file("core/uniffi.toml"))
    repoRoot.set(repoRootDir)
    outDir.set(layout.buildDirectory.dir("generated/eq-core/kotlin"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoNdk, CargoNdkTask::jniOut)
        variant.sources.kotlin?.addGeneratedSourceDirectory(uniffiBindings, BindgenTask::outDir)
    }
}

dependencies {
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.compose.ui:ui:$composeVersion")
    implementation("androidx.compose.ui:ui-graphics:$composeVersion")
    implementation("androidx.compose.foundation:foundation:$composeVersion")
    implementation("androidx.compose.material3:material3:$material3Version")
    implementation("androidx.activity:activity-compose:$activityComposeVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycleComposeVersion")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:$composeVersion")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
}
