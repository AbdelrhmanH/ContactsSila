package app.sila.feature.settings.data.crypto

import app.sila.feature.settings.domain.BackupDecryptionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * متجهات اختبار معروفة النتيجة (KAT) لمحرك التشفير اليدوي —
 * مرجع الحقيقة:
 * - SHA-256: أمثلة FIPS 180-4 المنشورة («abc» والسلسلة الفارغة).
 * - AES-256 (دور الكتلة الواحدة): النموذج C.3 من FIPS-197 حرفاً.
 * - AES-256-GCM: نظام اختبار McGrew-Viega/NIST (النظام 13 والنظام 14 — من دون بيانات إضافية AAD،
 *   ما يطابق قيود المحرك الحال) + متجه L-60 اصطناعي (60 بايتاً = 3 كتل + 12 جزئية)
 *   مُثبت توافقه بالمقارنة بين مزوّدين مستقلين حكمان (JDK JCA و BouncyCastle 1.85)
 *   وغلاّم .NET Framework `Rfc2898DeriveBytes` لاشتقاق المفاتيح.
 * - PBKDF2-HMAC-SHA256: نظام RFC 7914 §11.1 — أُعيد إنتاجه حرفاً على JDK (PBKDF2WithHmacSHA256)
 *   مع تقاطع نسختي .NET على المتجهات ذات الملح الطويل.
 * قاعدة الفحص: أي انحراف عن هذه القيم يُفشل البناء — لا اعتماد على «ترجمة تمرّ مع ذاتها».
 */
class AesGcmCryptoKatTest {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun toHex(a: ByteArray): String =
        a.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    // ==========================================================
    // SHA-256 (FIPS 180-4)
    // ==========================================================

    @Test
    fun sha256_knownAnswer_abc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            toHex(AesGcmCrypto.sha256("abc".encodeToByteArray())),
        )
    }

    @Test
    fun sha256_knownAnswer_empty() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            toHex(AesGcmCrypto.sha256(ByteArray(0))),
        )
    }

    // ==========================================================
    // AES-256 block role (FIPS-197 C.3)
    // ==========================================================

    @Test
    fun aes256_block_fips197_C3() {
        val key = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val input = hex("00112233445566778899aabbccddeeff")
        val output = ByteArray(16)
        AesGcmCrypto.aesEncryptBlock(input, output, key)
        assertEquals("8ea2b7ca516745bfeafc49904b496089", toHex(output))
    }

    // ==========================================================
    // AES-256-GCM (McGrew-Viega / NIST — AAD خالٍ)
    // ==========================================================

    @Test
    fun gcm_testCase13_emptyPlaintext() {
        val key = hex("0000000000000000000000000000000000000000000000000000000000000000")
        val iv = hex("000000000000000000000000")
        val (ciphertext, tag) = AesGcmCrypto.encryptAes256Gcm(ByteArray(0), key, iv)
        assertEquals(0, ciphertext.size)
        assertEquals("530f8afbc74536b9a963b4f1c4cb738b", toHex(tag))
    }

    @Test
    fun gcm_testCase14_singleBlock() {
        val key = hex("0000000000000000000000000000000000000000000000000000000000000000")
        val iv = hex("000000000000000000000000")
        val (ciphertext, tag) = AesGcmCrypto.encryptAes256Gcm(ByteArray(16), key, iv)
        assertEquals("cea7403d4d606b6e074ec5d3baf39d18", toHex(ciphertext))
        assertEquals("d0d1c8a799996bf0265b98b5d48ab919", toHex(tag))
    }

    /** 60 بايتاً = 3 كتل + 12 جزئياً — يغطي حدود GHASH وCTR خارج مضاعف الـ 16. */
    @Test
    fun gcm_len60_partialBlock() {
        val key = hex("feffe9928665732c6d5946ea71e0510afeffe9928665732c6d5946ea71e0510a")
        val iv = hex("cafebabefacedbaddecaf888")
        val plain = hex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449c6d764") +
            hex("0b0b0b0b0b0b0b0b0b0b0b0b")
        val (ciphertext, tag) = AesGcmCrypto.encryptAes256Gcm(plain, key, iv)
        assertEquals(
            "ff5fab87356d5593ebde2f8c66edd360892a2dea246c6af96caa2cb3f499d1e3e46f7bc563155c1cd2fd77fe54a566e05c44d6a5cf6db290ab05af11",
            toHex(ciphertext),
        )
        assertEquals("8a4d5b083e58403be7c56d5fbcdb4fd5", toHex(tag))
    }

    @Test
    fun gcm_len60_decrypt_restoresPlaintextAndVerifiesTag() {
        val key = hex("feffe9928665732c6d5946ea71e0510afeffe9928665732c6d5946ea71e0510a")
        val iv = hex("cafebabefacedbaddecaf888")
        val plain = hex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449c6d764") +
            hex("0b0b0b0b0b0b0b0b0b0b0b0b")
        val ciphertext = hex(
            "ff5fab87356d5593ebde2f8c66edd360892a2dea246c6af96caa2cb3f499d1e3e46f7bc563155c1cd2fd77fe54a566e05c44d6a5cf6db290ab05af11",
        )
        val tag = hex("8a4d5b083e58403be7c56d5fbcdb4fd5")
        assertEquals(toHex(plain), toHex(AesGcmCrypto.decryptAes256Gcm(ciphertext, tag, key, iv)))
    }

    /** أي انحراف بايت في العلامة ⇒ `BackupDecryptionException` (فحص ثابت الزمن). */
    @Test
    fun gcm_tamperedTag_isRejected() {
        val key = hex("0000000000000000000000000000000000000000000000000000000000000000")
        val iv = hex("000000000000000000000000")
        val ciphertext = hex("cea7403d4d606b6e074ec5d3baf39d18")
        val tampered = hex("d0d1c8a799996bf0265b98b5d48ab918") // آخر بايت مُقلّب (919→918)
        assertFailsWith<BackupDecryptionException> {
            AesGcmCrypto.decryptAes256Gcm(ciphertext, tampered, key, iv)
        }
    }

    // ==========================================================
    // PBKDF2-HMAC-SHA256 (RFC 7914 §11.1 + متجهات شائعة الوثوق)
    // ==========================================================

    @Test
    fun pbkdf2_rfc7914_case1_passwd_salt_c1() {
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            toHex(AesGcmCrypto.pbkdf2HmacSha256("passwd", hex("73616c74"), 1, 64)),
        )
    }

    @Test
    fun pbkdf2_password_salt_c2_32byte() {
        assertEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            toHex(AesGcmCrypto.pbkdf2HmacSha256("password", hex("73616c74"), 2, 32)),
        )
    }

    @Test
    fun pbkdf2_password_salt_c4096_32byte() {
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            toHex(AesGcmCrypto.pbkdf2HmacSha256("password", hex("73616c74"), 4096, 32)),
        )
    }

    @Test
    fun pbkdf2_rfc7914_case3_pleaseletmein_SodiumChloride_c16384() {
        assertEquals(
            "e5d7c03e11ae5b90b6669755fd90930259ec77f7f643b3d71d263304358e528a679f2ef40a2306882becc93d7f3f38f213304971033ad47eb3421d72ad20e77c",
            toHex(AesGcmCrypto.pbkdf2HmacSha256("pleaseletmein", "SodiumChloride".encodeToByteArray(), 16384, 64)),
        )
    }
}
