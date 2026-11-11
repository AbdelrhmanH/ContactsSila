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

/**
 * مسار التوقيع المحروس عبر أسرار CI (ADR-0020 — قرار المالك 2026-11-10): يُقرأ من
 * متغيرات البيئة حصراً — **لا أي سر في المستودع**. حين تتوفر المتغيرات الأربعة
 * (محلياً عبر shell أو على CI عبر أسرار المستودع) تُوقَّع `release` بمفتاح الإنتاج؛
 * وغبوها يُبقي الوضع الراهن حرفاً: بلا `signingConfig` (fallback AGP — مفتاح debug
 * الافتراضي للحزم)، وبنية `benchmark` تبقى debug-signed بالتصميم (ADR-0018) أياً
 * كان توفر الأسرار.
 */
val releaseKeystorePath: String? = System.getenv("SILA_RELEASE_KEYSTORE_PATH")
val releaseKeystorePassword: String? = System.getenv("SILA_RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("SILA_RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("SILA_RELEASE_KEY_PASSWORD")
val hasReleaseSigning: Boolean =
    !releaseKeystorePath.isNullOrBlank() &&
        !releaseKeystorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

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
    // ADR-0020: تكوين توقيع الإنتاج يُبنى حصراً حين تتوفر متغيرات البيئة الأربعة —
    // الحارس fail-safe: أي متغير غائب/فارغ يعني لا تكوين ولا توقيع إنتاجي.
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = requireNotNull(releaseKeystorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // ADR-0020: توقيع الإنتاج محروس — لا مفتاح في المستودع أبداً؛ بلا أسرار
            // يبقى fallback AGP (debug keystore الافتراضي للحزم) كما هو.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // بنية القياس (ADR-0018): نسخة إنتاج بلا تصحيح ومصغّرة كما في release —
        // لكنها موقعة بمفتاح debug ليمكن تثبيتها وقراءة آثار التتبع أثناء القياس
        // على العتاد المرجعي حصراً؛ matchingFallbacks يجعلها تستهلك نسخ release
        // من مكتبات KMP (:sharedUI وغيرها) عند مطابقة البنى.
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
            // تذكرة #95 (تعديل ADR-0018 بتاريخ 2026-11-11): معرّف معزول فريد لنسخة
            // القياس `app.sila.bench` — لا يستبدل التطبيق الحقيقي `app.sila` على
            // الجهاز ولا يزيله عند الـ teardown، ولا يصطدم بحزمة الأجهزة الاختبارية
            // `app.sila.benchmark` لموديول `:benchmark` (الحارس أدناه يقفل الثلاثية).
            applicationIdSuffix = ".bench"
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

// ============================================================================
// حارس هوية الحزم (تذكرة #95 · تعديل ADR-0018 بتاريخ 2026-11-11): فشل إعداد فوري
// (configuration failure) على كل بناء وCI عند أي خلط بين هوية التطبيق الحقيقي
// وهوية نسخة القياس. الجلسة الميدانية 2026-11-10 أثبتت الخطر: بنية `benchmark`
// ورثت `app.sila` من defaultConfig فاستبدلت التطبيق الحقيقي على الجهاز ثم أزالته
// عند الـ teardown. القاعدة الثلاثية المقفلة هنا:
//   1) إنتاج/debug = `app.sila` حصراً — هوية المتجر لا تنحرف أبداً.
//   2) بنية القياس = معرّف معزول فريد ≠ `app.sila` و≠ `app.sila.benchmark`
//      (حزمة الأجهزة الاختبارية لموديول `:benchmark`) — عزل ثلاثي كامل.
//   3) بقية البنى تمر عبر (1) — فيفشل الحارس عند الإعداد لا عند التشغيل فقط.
// ============================================================================
val productionApplicationId = "app.sila"
// ثابت التزامن مع الحارس الإيجابي في benchmark/build.gradle.kts (namespace == هذا الثابت
// حصراً) — تغيير أحدهما يُلزم بتحديث الآخر وفحص TARGET_PACKAGE في StartupBenchmark.kt.
val benchmarkInstrumentationId = "app.sila.benchmark"

androidComponents {
    onVariants { variant ->
        val variantId = variant.applicationId.get()
        if (variant.buildType == "benchmark") {
            check(variantId != productionApplicationId) {
                "بنية القياس `${variant.name}` تحمل معرّف التطبيق الحقيقي `$variantId` (تذكرة #95) — " +
                    "أضف `applicationIdSuffix` معزولاً لبنية `benchmark` في `:androidApp`."
            }
            check(variantId != benchmarkInstrumentationId) {
                "بنية القياس `${variant.name}` (`$variantId`) تصطدم بحزمة الأجهزة الاختبارية " +
                    "لموديول `:benchmark` — اختر لاحقة لا تُنتج `$benchmarkInstrumentationId`."
            }
        } else {
            check(variantId == productionApplicationId) {
                "هوية البنية `${variant.name}` هي `$variantId` والمتوقع `$productionApplicationId` — " +
                    "هوية المتجر لا تنحرف أبداً (تذكرة #95)."
            }
        }
    }
}
