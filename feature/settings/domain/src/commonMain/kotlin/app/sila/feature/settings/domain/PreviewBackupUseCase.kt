package app.sila.feature.settings.domain

import app.sila.core.common.AppDispatchers
import app.sila.core.common.DefaultAppDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * حالة استخدام فك تشفير ومعاينة بيانات النسخة الاحتياطية قبل الاستعادة (PRD §12).
 * تضمن المعالجة على مسار خلفي (Dispatchers.Default) وإعادة رمي استثناء الإلغاء.
 */
class PreviewBackupUseCase(
    private val cryptoEngine: BackupCryptoEngine,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val dispatchers: AppDispatchers = DefaultAppDispatchers()
) {
    suspend operator fun invoke(cipherBytes: ByteArray, password: String): BackupPreview = withContext(dispatchers.default) {
        require(password.isNotBlank()) { "Password must not be blank" }
        val plainBytes = cryptoEngine.decrypt(cipherBytes, password)
        val payload = try {
            json.decodeFromString(BackupPayload.serializer(), plainBytes.decodeToString())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw BackupDecryptionException("Failed to parse decrypted backup payload", e)
        }
        BackupPreview(
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
