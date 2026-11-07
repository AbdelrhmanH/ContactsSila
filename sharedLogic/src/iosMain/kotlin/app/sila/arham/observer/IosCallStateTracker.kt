package app.sila.arham.observer

import app.sila.feature.arham.domain.ObservedCallEnd

/**
 * متتبع مكالمات iOS النقي (T8-12 · خطة سطر 550 — مرآة [ObservedCallState] المشتركة في
 * قواعد الدورة، لكل مكالمة `CXCall` على حدة لأن iOS يبلّغ لكل مكالمة منفصلة): يستقبل
 * بدئيات `CXCall` الصارمة فقط — هوية المكالمة الشفافية (`uuid` معرّف عشوائي للجلسة
 * لا يُخترَق كذلك قط)، الاتجاه، هل اتصلت، هل انتهت — **ولا رقم ولا جهة ولا محتوى يمرّ
 * هنا قط** (AC-17 حالة فقط).
 *
 * قواعد الدورة لكل معرّف:
 * - الاتصال الأول `hasConnected` يثبّت لحظة الرفع (الأول يكفي — التكرار يُهمل).
 * - الانتهاء يُخرج نهايةً للصادر المتصل حصراً: الوارد يُستبعد من الربط بنيوياً (اتجاهُه
 *   منصّيّ حقيقي — ربطُ نهايته بمحاولة صادرةٍ مفتوحة اختلاقٌ محتمل، وبديلُه أندرويد
 *   قاعدةُ «رنينٍ متدخل» لا ينطبق هنا لغياب إشارة الرنين في `CXCallObserver`)، والصادر
 *   غير المتصل (رفض/لم يُجب) بلا رفع سماعة — لا نهاية ولا مدة مُختلقة.
 * - المدة من لحظتي الاتصال والانتهاء المعروفتين حصراً — وإلا لا نهاية أصلاً (مرآة قاعدة
 *   «المدة عند إشارة معلومة فقط» في الخطة).
 * - كل معرّف يُنسى بعد انتهائه مهما كانت دورته (لا احتفاظ بمكالمات منتهية — لا تراكم).
 */
class IosCallStateTracker {

    /** حالة مكالمة قيد الدورة — بلا أي هوية، وتُنسى بعد الانتهاء دوماً. */
    private data class OpenCall(val outgoing: Boolean, val connectedAtMillis: Long?)

    private val open = HashMap<String, OpenCall>()

    /**
     * يبلّغ ببدئيات مكالمة كما أبلغت `CXCallObserver`؛ يعيد [ObservedCallEnd] لانتهاء
     * صادرٍ متصل عند اللحظة المعروفة، و`null` في كل غير ذلك (بلا كتابة).
     */
    fun onCallReported(
        callId: String,
        outgoing: Boolean,
        hasConnected: Boolean,
        hasEnded: Boolean,
        nowMillis: Long,
    ): ObservedCallEnd? {
        // الانتهاء أولاً: كل مسار انتهاء ينسى المعرّف — لا دورة منتهية تبقى في الحامل.
        if (hasEnded) {
            val current = open.remove(callId) ?: return null // بلا دورة مفتوحة — لا اختلاق
            // لحظة الرفع: المحفوظة، أو أول بلاغ متصل منتهٍ (تلاحقت البلاغات بين التسليمات).
            val connectedAt = current.connectedAtMillis
                ?: if (hasConnected) nowMillis else return null
            // الصادر المتصل حصراً، والانتقال موجب حصراً (لا مدد صفرية/سالبة مُختلقة).
            if (!current.outgoing || nowMillis <= connectedAt) return null
            return ObservedCallEnd(
                offhookAtMillis = connectedAt,
                idleAtMillis = nowMillis,
                ringingAtMillis = null, // لا إشارة رنين في CXCallObserver — تُبقى مجهولةً بأمانة
            )
        }

        val current = open[callId]
        val connectedAt = when {
            current != null -> current.connectedAtMillis ?: if (hasConnected) nowMillis else null
            // أول بلاغ لمكالمة مفتوحة: متصلة الآن ⇒ لحظة الرفع الآن، وإلا بلا رفع بعد.
            hasConnected -> nowMillis
            else -> null
        }
        open[callId] = OpenCall(outgoing = outgoing, connectedAtMillis = connectedAt)
        return null
    }

    /** يصفّي الدورات المفتوحة — مسارُ `stop` وقواعد النظافة (لا تسريب حالة بين دورات). */
    fun clear() = open.clear()
}
