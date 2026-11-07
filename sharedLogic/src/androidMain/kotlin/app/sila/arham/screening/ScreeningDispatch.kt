package app.sila.arham.screening

import android.telecom.Call
import app.sila.feature.arham.domain.CallEventIngestion
import app.sila.feature.arham.domain.ScreenedCallSignal

/**
 * سياسات مسار الفرز (T8-09 · AC-5) — الصمت الصادر، والاستجابة المحايدة أول فعلٍ
 * قبل أي عمل تخزين، ثم الإدخال إن وُجدت إشارة. قرارُ الترتيب في مكانٍ واحد
 * (الوظيفة الحارة) ومثبَّتٌ باختبار صارم — لا يُعاد ترتيبه.
 */
class ScreeningDispatch {

    /**
     * المسار الكامل لإشارة فرز:
     * 1. الصادر ⇒ صمتٌ تام — لا استجابة ولا إدخال ولا انهيار (قرار مثبت: استجابةُ
     *    الصادر خارج عقد الفرز، وقيم `respond` عليه تُهمل من المنصة).
     * 2. غير الصادر (وارد/مجهول — المجهول قبل API 28 يُعامَل وارداً تحفّظاً) ⇒
     *    الاستجابة المحايدة **فوراً** — قبل أي DB/UI، وبغضّ النظر عن Koin أو قراءة الإشارة.
     * 3. إشارة غير قابلة للقراءة (نظرياً غير وارد من القارئ الحقيقي) ⇒ صمتٌ بعد الاستجابة.
     * 4. الإدخال عبر المستدعى المُمرَّر — قد يُرجع `null` (Koin غير متاح: §O.5 فشلٌ
     *    هادئ بنوعٍ حصراً؛ سطح التأكيد اليدوي في T8-06 غير متأثر).
     *
     * @param callDirection بدائية `Call.Details.callDirection` (`null` قبل API 28).
     * @param signal الإشارة المُستخرجة كبدائيات.
     * @param sink وجهة الاستجابة (الخادم الحقيقي أو زائفة الاختبار).
     * @param ingest إدخال الإشارة عبر `ScreeningSignalIngestionUseCase` — `null` = تخطٍّ هادئ.
     * @return نتيجة الإدخال، أو `null` عند الصمت/التخطي.
     */
    suspend fun handleScreenedCall(
        callDirection: Int?,
        signal: ScreenedCallSignal?,
        sink: ScreeningResponseSink,
        ingest: suspend (ScreenedCallSignal) -> CallEventIngestion?,
    ): CallEventIngestion? {
        if (callDirection == Call.Details.DIRECTION_OUTGOING) return null
        sink.respond(ScreeningResponse.NEUTRAL) // AC-5 — أول فعل، قبل أي عمل تخزين
        val readable = signal ?: return null
        return ingest(readable)
    }
}
