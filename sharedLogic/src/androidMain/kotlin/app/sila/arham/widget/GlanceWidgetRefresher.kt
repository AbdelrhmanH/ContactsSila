package app.sila.arham.widget

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.sila.core.database.dao.ArhamDataChangeListener
import java.util.concurrent.TimeUnit

/**
 * جسر «تغيّرت بيانات أرحام» إلى انعاش الودجت (T8-10 · D3 · Issue #29) — مشتركٌ وحيد
 * على خطاف [ArhamDataChangeListener] (يُسجِّله SilaApplication على الواجهة الحقيقية):
 * كل دفعة كتابات تُطلق عملًا أحاديًا باسم فريد بسياسة `REPLACE` (يُدمِّج الاندفاعات —
 * تشغيلة واحدة تعيد قراءة Room حديثة كلياً فلا تقادم).
 *
 * الكادنس الدوري (30 دقيقة، اسم فريد، سياسة `UPDATE` — idempotent عبر إعادة
 * التسجيل عند كل بدء عملية) يُسجَّل من [enqueuePeriodic] — كلمة «دقيق» لا مكان
 * لها هنا (§D3-13). فشل الجدولة يُسجَّل بنوعٍ حصراً (§O.5) — المستمع لا يُسقط
 * الكتابة التي أطلقته أبداً.
 */
class GlanceWidgetRefresher(private val context: Context) : ArhamDataChangeListener {

    override fun onArhamDataChanged() {
        runCatching { enqueueOneShot(context) }
            .onFailure { Log.w(TAG, "Event-driven widget refresh failed: ${it.javaClass.simpleName}") }
    }

    companion object {
        private const val TAG = "ArhamWidgetRefresh"

        /** الاسم الفريد للعمل الدوري (D3: 30 دقيقة — `UPDATE` يستبدل لا يُراكم). */
        const val PERIODIC_WORK_NAME = "arham_widget_periodic_refresh"

        /** الاسم الفريد للعمل الأحادي المُحرَّك بالتغيير (`REPLACE` يدمج الدفعات). */
        const val ONE_SHOT_WORK_NAME = "arham_widget_data_refresh"

        /** التسجيل الدوري — idempotent (سياسة UPDATE)؛ يُستدعى مرة من SilaApplication. */
        fun enqueuePeriodic(context: Context) {
            runCatching {
                val request = PeriodicWorkRequestBuilder<ArhamWidgetRefreshWorker>(30, TimeUnit.MINUTES)
                    .build()
                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            }.onFailure { Log.w(TAG, "Periodic widget refresh enqueue failed: ${it.javaClass.simpleName}") }
        }

        /** الانعاش المُحرَّك بالتغيير — `REPLACE` يدمج أي دفعة سابقة غير منفَّذة. */
        fun enqueueOneShot(context: Context) {
            val request = OneTimeWorkRequestBuilder<ArhamWidgetRefreshWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_SHOT_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
