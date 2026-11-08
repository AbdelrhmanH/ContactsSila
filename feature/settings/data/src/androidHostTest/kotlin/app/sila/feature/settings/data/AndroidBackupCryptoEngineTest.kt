package app.sila.feature.settings.data

import app.sila.feature.settings.domain.BackupDecryptionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidBackupCryptoEngineTest {

    private val engine = AndroidBackupCryptoEngine()

    @Test
    fun roundTrip_encryptAndDecrypt_restoresOriginalContent() {
        val originalText = "بيانات صلة الاحتياطية المشفرة — فحص العربية والإنجليزية 123456"
        val originalBytes = originalText.encodeToByteArray()
        val password = "SuperSecretPassword#2026!"

        val encrypted = engine.encrypt(originalBytes, password)
        assertTrue(encrypted.size > originalBytes.size, "Encrypted payload must contain header, salt, IV, and GCM tag")

        val decrypted = engine.decrypt(encrypted, password)
        assertEquals(originalText, decrypted.decodeToString())
    }

    @Test
    fun roundTrip_largePayload_restoresOriginalBytes() {
        // اختبار حمولة كبيرة (100KB) تحاكي آلاف التصنيفات والأقارب
        val largeBytes = ByteArray(100 * 1024) { (it % 256).toByte() }
        val password = "StrongPassword987"

        val encrypted = engine.encrypt(largeBytes, password)
        val decrypted = engine.decrypt(encrypted, password)

        assertTrue(largeBytes.contentEquals(decrypted), "Large payload roundtrip must match byte-for-byte")
    }

    @Test
    fun decrypt_withIncorrectPassword_throwsBackupDecryptionException() {
        val plainBytes = "Hello Sila".encodeToByteArray()
        val encrypted = engine.encrypt(plainBytes, "CorrectPassword123")

        assertFailsWith<BackupDecryptionException> {
            engine.decrypt(encrypted, "WrongPassword456")
        }
    }

    @Test
    fun decrypt_withCorruptedCiphertext_throwsBackupDecryptionException() {
        val plainBytes = "Hello Sila Tamper Test".encodeToByteArray()
        val encrypted = engine.encrypt(plainBytes, "Password123")

        // التلاعب ببت واحد في نهاية التشفير (مكان علامة المصادقة GCM Tag)
        val corrupted = encrypted.copyOf()
        corrupted[corrupted.lastIndex] = (corrupted[corrupted.lastIndex].toInt() xor 0xFF).toByte()

        assertFailsWith<BackupDecryptionException> {
            engine.decrypt(corrupted, "Password123")
        }
    }

    @Test
    fun decrypt_withInvalidMagicHeader_throwsBackupDecryptionException() {
        val plainBytes = "Magic Header Test".encodeToByteArray()
        val encrypted = engine.encrypt(plainBytes, "Password123")

        val corrupted = encrypted.copyOf()
        corrupted[0] = 'X'.code.toByte()

        val ex = assertFailsWith<BackupDecryptionException> {
            engine.decrypt(corrupted, "Password123")
        }
        assertTrue(ex.message?.contains("magic header mismatch") == true)
    }

    @Test
    fun decrypt_withTruncatedPayload_throwsBackupDecryptionException() {
        val shortBytes = ByteArray(10) { 0 }
        assertFailsWith<BackupDecryptionException> {
            engine.decrypt(shortBytes, "Password123")
        }
    }

    @Test
    fun emptyPassword_throwsIllegalArgumentException() {
        val bytes = "Test".encodeToByteArray()
        assertFailsWith<IllegalArgumentException> {
            engine.encrypt(bytes, "")
        }
        assertFailsWith<IllegalArgumentException> {
            engine.decrypt(bytes, "")
        }
    }

    @Test
    fun crossEngineInteroperability_androidEncryptAesGcmDecrypt_andViceVersa() {
        val originalText = "Cross-Engine Interoperability Test 2026"
        val originalBytes = originalText.encodeToByteArray()
        val password = "CrossPlatformPassword_123"

        // 1. Android encrypt -> AesGcmCrypto decrypt
        val androidEncrypted = engine.encrypt(originalBytes, password)
        val aesGcmDecrypted = app.sila.feature.settings.data.crypto.AesGcmCrypto.decrypt(androidEncrypted, password)
        assertEquals(originalText, aesGcmDecrypted.decodeToString())

        // 2. AesGcmCrypto encrypt -> Android decrypt
        val aesGcmEncrypted = app.sila.feature.settings.data.crypto.AesGcmCrypto.encrypt(originalBytes, password)
        val androidDecrypted = engine.decrypt(aesGcmEncrypted, password)
        assertEquals(originalText, androidDecrypted.decodeToString())
    }
}
