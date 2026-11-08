package app.sila.feature.settings.data

import app.sila.feature.settings.domain.BackupCryptoEngine
import app.sila.feature.settings.domain.BackupDecryptionException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * محرك تشفير وفك تشفير النسخ الاحتياطية على نظام أندرويد/JVM (PRD §12).
 * يعتمد على معايير التشفير الصارمة:
 * - مفتاح AES-256 مشتق عبر PBKDF2WithHmacSHA256 مع 100,000 تكرار وملح 16 بايت عشوائي.
 * - تشفير موثّق AES-256-GCM مع IV عشوائي 12 بايت وعلامة مصادقة (Tag) بطول 128 بت.
 * - تطهير صارم لبيانات الاعتماد من الذاكرة (Zeroization).
 */
class AndroidBackupCryptoEngine : BackupCryptoEngine {

    private val secureRandom = SecureRandom()

    override fun encrypt(plainBytes: ByteArray, password: String): ByteArray {
        require(password.isNotEmpty()) { "Password must not be empty" }

        val salt = ByteArray(SALT_SIZE_BYTES).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_SIZE_BYTES).also { secureRandom.nextBytes(it) }

        val keySpec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val keyBytes = factory.generateSecret(keySpec).encoded
        keySpec.clearPassword()
        val secretKey = SecretKeySpec(keyBytes, "AES")

        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))
            val cipherTextWithTag = cipher.doFinal(plainBytes)

            val output = ByteArray(HEADER_SIZE_BYTES + cipherTextWithTag.size)
            // 1) Magic header (4 bytes)
            MAGIC_BYTES.copyInto(output, destinationOffset = 0)
            // 2) Version (1 byte)
            output[4] = FORMAT_VERSION
            // 3) Salt (16 bytes)
            salt.copyInto(output, destinationOffset = 5)
            // 4) IV (12 bytes)
            iv.copyInto(output, destinationOffset = 21)
            // 5) Ciphertext + Tag
            cipherTextWithTag.copyInto(output, destinationOffset = HEADER_SIZE_BYTES)

            output
        } finally {
            keyBytes.fill(0)
        }
    }

    override fun decrypt(cipherBytes: ByteArray, password: String): ByteArray {
        require(password.isNotEmpty()) { "Password must not be empty" }

        if (cipherBytes.size < MIN_PAYLOAD_SIZE_BYTES) {
            throw BackupDecryptionException("Corrupted backup: payload is too short (${cipherBytes.size} bytes)")
        }

        // 1) Verify magic bytes
        for (i in MAGIC_BYTES.indices) {
            if (cipherBytes[i] != MAGIC_BYTES[i]) {
                throw BackupDecryptionException("Invalid backup format: magic header mismatch")
            }
        }

        // 2) Verify format version
        val version = cipherBytes[4]
        if (version != FORMAT_VERSION) {
            throw BackupDecryptionException("Unsupported backup format version: $version")
        }

        // 3) Extract salt and IV
        val salt = ByteArray(SALT_SIZE_BYTES)
        cipherBytes.copyInto(salt, destinationOffset = 0, startIndex = 5, endIndex = 21)

        val iv = ByteArray(IV_SIZE_BYTES)
        cipherBytes.copyInto(iv, destinationOffset = 0, startIndex = 21, endIndex = HEADER_SIZE_BYTES)

        // 4) Extract ciphertext + tag
        val cipherTextWithTag = ByteArray(cipherBytes.size - HEADER_SIZE_BYTES)
        cipherBytes.copyInto(cipherTextWithTag, destinationOffset = 0, startIndex = HEADER_SIZE_BYTES, endIndex = cipherBytes.size)

        // 5) Derive AES key
        val keySpec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val keyBytes = factory.generateSecret(keySpec).encoded
        keySpec.clearPassword()
        val secretKey = SecretKeySpec(keyBytes, "AES")

        // 6) Decrypt and authenticate
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))
            cipher.doFinal(cipherTextWithTag)
        } catch (t: Throwable) {
            throw BackupDecryptionException("Decryption failed: incorrect password or corrupted data", cause = t)
        } finally {
            keyBytes.fill(0)
        }
    }

    companion object {
        private val MAGIC_BYTES = byteArrayOf(0x53, 0x49, 0x4C, 0x41) // "SILA"
        private const val FORMAT_VERSION: Byte = 1
        private const val SALT_SIZE_BYTES = 16
        private const val IV_SIZE_BYTES = 12
        private const val TAG_LENGTH_BITS = 128
        private const val HEADER_SIZE_BYTES = 4 + 1 + SALT_SIZE_BYTES + IV_SIZE_BYTES // 33 bytes
        private const val MIN_PAYLOAD_SIZE_BYTES = HEADER_SIZE_BYTES + (TAG_LENGTH_BITS / 8) // 49 bytes

        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val PBKDF2_ITERATIONS = 100_000
        private const val KEY_LENGTH_BITS = 256
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
