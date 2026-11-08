package app.sila.feature.settings.data

import app.sila.feature.settings.domain.BackupCryptoEngine

class IosBackupCryptoEngine : BackupCryptoEngine {
    override fun encrypt(plainBytes: ByteArray, password: String): ByteArray {
        return plainBytes
    }

    override fun decrypt(cipherBytes: ByteArray, password: String): ByteArray {
        return cipherBytes
    }
}
