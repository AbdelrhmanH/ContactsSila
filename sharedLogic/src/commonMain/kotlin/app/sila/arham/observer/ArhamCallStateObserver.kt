package app.sila.arham.observer

import app.sila.feature.arham.domain.CorrelateObservedCallEndUseCase
import kotlinx.coroutines.CoroutineScope

/**
 * عقد مراقب حالة المكالمات (T8-09 أندرويد · T8-12 iOS · AC-4/AC-17) — **حالة فقط بلا
 * هوية**: لا رقم ولا جهة ولا محتوى يمرّ هنا قط؛ النهاية المرصودة تُربَط بمحاولة صِلَة
 * المفتوحة عبر [CorrelateObservedCallEndUseCase] (حل D-2 بالربط لا باختراع المفاتيح).
 *
 * العقد في النطاق المشترك حصراً (قاعدة «expect/actual أو interfaces» — كل منصة تنفّذه
 * فوق إمكاناتها: أندرويد `TelephonyManager` ضمن صلاحية `READ_PHONE_STATE`، وiOS
 * `CXCallObserver` بلا صلاحيات نظام — ضمن حياة التطبيق فقط، خطة سطر 550).
 */
interface ArhamCallStateObserver {

    /**
     * يبدأ المراقبة — **idempotent**: الاستدعاءات المتكررة بلا أثر. بلا تسجيلٍ ممكن
     * (صلاحية مرفوضة/رفض منصّي) ⇒ لا عملية إطلاقاً، بسجلٍّ بنوعٍ حصراً — بلا انهيار.
     */
    fun start(scope: CoroutineScope)

    /** يوقف المراقبة ويفكّ كل التسجيلات (idempotent — آمن قبل `start`). */
    fun stop()
}
