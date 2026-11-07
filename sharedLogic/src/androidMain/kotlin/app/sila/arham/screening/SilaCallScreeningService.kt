package app.sila.arham.screening

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import app.sila.arham.notification.resolveArhamKoin
import app.sila.feature.arham.domain.ScreeningSignalIngestionUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * خدمة فرز المكالمات الاختيارية (T8-09 · AC-4/AC-5) — مُثبَّتة من النظام عبر دور
 * `ROLE_CALL_SCREENING` (الطلب من الواجهة مسؤولية T8-11). عقدها الجامع: **لا تحكم على
 * المكالمات** — استجابة محايدة دائماً، ورصدٌ فقط يُدخل أحداثاً `UNASSOCIATED`/`PENDING`
 * بنتيجة `UNKNOWN` (الربط والتفسير لمسار التأكيد في T8-06).
 *
 * AC-5: الاستجابة المحايدة أول فعل في المسار — قبل أي DB/UI (الترتيب مثبَّت باختبار
 * صارم في `ScreeningDispatch`)، والزمن الفعلي ميكروثوانٍ على `Dispatchers.IO`.
 * الصادر: صمتٌ تام بلا استجابة ولا إدخال. كل إخفاق داخلي (Koin/Room) يُسجَّل بنوعٍ
 * حصراً ولا يسقط العملية (§O.5) — سطح التأكيد اليدوي في T8-06 غير متأثر.
 *
 * القراءة (`detailsReader`) قبل الإطلاق لأنها تجريد بدائياتٍ من الكائن المُمرَّر من
 * النظام (بلا DB ولا تعليق)، بينما الإدخال وحده هو عملُ التخزين.
 */
class SilaCallScreeningService : CallScreeningService() {

    internal var detailsReader: ScreeningDetailsReader =
        AndroidScreeningDetailsReader(app.sila.core.common.SystemAppClock)

    /** وجهة الاستجابة تُبنى لكل مكالمة — `respondToCall` يتطلب تفاصيل المكالمة نفسها. */
    internal var sinkFactory: (Call.Details) -> ScreeningResponseSink =
        { details -> ServiceScreeningResponseSink(this, details) }

    internal var dispatch: ScreeningDispatch = ScreeningDispatch()

    override fun onScreenCall(details: Call.Details) {
        val direction = if (Build.VERSION.SDK_INT >= 28) details.callDirection else null
        val signal = detailsReader.read(details)
        val sink = sinkFactory(details)
        val dispatch = this.dispatch
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                dispatch.handleScreenedCall(
                    callDirection = direction,
                    signal = signal,
                    sink = sink,
                    ingest = { s ->
                        val koin = resolveArhamKoin()
                        if (koin == null) {
                            Log.w(TAG, "Koin unavailable at screening ? signal skipped; confirmation surface unaffected")
                            null
                        } else {
                            koin.get<ScreeningSignalIngestionUseCase>()(s)
                        }
                    },
                )
            }.onFailure {
                Log.w(TAG, "Screening dispatch failed: ${it.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        const val TAG = "SilaCallScreening"
    }
}
