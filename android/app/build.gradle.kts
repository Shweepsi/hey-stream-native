import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// Cœur Rust ------------------------------------------------------------------
//
// Les liaisons Kotlin et la bibliothèque native sont produites par cargo à
// chaque build (cargo ne recompile que ce qui a changé). Elles ne sont jamais
// versionnées.

val rustRoot: File = rootDir.parentFile
// arm64-v8a : Xiaomi Pad 7 ; armeabi-v7a : Mi Box S (espace utilisateur 32 bits).
// La CI ajoute x86_64 pour l'émulateur (-Pheystream.abis=x86_64).
val rustAbis: List<String> = providers.gradleProperty("heystream.abis").orNull
    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
    ?: listOf("arm64-v8a", "armeabi-v7a")

/** Sources dont dépend le cœur : un changement relance cargo. */
fun rustSources() = files(
    fileTree(rustRoot.resolve("core/src")),
    fileTree(rustRoot.resolve("ffi/src")),
    rustRoot.resolve("core/Cargo.toml"),
    rustRoot.resolve("ffi/Cargo.toml"),
    rustRoot.resolve("ffi/uniffi.toml"),
    rustRoot.resolve("Cargo.toml"),
    rustRoot.resolve("Cargo.lock"),
)

/** `libheystream.so` pour chaque ABI, compilée en release (LTO). */
abstract class CargoNdkTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val repoRoot: DirectoryProperty

    @get:Input
    abstract val abis: ListProperty<String>

    @get:Input
    abstract val minSdk: Property<Int>

    @get:Internal
    abstract val ndkDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val jniLibs: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun build() {
        val out = jniLibs.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        exec.exec {
            workingDir = repoRoot.get().asFile
            environment("ANDROID_NDK_HOME", ndkDirectory.get().asFile.absolutePath)
            commandLine(
                listOf("cargo", "ndk") +
                    abis.get().flatMap { listOf("-t", it) } +
                    listOf("--platform", minSdk.get().toString(), "-o", out.absolutePath) +
                    listOf("build", "-p", "heystream-ffi", "--release"),
            )
        }
    }
}

/**
 * Liaisons Kotlin, générées depuis la bibliothèque de l'hôte (même code,
 * métadonnées UniFFI intactes), comme `scripts/bindings.sh`.
 */
abstract class UniffiBindingsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val repoRoot: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun generate() {
        val root = repoRoot.get().asFile
        val out = outputDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        exec.exec {
            workingDir = root
            commandLine("cargo", "build", "-q", "-p", "heystream-ffi")
        }
        val lib = if (System.getProperty("os.name").startsWith("Mac")) "libheystream.dylib" else "libheystream.so"
        exec.exec {
            workingDir = root
            commandLine(
                "cargo", "run", "-q", "-p", "heystream-ffi", "--features", "bindgen",
                "--bin", "uniffi-bindgen", "--", "generate",
                "--library", root.resolve("target/debug/$lib").absolutePath,
                "--language", "kotlin", "--out-dir", out.absolutePath, "--no-format",
            )
        }
    }
}

val cargoNdk = tasks.register<CargoNdkTask>("cargoNdk") {
    sources.from(rustSources())
    repoRoot.set(rustRoot)
    abis.set(rustAbis)
    minSdk.set(30)
    ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
    jniLibs.set(layout.buildDirectory.dir("generated/rust/jniLibs"))
}

val uniffiBindings = tasks.register<UniffiBindingsTask>("uniffiBindings") {
    sources.from(rustSources())
    repoRoot.set(rustRoot)
    outputDir.set(layout.buildDirectory.dir("generated/rust/kotlin"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoNdk, CargoNdkTask::jniLibs)
        variant.sources.kotlin?.addGeneratedSourceDirectory(uniffiBindings, UniffiBindingsTask::outputDir)
    }
}

// Application ----------------------------------------------------------------

android {
    namespace = "io.github.shweepsi.heystream"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "io.github.shweepsi.heystream"
        // Android 11 : Mi Box S 2e gén. (docs/MATERIEL.md).
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += rustAbis }
    }

    signingConfigs {
        // Clé de test publique (voir android/README.md) : les APK de la CI
        // s'installent par-dessus les précédents sans désinstaller. À
        // remplacer par une clé privée avant toute publication.
        create("test") {
            storeFile = file("test.keystore")
            storePassword = "heystream-test"
            keyAlias = "heystream-test"
            keyPassword = "heystream-test"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("test")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("test")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                test.maxHeapSize = "1536m"
                // Miroir de Maven Central pour les fichiers d'Android de Robolectric
                // (propriété `robolectric.repo` de ~/.gradle/gradle.properties).
                providers.gradleProperty("robolectric.repo").orNull?.let {
                    test.systemProperty("robolectric.dependency.repo.url", it)
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.work.runtime)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.ui)
    implementation(libs.media3.ffmpeg)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    // Version Android de JNA (libjnidispatch.so), utilisée par les liaisons UniFFI.
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    // Test de bout en bout sur émulateur (CI) : faux panneau local.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.mockwebserver)
}
