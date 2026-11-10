package app.sila.di

import app.sila.core.common.crash.CrashSink
import app.sila.core.common.crash.LocalCrashCapture
import app.sila.core.datastore.UserPreferencesDataSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * موديول التقاط الأعطال المحلي (ADR-0019 — قرار المالك 2026-11-10): **محلي حصراً
 * — لا شبكة ولا SDK سحابي في المرحلة 1**. المنطق المشترك هنا: بوابة
 * `crashReportsEnabled` fail-closed تُقرأ وقت العطل حصراً (ليس عند الإقلاع —
 * التبديل الأخير في الإعدادات هو الحاكم)، ومنفذ الكتابة [CrashSink] المنصوي
 * عبر [platformCrashModule].
 */
internal val crashCaptureModule: Module = module {
    single<LocalCrashCapture> {
        val preferences = get<UserPreferencesDataSource>()
        LocalCrashCapture(
            isCaptureEnabled = {
                runBlocking {
                    withTimeoutOrNull(timeMillis = CRASH_GATE_READ_TIMEOUT_MILLIS) {
                        preferences.preferences.first().crashReportsEnabled
                    }
                } == true
            },
            sink = get(),
        )
    }
}

/** سقف مهلة قراءة البوابة على مسار العطل الطرفي (fail-closed عند التجاوز). */
private const val CRASH_GATE_READ_TIMEOUT_MILLIS = 2_000L

/**
 * منفذ الكتابة المنصوي (ADR-0019): كل منصة تكتب في تخزينها الخاص حصراً —
 * actuals في `PlatformCrashModule.android.kt` / `.ios.kt` بنمط الموديولات
 * المنصوية للمستودع (fallback مضيف-غائب في اختبارات JVM بلا Context).
 */
internal expect val platformCrashModule: Module
