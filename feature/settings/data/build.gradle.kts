import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

/**
 * موديول طبقة البيانات لميزة الإعدادات (:feature:settings:data)
 * 🔒 ينفذ عقود :feature:settings:domain ويعتمد على :core:datastore فقط.
 */
kotlin {
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "app.sila.feature.settings.data"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":feature:settings:domain"))
            implementation(project(":core:datastore"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
