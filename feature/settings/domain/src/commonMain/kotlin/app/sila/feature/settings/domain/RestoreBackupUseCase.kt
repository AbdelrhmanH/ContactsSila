package app.sila.feature.settings.domain

import app.sila.core.common.AppDispatchers
import app.sila.core.common.DefaultAppDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * حالة استخدام استعادة بيانات النسخة الاحتياطية المشفرة (PRD §12).
 * تضمن المعالجة على مسار خلفي (Dispatchers.Default) وإعادة رمي استثناء الإلغاء.
 */
class RestoreBackupUseCase(
    private val repository: SettingsRepository,
    private val cryptoEngine: BackupCryptoEngine,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val dispatchers: AppDispatchers = DefaultAppDispatchers()
) {
    suspend operator fun invoke(
        cipherBytes: ByteArray,
        password: String,
        mode: RestoreMode = RestoreMode.MERGE,
        restorePreferences: Boolean = false
    ): RestoreResult = withContext(dispatchers.default) {
        require(password.isNotBlank()) { "Password must not be blank" }
        val plainBytes = cryptoEngine.decrypt(cipherBytes, password)
        val payload = try {
            json.decodeFromString(BackupPayload.serializer(), plainBytes.decodeToString())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw BackupDecryptionException("Failed to parse decrypted backup payload", e)
        }
        repository.restoreBackupData(
            payload = payload,
            mode = mode,
            restorePreferences = restorePreferences
        )
    }
}
