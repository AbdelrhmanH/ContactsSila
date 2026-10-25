import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

/**
 * موديول طبقة البيانات لميزة جهات الاتصال (:feature:contacts:data)
 * 🔒 محلي 100%: ينفذ عقود :feature:contacts:domain ويعتمد على :core:database فقط (بلا شبكة).
 */
kotlin {
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "app.sila.feature.contacts.data"
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
            api(project(":feature:contacts:domain"))
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
