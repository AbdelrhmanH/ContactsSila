package app.sila.feature.contacts.domain.onboarding

/**
 * خطوات التهيئة الأولى للمرحلة 1 (ضيف فقط) — تحدد ترتيب الخطوة المحفوظة في
 * مستودع التهيئة (`resumeStep`) بحيث يستأنف المستخدم عند أول خطوة غير مكتملة.
 *
 * لا توجد خطوة OTP في المرحلة 1: خطوة القيس تكون شاشة "تخطي الآن" (GUEST_LOGIN).
 */
enum class OnboardingStep {
    WELCOME,
    PERMISSION,
    GUEST_LOGIN,
    TOP_FIVE,
    ORGANIZING
}

/** الخطوة التالية في الترتيب، أو null إذا كانت هذه الأخيرة (ORGANIZING يعني الاكتمال التام). */
fun OnboardingStep.nextStep(): OnboardingStep? =
    OnboardingStep.entries.getOrNull(ordinal + 1)
