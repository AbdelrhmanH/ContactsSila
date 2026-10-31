package app.sila.feature.contacts.domain.onboarding

import app.sila.core.model.AppLanguage
import app.sila.core.model.TopFiveSelection

/**
 * حالة التهيئة الأولى الكاملة كما تقرأها طبقة الواجهة
 * (المصدر الوحيد: DataStore عبر [OnboardingRepository]).
 */
data class OnboardingState(
    val isCompleted: Boolean = false,
    val isGuest: Boolean = true,
    /** الخطوة الأولى غير المكتملة التي تستأنف منها، أو null بعد اكتمال التهيئة. */
    val resumeStep: OnboardingStep? = OnboardingStep.WELCOME,
    val contactsPermission: OnboardingPermissionState = OnboardingPermissionState.NOT_DETERMINED,
    val topFiveSelections: List<TopFiveSelection> = emptyList(),
    val language: AppLanguage = AppLanguage.ARABIC
)
