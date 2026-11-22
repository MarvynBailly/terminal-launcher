import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing. CI passes the keystore and its credentials through environment variables;
// local release builds read keystore.properties in the project root (git-ignored). With neither,
// the release build is left unsigned and debug builds work with no setup.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotEmpty() } ?: keystoreProps.getProperty(prop)

val releaseStoreFile = signingValue("KEYSTORE_FILE", "storeFile")

// The version comes from the release tag: CI passes -PverName=0.2.0 for the tag v0.2.0.
// The version code is derived from it, so it rises whenever the version does:
// 0.2.0 -> 200, 1.4.2 -> 10402. (These are verName/verCode because -PversionName would clash
// with the Android DSL.)
val appVersionName = (project.findProperty("verName") ?: "0.1.0").toString()
val appVersionCode = (project.findProperty("verCode") as String?)?.toInt()
    ?: appVersionName.split(".")
        .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        .let { it.getOrElse(0) { 0 } * 10000 + it.getOrElse(1) { 0 } * 100 + it.getOrElse(2) { 0 } }
        .coerceAtLeast(1)

android {
    namespace = "com.terminallauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.terminallauncher"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // 2025.02.00 is Compose 1.7.8. Compose 1.6 crashes in the text field on Android 16 when the
    // window loses the focus, as it does to Android's own unlock screen, while the keyboard is
    // following the cursor (a null check added in 1.7). Do not go back below 1.7.
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.core:core-ktx:1.12.0")
    // ssh
    implementation("com.github.mwiede:jsch:2.28.8")

    testImplementation("junit:junit:4.13.2")
    // Android's own JSON reader is only a stub off the phone. This is the same one, for the tests.
    testImplementation("org.json:json:20240303")
}
