package app.sila.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreCommonTests {

    @Test
    fun arabicTextNormalizer_normalizesAlefTaaMarbutaAndDiacritics() {
        val raw = "أَحْمَد إِبْرَاهِيم فاطمة سلمى ١٢٣"
        val normalized = ArabicTextNormalizer.normalize(raw)
        assertEquals("احمد ابراهيم فاطمه سلمي 123", normalized)
    }

    @Test
    fun arabicTextNormalizer_collapsesExtraWhitespaceAndTatweel() {
        val raw = "  عَبْدُ   الرَّحْمٰنـــ   "
        val normalized = ArabicTextNormalizer.normalize(raw)
        assertEquals("عبد الرحمن", normalized)
    }

    @Test
    fun phoneNormalizer_convertsLocalEgyptianPhoneToE164() {
        assertEquals("+201012345678", PhoneNormalizer.normalizeToE164("010 1234-5678"))
        assertEquals("+201012345678", PhoneNormalizer.normalizeToE164("00201012345678"))
        assertEquals("+201012345678", PhoneNormalizer.normalizeToE164("+20 (10) 1234 5678"))
        assertEquals("+201012345678", PhoneNormalizer.normalizeToE164("٠١٠١٢٣٤٥٦٧٨"))
    }

    @Test
    fun phoneNormalizer_preservesShortCodesAndEmergencyNumbers() {
        assertEquals("123", PhoneNormalizer.normalizeToE164("123"))
        assertEquals("19011", PhoneNormalizer.normalizeToE164("19011"))
        assertTrue(PhoneNormalizer.isShortCodeOrHotline("123"))
        assertTrue(PhoneNormalizer.isShortCodeOrHotline("19999"))
        assertFalse(PhoneNormalizer.isShortCodeOrHotline("01012345678"))
    }

    @Test
    fun redactor_scrubsPhoneNumbersAndEmailsFromLogs() {
        val rawLog = "Sync failed for user abdel@example.com with phone +201012345678"
        val scrubbed = Redactor.scrub(rawLog)
        assertFalse(scrubbed.contains("abdel@example.com"))
        assertFalse(scrubbed.contains("201012345678"))
        assertTrue(scrubbed.contains("[REDACTED_EMAIL]"))
        assertTrue(scrubbed.contains("[REDACTED_PHONE]"))
    }

    @Test
    fun appResult_mapsSuccessAndPreservesError() {
        val success: AppResult<Int> = AppResult.Success(10)
        val mapped = success.map { it * 2 }
        assertEquals(20, mapped.getOrNull())

        val error: AppResult<Int> = AppResult.Error(DataError.NotFound("c_1"))
        val mappedError = error.map { it * 2 }
        assertNull(mappedError.getOrNull())
    }
}
