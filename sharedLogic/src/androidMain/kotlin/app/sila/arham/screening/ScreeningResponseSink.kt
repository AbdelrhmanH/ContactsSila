package app.sila.arham.screening

import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * استجابة الفرز كبدائيات حصراً (T8-09 · AC-5) — `CallScreeningService.CallResponse`
 * غير قابل للبناء في اختبارات المضيف، فتحمل القرار كحملٍ نقي ويبنيه الوجهة الحقيقية.
 */
data class ScreeningResponse(
    val disallowCall: Boolean,
    val rejectCall: Boolean,
    val skipCallLog: Boolean,
    val skipNotification: Boolean,
) {
    companion object {
        /**
         * المحايدة: سلوك غياب الخدمة تماماً — لا حجب ولا رفض ولا إخفاء من سجل أو
         * إشعارات. صِلَة لا تحكم على المكالمات الواردة في المرحلة الأولى؛ الفرز هنا
         * رصدٌ اختياري فقط (AC-4) والقرار للمستخدم دائماً.
         */
        val NEUTRAL: ScreeningResponse = ScreeningResponse(
            disallowCall = false,
            rejectCall = false,
            skipCallLog = false,
            skipNotification = false,
        )
    }
}

/**
 * وجهة بث استجابة الفرز — seam للاختبار (المستقبل الصارم يسجّل الترتيب: الاستجابة
 * قبل أي إدخال DB). التنفيذ الحقيقي يبني `CallResponse` ويستدعي `service.respond`.
 */
fun interface ScreeningResponseSink {
    fun respond(response: ScreeningResponse)
}

/** الوجهة الحقيقية فوق الخدمة المرتبطة من النظام — تُبنى لكل مكالمة بتفاصيلها. */
internal class ServiceScreeningResponseSink(
    private val service: CallScreeningService,
    private val details: Call.Details,
) : ScreeningResponseSink {
    override fun respond(response: ScreeningResponse) {
        service.respondToCall(
            details,
            CallScreeningService.CallResponse.Builder()
                .setDisallowCall(response.disallowCall)
                .setRejectCall(response.rejectCall)
                .setSkipCallLog(response.skipCallLog)
                .setSkipNotification(response.skipNotification)
                .build(),
        )
    }
}
