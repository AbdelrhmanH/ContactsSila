import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.skie)
}

/**
 * Umbrella Module (:sharedLogic) — يجمع موديولات الميزات الفرعية (domain / data / presentation)
 * وموديولات النواة (:core:*) ويصدرها لـ iOS و Android.
 */
kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "SharedLogic"
            isStatic = true
            export(project(":core:common"))
            export(project(":core:model"))
            export(project(":core:database"))
            export(project(":core:datastore"))
            export(project(":feature:contacts:domain"))
            export(project(":feature:contacts:data"))
            export(project(":feature:contacts:presentation"))
            export(project(":feature:arham:domain"))
            export(project(":feature:arham:data"))
            export(project(":feature:arham:presentation"))
            export(project(":feature:services:domain"))
            export(project(":feature:services:data"))
            export(project(":feature:services:presentation"))
            export(project(":feature:settings:domain"))
            export(project(":feature:settings:data"))
            export(project(":feature:settings:presentation"))
        }
    }

    android {
        namespace = "app.sila.sharedLogic"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            api(project(":core:model"))
            api(project(":core:database"))
            api(project(":core:datastore"))

            // ميزة جهات الاتصال (Contacts Feature Sub-modules)
            api(project(":feature:contacts:domain"))
            api(project(":feature:contacts:data"))
            api(project(":feature:contacts:presentation"))

            // ميزة أرحام (Arham Feature Sub-modules)
            api(project(":feature:arham:domain"))
            api(project(":feature:arham:data"))
            api(project(":feature:arham:presentation"))

            // ميزة دليل الخدمات (Services Feature Sub-modules)
            api(project(":feature:services:domain"))
            api(project(":feature:services:data"))
            api(project(":feature:services:presentation"))

            // ميزة الإعدادات (Settings Feature Sub-modules)
            api(project(":feature:settings:domain"))
            api(project(":feature:settings:data"))
            api(project(":feature:settings:presentation"))

            api(libs.koin.core)
        }
        getByName("androidMain").dependencies {
            // androidContext() في appDeclaration (SilaApplication) — Issue #22.
            api(libs.koin.android)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.androidx.sqlite.bundled.jvm)
            implementation(libs.konsist)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.koin.test)
        }
    }
}

tasks.withType<KotlinNativeLink>().configureEach {
    val skieBinariesDir = layout.buildDirectory.dir("skie/binaries")

    doFirst {
        // Safeguard for touchlab/SKIE#208: remove stale Swift macro generated files before framework linking
        // (declared as a local val inside the task-configuration block so the doFirst closure stays
        // Gradle configuration-cache compatible — a script-level val would capture the script object,
        // failing cache storage with "cannot serialize Gradle script object references").
        val binariesRoot = skieBinariesDir.get().asFile
        if (binariesRoot.exists()) {
            binariesRoot.walkTopDown()
                .filter { file ->
                    file.isFile &&
                        file.name.startsWith("@__swiftmacro_") &&
                        file.name.endsWith(".swift") &&
                        file.invariantSeparatorsPath.contains("/swift/swift-generated-sources/")
                }
                .forEach { it.delete() }
        }
    }
}
