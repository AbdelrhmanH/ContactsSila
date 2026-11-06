package app.sila.arham.widget

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.glance.appwidget.updateAll
import app.sila.arham.notification.resolveArhamKoin

/**
 * عامل انعاش الودجت (T8-10 · D3 · Issue #29) — مُشغِّل حصراً: يوقظ إعادة تركيب
 * [ArhamGlanceWidget] لكل ودجت مُثبَّت (`updateAll`)، والقراءة نفسها Room-مشتقة
 * عبر Koin داخل `provideGlance` — لا PII في أي WorkData، ولا منطق أعمال هنا.
 * Koin يُحلُّ بشكلٍ خامل داخل `doWork` (التهيئة الافتراضية لـ WorkManager تشغّل
 * الأعمال بعد `Application.onCreate` فقط — بلا مُهيِّئ مخصّص).
 */
class ArhamWidgetRefreshWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val koin = resolveArhamKoin()
        if (koin == null) {
            // Koin غير جاهز (نادر — إعادة تشغيل مبكرة): إعادة جدولة مؤقتة — بلا فشل دائم.
            Log.w(TAG, "Koin unavailable at widget refresh; retrying")
            return Result.retry()
        }
        return runCatching {
            ArhamGlanceWidget.updateAll(applicationContext) // إعادة تركيب لكل ودجت — قراءة Room حديثة
            Result.success()
        }.getOrElse {
            Log.w(TAG, "Widget refresh failed: ${it.javaClass.simpleName}")
            Result.retry()
        }
    }

    private companion object {
        const val TAG = "ArhamWidgetWorker"
    }
}
