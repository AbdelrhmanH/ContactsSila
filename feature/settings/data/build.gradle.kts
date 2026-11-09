import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

/**
 * موديول طبقة البيانات لميزة الإعدادات (:feature:settings:data)
 * 🔒 ينفذ عقود :feature:settings:domain ويعتمد على :core:datastore و :core:database.
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
            implementation(project(":core:database"))
            implementation(libs.kotlinx.datetime)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.koin.core)
        }
        getByName("androidHostTest").dependencies {
            // تذكرة #63 (S2-10): Robolectric + WorkManager الاختباري لاختبار عامل نغمة
            // التصنيف كاملاً فوق Robolectric — بلا Koin حقيقية (إديوم sharedLogic · T8-10).
            implementation(libs.robolectric)
            implementation(libs.androidx.work.testing)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
