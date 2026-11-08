package app.sila.feature.settings.domain

/**
 * استثناء فشل فك تشفير النسخة الاحتياطية (كلمة مرور خاطئة أو بيانات تالفة/متلاعب بها).
 */
class BackupDecryptionException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * عقد محرك التشفير المعتمد على كلمة المرور للنسخ الاحتياطية (PRD §12).
 * يشفر بواسطة AES-256-GCM ويشتق المفاتيح عبر PBKDF2 (HMAC-SHA256 >= 100,000 iterations).
 */
interface BackupCryptoEngine {
    /**
     * يشفر [plainBytes] بكلمة المرور [password].
     */
    fun encrypt(plainBytes: ByteArray, password: String): ByteArray

    /**
     * يفك تشفير [cipherBytes] بكلمة المرور [password].
     * يرمي [BackupDecryptionException] عند فشل فك التشفير أو التلاعب.
     */
    fun decrypt(cipherBytes: ByteArray, password: String): ByteArray
}
