package app.sila.feature.settings.data.crypto

import app.sila.feature.settings.domain.BackupDecryptionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AesGcmCryptoTest {

    @Test
    fun encryptDecrypt_roundTrip_succeeds() {
        val payload = "Hello ContactsSila encrypted backup!".encodeToByteArray()
        val password = "StrongPassword123"

        val cipher = AesGcmCrypto.encrypt(payload, password)
        assertEquals(payload.size + AesGcmCrypto.MIN_PAYLOAD_SIZE_BYTES, cipher.size)

        val decrypted = AesGcmCrypto.decrypt(cipher, password)
        assertEquals(payload.decodeToString(), decrypted.decodeToString())
    }

    @Test
    fun decrypt_withWrongPassword_failsAuthenticationTag() {
        val payload = "Confidential kinship and category data".encodeToByteArray()
        val cipher = AesGcmCrypto.encrypt(payload, "RightPassword")

        assertFailsWith<BackupDecryptionException> {
            AesGcmCrypto.decrypt(cipher, "WrongPassword")
        }
    }

    @Test
    fun decrypt_tamperedCiphertext_detectsTampering() {
        val payload = "Untampered data".encodeToByteArray()
        val cipher = AesGcmCrypto.encrypt(payload, "Password123")

        // التلاعب بآخر بايت (علامة المصادقة Tag)
        val tampered = cipher.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()

        assertFailsWith<BackupDecryptionException> {
            AesGcmCrypto.decrypt(tampered, "Password123")
        }
    }

    @Test
    fun decrypt_missingMagicHeader_throwsException() {
        val payload = "Data".encodeToByteArray()
        val cipher = AesGcmCrypto.encrypt(payload, "Password123")

        val corruptedHeader = cipher.copyOf()
        corruptedHeader[0] = 'X'.code.toByte()

        assertFailsWith<BackupDecryptionException> {
            AesGcmCrypto.decrypt(corruptedHeader, "Password123")
        }
    }

    @Test
    fun decrypt_truncatedPayload_throwsException() {
        val truncated = byteArrayOf(0x53, 0x49, 0x4C, 0x41, 0x01)
        assertFailsWith<BackupDecryptionException> {
            AesGcmCrypto.decrypt(truncated, "Password123")
        }
    }
}
