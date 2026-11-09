package app.sila.feature.settings.domain

/**
 * حالة استخدام أحادية المسؤولية: وسوم الاهتمامات المستخدمة في التصفية والاقتراحات (SRP).
 */
class SetInterestTagsUseCase(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(tags: Set<String>) = repository.setInterestTags(tags)
}
