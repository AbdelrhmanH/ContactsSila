package app.sila.feature.settings.domain

import kotlinx.serialization.json.Json

/**
 * حالة استخدام استعادة بيانات النسخة الاحتياطية المشفرة (PRD §12).
 */
class RestoreBackupUseCase(
    private val repository: SettingsRepository,
    private val cryptoEngine: BackupCryptoEngine,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend operator fun invoke(
        cipherBytes: ByteArray,
        password: String,
        mode: RestoreMode = RestoreMode.MERGE,
        restorePreferences: Boolean = false
    ): RestoreResult {
        require(password.isNotBlank()) { "Password must not be blank" }
        val plainBytes = cryptoEngine.decrypt(cipherBytes, password)
        val payload = try {
            json.decodeFromString(BackupPayload.serializer(), plainBytes.decodeToString())
        } catch (t: Throwable) {
            throw BackupDecryptionException("Failed to parse decrypted backup payload", t)
        }
        return repository.restoreBackupData(
            payload = payload,
            mode = mode,
            restorePreferences = restorePreferences
        )
    }
}
