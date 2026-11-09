package app.sila.feature.settings.domain

/**
 * حالة استخدام أحادية المسؤولية: تحديد الحسابات الظاهرة في التطبيق (SRP).
 */
class SetVisibleAccountsUseCase(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(accounts: Set<String>) = repository.setVisibleAccounts(accounts)
}
