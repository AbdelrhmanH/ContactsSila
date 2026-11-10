// منصة قياس Macrobenchmark على العتاد المرجعي حصراً (ADR-0018 — بوابة المرحلة 1 · T10).
// هذا الموديول لا ينتج كود تطبيق: إنه APK قياس ذاتي الأجهزة (self-instrumenting) يستهدف
// :androidApp، ولا يعتمده أي موديول آخر — لا يمس حدود الخصوصية ولا يقرأ Room.
plugins {
    alias(libs.plugins.androidTest)
}

android {
    namespace = "app.sila.benchmark"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
    }

    targetProjectPath = ":androidApp"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        // AGP 9: موديول الاختبار يأتي ببنية debug ضمنياً دون release (فشل getByName("release")
        // هو ما أثبت ذلك) — فتُعرَّف بنية القياس وحدها هنا، وهي تقابل بنية "benchmark" في
        // :androidApp (نسخة release موقعة بمفتاح debug) لضمان أن المقيس بناء إنتاج لا debug،
        // ولا يُبنى في هذا الموديول سواها (الجمع عبر :benchmark:assembleBenchmark — ADR-0018).
        // البنى المخصصة لا ترث توقيع debug تلقائياً في AGP: بلا signingConfig يخرج APK الاختبار
        // غير موقَّع ويفشل تثبيته على الجهاز بـ INSTALL_PARSE_FAILED_NO_CERTIFICATES.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    // `AndroidBenchmarkRunner` تقطن `benchmark-junit4` الذي لم يعد يجلب تعدياً عبر
    // `benchmark-macro-junit4` منذ benchmark 1.5.0 — غيابها يُخرج APK بلا العدّادة
    // المعلنة في الـ manifest فينهار إقلاع التركيبة بـ `ClassNotFoundException`.
    implementation(libs.androidx.benchmark.junit4)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.testUiautomator)
    implementation(libs.androidx.testExt.junit)
}
