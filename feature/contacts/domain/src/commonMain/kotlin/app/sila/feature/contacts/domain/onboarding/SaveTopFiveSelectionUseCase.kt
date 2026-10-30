package app.sila.feature.contacts.domain.onboarding

import app.sila.core.model.TopFiveSelection

/**
 * حالة استخدام أحادية المسؤولية: حفظ اختيارات "أهم 5 أشخاص" (SRP).
 * حفظها هنا هو **عقد التسليم** إلى ميزة أرحام (T8) التي ستنشئ السجلات الفعلية
 * `FAMILY` + وتيرة أسبوعية بعد توفر جهات الاتصال (T2) — انظر OnboardingRepository.
 */
class SaveTopFiveSelectionUseCase(
    private val repository: OnboardingRepository
) {
    suspend operator fun invoke(selections: List<TopFiveSelection>) = repository.saveTopFiveSelections(selections)
}
