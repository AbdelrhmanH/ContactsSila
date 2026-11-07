package app.sila.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class AppThemeMode {
    LIGHT,
    DARK,
    SYSTEM
}

@Serializable
enum class AppLanguage(val code: String, val isRtl: Boolean) {
    ARABIC("ar", true),
    ENGLISH("en", false)
}

@Serializable
enum class DefaultTab {
    CONTACTS,
    SERVICES,
    ARHAM
}

/**
 * أفعال السحب في قائمة جهات الاتصال (Issue #24 — "يمين=اتصال، يسار=رسالة، قابل للتغيير").
 * دلالة البداية/النهاية منطقية (Start/End) فينعكس الاتجاه فعلياً في RTL تلقائياً.
 */
@Serializable
enum class ContactSwipeAction {
    CALL,
    MESSAGE
}

@Serializable
data class UserPreferences(
    val themeMode: AppThemeMode = AppThemeMode.LIGHT,
    val language: AppLanguage = AppLanguage.ARABIC,
    val defaultTab: DefaultTab = DefaultTab.CONTACTS,
    val arhamNotificationsEnabled: Boolean = true,
    val quietHoursStartHour: Int = 23,
    val quietHoursEndHour: Int = 8,
    /** ساعة التذكير اليومي المفضلة بالتوقيت المحلي (§M «الوقت المفضل» — T8-08؛ الافتراضي 20:00). */
    val arhamNotificationHour: Int = 20,
    /** دقيقة التذكير اليومي المفضلة بالتوقيت المحلي (§M «الوقت المفضل» — T8-08). */
    val arhamNotificationMinute: Int = 0,
    /** تنبيه اليوم السابق للمناسبة (§M «اختيارياً» — T8-08 يمرِّر الراية؛ الافتراضي معطل). */
    val arhamOccasionDayBeforeEnabled: Boolean = false,
    val confettiEnabled: Boolean = true,
    val searchRadiusKm: Int = 5,
    val autoUpdatePhonebookServices: Boolean = false,
    val smartFilterEnabled: Boolean = true,
    val onboardingCompleted: Boolean = false,
    /** المرحلة 1 ضيف فقط (لا حساب ولا OTP) — Issue #26 / #20. */
    val isGuest: Boolean = true,
    /** الخطوة التي سيعود إليها المستخدم عند إعادة الدخول (أول خطوة غير مكتملة). */
    val onboardingResumeStep: String? = null,
    /** آخر نتيجة صلاحية جهات اتصال أبلغتها المنصة أثناء التهيئة (اسم حالة). */
    val onboardingContactsPermission: String? = null,
    /** اختيارات "أهم 5 أشخاص" — عقد التسليم إلى T8 (انظر TopFiveSelection). */
    val onboardingTopFiveSelections: List<TopFiveSelection> = emptyList(),
    /**
     * علامة إنجاز التمويد الواحد (T8-04 · §F.10): تُرفع فقط بعد نجاح معاملة Room الأولى
     * لتجسيد هذه الاختيارات، ولا تُعاد فتحها أبداً (Issue #29 — one-shot). إعادة الاختيار
     * أثناء التهيئة تكتب القائمة المعلّقة حصراً ولا تُصفّر هذه العلامة (§F.6/§F.10).
     */
    val topFiveMaterialized: Boolean = false,
    /**
     * علامات الاحتفال بانتقالات أرحام (T8-06 · خطة §D.3-5): معرّف جهة الاتصال ← مفتاح
     * فترة الاحتفال (`nowEpochMillis() / 7 أيام`) التي احتُفلت فيها آخر مرة. الاحتفال
     * (كونفيتي) يُطلق مرة واحدة في الفترة لكل جهة عند انتقالها أحمر←أخضر، وتُكتب
     * العلامة **بعد القرار** حصراً كي لا يُعاد الإطلاق عند إعادة تركيب العملية في
     * الفترة نفسها. القرار نفسه في `CelebrateTransitionUseCase` — هذا الحقل تخزينٌ حصراً.
     */
    val celebratedArhamTransitions: Map<Long, Long> = emptyMap(),
    /**
     * معرّفات بطاقات الاقتراح المُستبعدة نهائياً (Issue #24 — "قابلة للإغلاق ولا تعود
     * لنفس العناصر"): المعرّف مشتق من هوية العناصر نفسها، فلا تعود البطاقة إلا
     * إذا تغيّرت مجموعة العناصر.
     */
    val dismissedSuggestionIds: Set<String> = emptySet(),
    /** فعل سحب البداية (يمين في LTR) — الافتراضي اتصال (Issue #24). */
    val swipeStartAction: ContactSwipeAction = ContactSwipeAction.CALL,
    /** فعل سحب النهاية (يسار في LTR) — الافتراضي رسالة (Issue #24). */
    val swipeEndAction: ContactSwipeAction = ContactSwipeAction.MESSAGE
)
