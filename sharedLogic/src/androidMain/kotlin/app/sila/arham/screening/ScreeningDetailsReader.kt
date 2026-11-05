package app.sila.arham.screening

import android.os.Build
import android.telecom.Call
import app.sila.core.common.AppClock
import app.sila.feature.arham.domain.ScreenedCallSignal

/**
 * قارئ تفاصيل الفرز — seam للاختبار (`Call.Details` غير قابل للبناء في Robolectric،
 * فتُختبر المصفوفة عبر قرّاء زائفة والمسار الحقيقي عبر حامل البدائيات).
 */
fun interface ScreeningDetailsReader {
    /** يستخرج البدائيات من تفاصيل المنصة — دائماً غير فارغ (الغيابات قيم `null` داخلها). */
    fun read(details: Call.Details): ScreenedCallSignal
}

/** القارئ الحقيقي: الرقم من `handle` بعد تجريد مخطط `tel:`، والتحقق واجهة API 30+. */
internal class AndroidScreeningDetailsReader(
    private val clock: AppClock,
) : ScreeningDetailsReader {

    override fun read(details: Call.Details): ScreenedCallSignal {
        val number: String? = details.handle
            ?.schemeSpecificPart
            ?.takeIf { it.isNotBlank() }
        // قيم VERIFICATION_STATUS في AOSP: NOT_VERIFIED = 0، VERIFIED = 1 — الثوابت
        // مخفية (SystemApi) في حزم SDK لهذه الآلة (javap: غياب فعلي لا سهو)، فالقيمة
        // موثقة نصاً مع مطابقة تجميعية للـ getter العام `getCallerNumberVerificationStatus`.
        val verified = Build.VERSION.SDK_INT >= 30 &&
            details.callerNumberVerificationStatus == VERIFICATION_STATUS_VERIFIED
        return ScreenedCallSignal(
            remoteNumber = number,
            verificationPassed = verified,
            timestampMillis = clock.nowEpochMillis(),
        )
    }

    private companion object {
        /** `Call.Details.VERIFICATION_STATUS_VERIFIED` (SystemApi — القيمة الموثقة في AOSP). */
        const val VERIFICATION_STATUS_VERIFIED = 1
    }
}
