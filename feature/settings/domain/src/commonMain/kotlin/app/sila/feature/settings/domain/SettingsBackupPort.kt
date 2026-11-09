package app.sila.feature.settings.domain

import app.sila.core.common.AppDispatchers
import app.sila.core.model.Contact

/**
 * بوابة نطاق ضيقة لعمليات النسخ الاحتياطي والاستعادة والربط اليدوي (تذكرة #66 · ADR-0014)
 * — الواجهة الوحيدة التي يعتمدها [app.sila.feature.settings.presentation.SettingsViewModel]
 * لهذا المحور: تصدير/معاينة/استعادة عبر حالات استخدام التشفير (راجع `ExportBackupUseCase`
 * و`PreviewBackupUseCase` و`RestoreBackupUseCase`)، وبحث مرشّحي الربط وربطه عبر
 * `SearchLinkCandidatesUseCase` و`LinkUnmatchedEntryUseCase` — مسار واحد حصراً، فغياب
 * محرك التشفير يُعلَم استثناءً من التنفيذ نفسه لا بمقارنات قابلية-الصدف في الـ ViewModel.
 */
interface SettingsBackupPort {
    /** تصدير نسخة مشفرة ببايتاتها الخام (رفع [SettingsEffect.BackupExported] مسؤولية الواجهة). */
    suspend fun exportBackup(password: String): ByteArray

    /** فك تشفير ومعاينة محتويات النسخة (عدّادات التصنيفات/الأرحام/الوسوم). */
    suspend fun previewBackup(cipherBytes: ByteArray, password: String): BackupPreview

    /** تنفيذ الاستعادة بوضعها وخيار تفضيلاتها + سجلاتها غير المطابقة. */
    suspend fun restoreBackup(
        cipherBytes: ByteArray,
        password: String,
        mode: RestoreMode,
        restorePreferences: Boolean
    ): RestoreResult

    /** بحث مرشّحي الربط اليدوي — أقل من حرفين لا يستهلك بحثاً (قاعدة `SearchLinkCandidatesUseCase`). */
    suspend fun searchLinkCandidates(query: String): List<Contact>

    /** ربط سجل غير مطابق بجهة هدف — `false` فشل معلوم لا صمت. */
    suspend fun linkUnmatchedEntry(entry: UnmatchedBackupEntry, targetContactId: Long): Boolean
}

/**
 * تنفيذ البوابة: يجمّع حالات استخدام النسخ الاحتياطي فوق المستودع ومحرك التشفير.
 * غياب محرك التشفير يجعل التصدير/المعاينة/الاستعادة غير مهيأة — تُرمى [IllegalStateException]
 * بنفس رسائل السلوك السابق (تتحول في الـ ViewModel إلى [BackupErrorKind] عبر `asBackupErrorKind`).
 */
class SettingsBackupPortImpl(
    private val repository: SettingsRepository,
    private val cryptoEngine: BackupCryptoEngine?,
    private val dispatchers: AppDispatchers
) : SettingsBackupPort {
    private val exportBackupUseCase = cryptoEngine?.let { ExportBackupUseCase(repository, it, dispatchers = dispatchers) }
    private val previewBackupUseCase = cryptoEngine?.let { PreviewBackupUseCase(it, dispatchers = dispatchers) }
    private val restoreBackupUseCase =
        cryptoEngine?.let { RestoreBackupUseCase(repository, it, dispatchers = dispatchers) }
    private val searchLinkCandidatesUseCase = SearchLinkCandidatesUseCase(repository)
    private val linkUnmatchedEntryUseCase = LinkUnmatchedEntryUseCase(repository)

    override suspend fun exportBackup(password: String): ByteArray =
        exportBackupUseCase?.invoke(password)
            ?: throw IllegalStateException("Backup export not configured")

    override suspend fun previewBackup(cipherBytes: ByteArray, password: String): BackupPreview =
        previewBackupUseCase?.invoke(cipherBytes, password)
            ?: throw IllegalStateException("Backup preview not configured")

    override suspend fun restoreBackup(
        cipherBytes: ByteArray,
        password: String,
        mode: RestoreMode,
        restorePreferences: Boolean
    ): RestoreResult = restoreBackupUseCase?.invoke(
        cipherBytes = cipherBytes,
        password = password,
        mode = mode,
        restorePreferences = restorePreferences
    ) ?: throw IllegalStateException("Backup restore not configured")

    override suspend fun searchLinkCandidates(query: String): List<Contact> =
        searchLinkCandidatesUseCase(query)

    override suspend fun linkUnmatchedEntry(entry: UnmatchedBackupEntry, targetContactId: Long): Boolean =
        linkUnmatchedEntryUseCase(entry, targetContactId)
}
