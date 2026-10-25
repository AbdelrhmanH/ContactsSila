import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

/**
 * موديول طبقة البيانات لميزة دليل الخدمات (:feature:services:data)
 * 🌐 الموديول الوحيد المسموح له بمزامنة أرقام الخدمات العامة مستقبلاً مع :core:network / :core:firebase.
 */
kotlin {
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "app.sila.feature.services.data"
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
            api(project(":feature:services:domain"))
            implementation(project(":core:database"))
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.androidx.sqlite.bundled.jvm)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
