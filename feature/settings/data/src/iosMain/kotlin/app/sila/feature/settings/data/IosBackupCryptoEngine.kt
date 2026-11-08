package app.sila.feature.settings.data

import app.sila.feature.settings.data.crypto.AesGcmCrypto
import app.sila.feature.settings.domain.BackupCryptoEngine

/**
 * محرك تشفير واسترجاع النسخ الاحتياطية على iOS (T9 — Issue #30).
 * يطبق معيار AES-256-GCM مع PBKDF2WithHmacSHA256 بتوافق تام 100% مع أندرويد.
 */
class IosBackupCryptoEngine : BackupCryptoEngine {
    override fun encrypt(plainBytes: ByteArray, password: String): ByteArray {
        return AesGcmCrypto.encrypt(plainBytes, password)
    }

    override fun decrypt(cipherBytes: ByteArray, password: String): ByteArray {
        return AesGcmCrypto.decrypt(cipherBytes, password)
    }
}
