package app.sila.feature.settings.domain

/**
 * حالة استخدام أحادية المسؤولية: ربط سجل استعادة غير مطابق بجهة مختارة يدوياً (SRP).
 *
 * تفويض صريح لعقد [SettingsRepository.linkUnmatchedEntry] — التصنيف وصلة الرحم تُطبَّقان
 * باستقلالية، ويعيد `false` عند غياب جهة الهدف أو خلوه من تصنيف وصلة معاً (فشل معلوم لا صمت).
 */
class LinkUnmatchedEntryUseCase(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(entry: UnmatchedBackupEntry, targetContactId: Long): Boolean =
        repository.linkUnmatchedEntry(entry, targetContactId)
}
