import java.util.Properties

fun parseDotEnv(file: File): Map<String, String> {
  if (!file.isFile) return emptyMap()
  return file.readLines().mapNotNull { rawLine ->
    val line = rawLine.trim()
    if (line.isBlank() || line.startsWith("#") || !line.contains('=')) return@mapNotNull null
    val key = line.substringBefore('=').removePrefix("export ").trim()
    val value = line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
    key.takeIf { it.isNotBlank() }?.let { it to value }
  }.toMap()
}

fun quotedBuildConfig(value: String): String =
  "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun usableSecret(value: String?): String {
  val cleaned = value?.trim().orEmpty()
  if (cleaned.isBlank()) return ""
  val upper = cleaned.uppercase()
  if (upper.startsWith("YOUR_") || upper.startsWith("MY_") || upper.contains("PLACEHOLDER")) return ""
  return cleaned
}

val localProperties = Properties().apply {
  // Worktree builds may live under GROKV5/; also load the parent clone's local.properties.
  listOf(
    rootProject.file("local.properties"),
    rootProject.rootDir.parentFile?.resolve("local.properties"),
  ).filterNotNull().forEach { file ->
    if (file.isFile) file.inputStream().use(::load)
  }
}
val dotEnv = parseDotEnv(rootProject.file(".env")) +
  parseDotEnv(rootProject.rootDir.parentFile?.resolve(".env") ?: File(""))
fun projectSecret(name: String): String =
  usableSecret(System.getenv(name)).ifBlank {
    usableSecret(localProperties.getProperty(name)).ifBlank {
      usableSecret(dotEnv[name])
    }
  }

val openAiApiKey = projectSecret("OPENAI_API_KEY")
val openAiModel = projectSecret("OPENAI_MODEL").ifBlank { "gpt-5.5" }
val openAiBaseUrl = projectSecret("OPENAI_BASE_URL").ifBlank { "https://api.openai.com/v1/responses" }
val openAiProxyToken = projectSecret("OPENAI_PROXY_TOKEN").ifBlank { projectSecret("PROXY_AUTH_TOKEN") }
// Gemini only — use GEMINI_API_KEY in local.properties / .env / CI secrets.
val geminiApiKey = projectSecret("GEMINI_API_KEY")
val geminiModel = projectSecret("GEMINI_MODEL").ifBlank { "gemini-3.5-flash" }
val mapsApiKey = projectSecret("MAPS_API_KEY")

val releaseKeystorePath = System.getenv("KEYSTORE_PATH")
val releaseKeystoreFile = releaseKeystorePath?.takeIf { it.isNotBlank() }?.let { file(it) }
val releaseStorePassword = System.getenv("STORE_PASSWORD")
val releaseKeyAlias = System.getenv("KEY_ALIAS") ?: "upload"
val releaseKeyPassword = System.getenv("KEY_PASSWORD")
val hasReleaseSigning =
  !releaseKeystorePath.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank() &&
    releaseKeystoreFile?.isFile == true

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.legacy.kapt)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.lidardetector.pkrxtz"
    minSdk = 24
    targetSdk = 36
    versionCode = 3
    versionName = "2.0"
    multiDexEnabled = true

    buildConfigField("String", "OPENAI_API_KEY", quotedBuildConfig(openAiApiKey))
    buildConfigField("String", "OPENAI_MODEL", quotedBuildConfig(openAiModel))
    buildConfigField("String", "OPENAI_BASE_URL", quotedBuildConfig(openAiBaseUrl))
    buildConfigField("String", "OPENAI_PROXY_TOKEN", quotedBuildConfig(openAiProxyToken))
    buildConfigField("String", "GEMINI_API_KEY", quotedBuildConfig(geminiApiKey))
    buildConfigField("String", "GEMINI_MODEL", quotedBuildConfig(geminiModel))
    buildConfigField("String", "MAPS_API_KEY", quotedBuildConfig(mapsApiKey))
    manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    if (hasReleaseSigning) {
      create("release") {
        storeFile = requireNotNull(releaseKeystoreFile)
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      }
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
    }
  }
}

androidComponents {
  onVariants(selector().all()) { variant ->
    variant.outputs.forEach { output ->
      output.javaClass.methods
        .firstOrNull { it.name == "setOutputFileName" && it.parameterCount == 1 }
        ?.invoke(output, "findit-v2-${variant.buildType}.apk")
    }
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.core.splashscreen)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.profileinstaller)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.laszip4j)
  implementation(libs.nga.tiff)
  implementation(libs.google.generativeai)
  implementation(libs.maps.compose)
  implementation(libs.okhttp)
  implementation(libs.play.services.maps)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  add("kapt", libs.androidx.room.compiler)
  // Studio LeakCanary registers LeakLauncherActivity as a second LAUNCHER entry, so the system
  // resolver (and some home screens) open that instead of MainActivity. Omit it from device builds.
}
