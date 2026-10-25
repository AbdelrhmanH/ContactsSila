import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    implementation(project(":sharedUI"))

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

/**
 * حساب versionCode تلقائياً من app-version في libs.versions.toml طبقاً لـ CODING_GUIDELINES.md §12:
 * MAJOR * 10000 + MINOR * 100 + PATCH (مثال: 1.0.0 -> 10000).
 */
val appVersionString = libs.versions.app.version.get()
val versionParts = appVersionString.split(".").map { it.toIntOrNull() ?: 0 }
val computedVersionCode =
    versionParts.getOrElse(0) { 1 } * 10_000 +
        versionParts.getOrElse(1) { 0 } * 100 +
        versionParts.getOrElse(2) { 0 }

android {
    namespace = "app.sila"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.sila"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = computedVersionCode
        versionName = appVersionString
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}
