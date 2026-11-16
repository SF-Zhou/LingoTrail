import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/*
 * Versioning
 * ----------
 * The version name can be overridden with -PversionName=1.2.3 or the
 * VERSION_NAME environment variable (the release workflow passes the git tag).
 * versionCode is derived from semver: major * 10000 + minor * 100 + patch.
 */
val versionNameOverride = (findProperty("versionName") as String?) ?: System.getenv("VERSION_NAME")
val appVersionName = (versionNameOverride ?: "1.1.0").removePrefix("v").ifBlank { "1.0.0" }

/*
 * versionCode = major * 10000 + minor * 100 + patch.
 * Pre-release builds sit just below the matching stable release (e.g. 1.0.0-rc1
 * gets 9951) so that stable builds always receive a higher versionCode.
 */
val appVersionCode = run {
    val numeric = appVersionName.substringBefore('-')
    val parts = numeric.split(".")
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 1
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    val base = major * 10_000 + minor * 100 + patch
    if (appVersionName.contains('-')) {
        val prereleaseNumber = appVersionName.substringAfter('-').filter { it.isDigit() }
            .toIntOrNull()?.coerceIn(1, 49) ?: 1
        (base - 50 + prereleaseNumber).coerceAtLeast(1)
    } else {
        base
    }
}

/*
 * Release signing
 * ---------------
 * Credentials are read from environment variables (used by CI) or from a
 * local, git-ignored signing/keystore.properties file (used by scripts/release.sh).
 * When nothing is configured the release variant is built unsigned, which is
 * only useful for local smoke tests.
 */
val signingProperties = Properties().apply {
    val file = rootProject.file("signing/keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(envName: String, propertyName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: signingProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val keystorePath = signingValue("ANDROID_KEYSTORE_PATH", "storeFile")
    ?.let { path -> rootProject.file(path).takeIf { it.exists() } }
val keystorePassword = signingValue("ANDROID_KEYSTORE_PASSWORD", "storePassword")
val keystoreKeyAlias = signingValue("ANDROID_KEY_ALIAS", "keyAlias")
val keystoreKeyPassword = signingValue("ANDROID_KEY_PASSWORD", "keyPassword")
val hasReleaseSigning = keystorePath != null && keystorePassword != null &&
    keystoreKeyAlias != null && keystoreKeyPassword != null

android {
    namespace = "io.github.sfzhou.lingotrail"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.sfzhou.lingotrail"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // Optional demo/self-built default key. Never set for published builds.
        val defaultApiKey = (findProperty("defaultApiKey") as String?) ?: ""
        buildConfigField("String", "DEFAULT_API_KEY", "\"$defaultApiKey\"")
        buildConfigField("String", "DEEPSEEK_BASE_URL", "\"https://api.deepseek.com\"")
        buildConfigField("String", "SOURCE_URL", "\"https://github.com/SF-Zhou/LingoTrail\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = keystorePath
                storePassword = keystorePassword
                keyAlias = keystoreKeyAlias
                keyPassword = keystoreKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        disable += setOf("NullSafeMutableLiveData")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    // Real org.json implementation for JVM unit tests (the Android stub throws).
    testImplementation(libs.json)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
