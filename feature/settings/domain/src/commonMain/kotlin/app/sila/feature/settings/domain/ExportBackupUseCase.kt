package app.sila.feature.settings.domain

import app.sila.core.common.AppDispatchers
import app.sila.core.common.DefaultAppDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * حالة استخدام تصدير نسخة احتياطية مشفرة بكلمة مرور (PRD §12).
 * تضمن المعالجة على مسار خلفي (Dispatchers.Default) لمنع حجب مسار الواجهة.
 */
class ExportBackupUseCase(
    private val repository: SettingsRepository,
    private val cryptoEngine: BackupCryptoEngine,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val dispatchers: AppDispatchers = DefaultAppDispatchers()
) {
    suspend operator fun invoke(password: String): ByteArray = withContext(dispatchers.default) {
        require(password.isNotBlank()) { "Password must not be blank" }
        val payload = repository.getBackupData()
        val jsonString = json.encodeToString(BackupPayload.serializer(), payload)
        cryptoEngine.encrypt(jsonString.encodeToByteArray(), password)
    }
}
