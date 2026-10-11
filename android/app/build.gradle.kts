import java.util.Properties
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Machine-local values (never in git, AGENTS.md rule 8): android/local.properties
//   elevate.apiBaseUrl=https://<project-ref>.supabase.co/functions/v1/api
//   elevate.supabaseUrl=https://<project-ref>.supabase.co
//   elevate.supabasePublishableKey=<publishable key>
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.elevate.app"
    // Compose UI 1.12.1 and core-ktx 1.19.1 (pinned) require compiling against API 37; runtime targets 36.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.elevate.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val apiBaseUrl = localProps.getProperty("elevate.apiBaseUrl", "")
        buildConfigField("String", "API_BASE_URL", "\"" + apiBaseUrl.replace("\"", "") + "\"")
        // Anonymous sign-in (GoTrue) for the upload client: the project URL and the publishable key
        // only (contracts §1). Never a service-role or secret key (AGENTS.md rule 8).
        val supabaseUrl = localProps.getProperty("elevate.supabaseUrl", "")
        buildConfigField("String", "SUPABASE_URL", "\"" + supabaseUrl.replace("\"", "") + "\"")
        val supabasePublishableKey = localProps.getProperty("elevate.supabasePublishableKey", "")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"" + supabasePublishableKey.replace("\"", "") + "\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        // Versions are pinned by decision (D-001, android/AGENTS.md) and bumped deliberately, not
        // by lint; targetSdk stays 36 until behaviour changes are tested on both phones.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable", "OldTargetApi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // Upload fallback below API 34 (long-running worker, dataSync foreground); API 34+ uses a
    // user-initiated data transfer job (android/UPLOAD_CLIENT.md).
    implementation(libs.androidx.work.runtime)

    // Record live (ui/record, data/record): CameraX, all five modules on one version (view = PreviewView).
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)

    // All Media3 modules on one version (android/AGENTS.md). effect = OverlayEffect pipeline.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.effect)
    implementation(libs.media3.common)
    implementation(libs.media3.ui.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

/**
 * Real fixtures come from Person 4's `fixtures/` (D-261011-HW4), never from a copy in `android/`:
 * the trail labels and recorded event sequences are copied into the app's assets at build time.
 * A missing file means no annotations, never a fallback.
 */
abstract class CopyRealFixtures : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile.resolve("fixtures")
        out.deleteRecursively()
        out.mkdirs()
        sources.files.filter { it.isFile }.forEach { it.copyTo(out.resolve(it.name)) }
    }
}

val copyRealFixtures = tasks.register<CopyRealFixtures>("copyRealFixtures") {
    sources.from(
        rootProject.layout.projectDirectory.dir("../fixtures").asFileTree.matching {
            include("fixture_trail*.json", "fixture_events*.jsonl")
        },
    )
}

/**
 * Engine output Person 1 recorded on Modal for evaluation (`engine/eval/runs/<run>/`): a real
 * window_result.v1 and its manifest, copied as `engine_runs/<run>/…`. Shown in the app only as a
 * recorded run, never as live analysis.
 */
abstract class CopyEngineRuns : DefaultTask() {
    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile.resolve("engine_runs")
        out.deleteRecursively()
        out.mkdirs()
        val root = runsDir.orNull?.asFile ?: return
        root.listFiles().orEmpty().filter { it.isDirectory }.forEach { run ->
            val wr = run.resolve("window_result.json")
            val mf = run.resolve("manifest.json")
            if (wr.isFile && mf.isFile) {
                wr.copyTo(out.resolve("${run.name}/window_result.json"))
                mf.copyTo(out.resolve("${run.name}/manifest.json"))
            }
        }
    }
}

val copyEngineRuns = tasks.register<CopyEngineRuns>("copyEngineRuns") {
    val runs = rootProject.layout.projectDirectory.dir("../engine/eval/runs")
    if (runs.asFile.isDirectory) runsDir.set(runs)
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyRealFixtures, CopyRealFixtures::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(copyEngineRuns, CopyEngineRuns::outputDir)
    }
}

