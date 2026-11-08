package app.sila.feature.settings.domain

import kotlinx.serialization.json.Json

/**
 * حالة استخدام فك تشفير ومعاينة بيانات النسخة الاحتياطية قبل الاستعادة (PRD §12).
 */
class PreviewBackupUseCase(
    private val cryptoEngine: BackupCryptoEngine,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    operator fun invoke(cipherBytes: ByteArray, password: String): BackupPreview {
        require(password.isNotBlank()) { "Password must not be blank" }
        val plainBytes = cryptoEngine.decrypt(cipherBytes, password)
        val payload = try {
            json.decodeFromString(BackupPayload.serializer(), plainBytes.decodeToString())
        } catch (t: Throwable) {
            throw BackupDecryptionException("Failed to parse decrypted backup payload", t)
        }
        return BackupPreview(
            version = payload.version,
            exportedAt = payload.exportedAt,
            appVersion = payload.appVersion,
            categoriesCount = payload.categories.size,
            arhamCount = payload.arham.size,
            tagsCount = payload.tags.size,
            hasPreferences = payload.preferences != null,
            categoryRingtonesCount = payload.categoryRingtones.size,
            automationsCount = payload.automations.size,
            disputesCount = payload.disputes.size
        )
    }
}
