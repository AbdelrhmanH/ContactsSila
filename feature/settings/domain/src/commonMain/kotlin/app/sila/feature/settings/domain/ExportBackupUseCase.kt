package app.sila.feature.settings.domain

import kotlinx.serialization.json.Json

/**
 * حالة استخدام تصدير نسخة احتياطية مشفرة بكلمة مرور (PRD §12).
 */
class ExportBackupUseCase(
    private val repository: SettingsRepository,
    private val cryptoEngine: BackupCryptoEngine,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend operator fun invoke(password: String): ByteArray {
        require(password.isNotBlank()) { "Password must not be blank" }
        val payload = repository.getBackupData()
        val jsonString = json.encodeToString(BackupPayload.serializer(), payload)
        return cryptoEngine.encrypt(jsonString.encodeToByteArray(), password)
    }
}
