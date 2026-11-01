import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

// ADR-0011: Room 3 هو صاحب المخطط الوحيد — تصدير مخطط JSON قابل للمراجعة والالتزام في Git.
room3 {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "app.sila.core.database"
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
            api(project(":core:common"))
            api(project(":core:model"))
            // PR B (F-04/F-05): room3-runtime هو وقت التشغيل الرسمي لمصدر الحقيقة الوحيد (ADR-0011) —
            // يحل محل androidx.room 2.7.0 (الشل القديم أُزيل من نفس التغيير).
            api(libs.androidx.room3.runtime)
            api(libs.androidx.sqlite.bundled)
            // PR B: امتدادات التعليق (`prepare`/`step`/`executeSQL`) في وحدة sqlite-async المستقلة —
            // يحتاجها مسار SQL الخام داخل معاملات Room (ADR-0011).
            api(libs.androidx.sqlite.async)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.androidx.sqlite.bundled.jvm)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.androidx.room3.testing)
        }
    }
}

dependencies {
    // معالج Room 3 لكل هدف تجميع (KSP2 — نسخة مستقلة عن إصدار Kotlin).
    add("kspAndroid", libs.androidx.room3.compiler)
    add("kspIosArm64", libs.androidx.room3.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room3.compiler)
}
