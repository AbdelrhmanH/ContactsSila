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
    // S2-14 (#68): AppCompatActivity لا ComponentActivity — تطبيق اللغة على المنصات
    // 26–32 يعمل عبر مندوب appcompat حصراً (يُقترن بأنشطة AppCompatActivity)،
    // والمنصة 33+ تطبّقها نظامياً عبر LocaleManager.
    implementation(libs.androidx.appcompat)

    // ADR-0018 (بوابة CI): ProfileInstaller ≥1.3 شرط Macrobenchmark لإعادة تثبيت
    // ملفات التعريف ومسح كاش الشيدرات بين قياسات الإقلاع على العتاد المرجعي
    implementation(libs.androidx.profileinstaller)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)

    // اختبار تحقق تهيئة النسخ الاحتياطي (F-06) — JUnit JVM فقط، بلا Robolectric.
    testImplementation(libs.junit)
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
        // بنية القياس (ADR-0018): نسخة إنتاج بلا تصحيح ومصغّرة كما في release —
        // لكنها موقعة بمفتاح debug ليمكن تثبيتها وقراءة آثار التتبع أثناء القياس
        // على العتاد المرجعي حصراً؛ matchingFallbacks يجعلها تستهلك نسخ release
        // من مكتبات KMP (:sharedUI وغيرها) عند مطابقة البنى.
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
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
