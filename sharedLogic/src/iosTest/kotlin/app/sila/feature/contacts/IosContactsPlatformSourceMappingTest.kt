package app.sila.feature.contacts

import app.sila.feature.contacts.data.IosContactValues
import app.sila.feature.contacts.data.IosContactsPlatformSource
import app.sila.feature.contacts.data.IosHistoryEvent
import app.sila.feature.contacts.data.IosLabeledValue
import app.sila.feature.contacts.data.IosPhoneValue
import app.sila.feature.contacts.data.assembleIosDelta
import app.sila.feature.contacts.data.mapIosContact
import app.sila.feature.contacts.data.toIosContactValues
import app.sila.feature.contacts.domain.ContactsSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import platform.Contacts.CNLabelHome
import platform.Contacts.CNLabelPhoneNumberMobile
import platform.Contacts.CNLabelWork
import platform.Contacts.CNLabeledValue
import platform.Contacts.CNMutableContact
import platform.Contacts.CNPhoneNumber
import platform.Foundation.NSCopyingProtocol
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create

/**
 * اختبارات تحويل `CNContact` الفعلي ورمز سجل التغييرات لمصدر جهات الاتصال على iOS
 * (Issue #22 · ADR-0009 §4 · ADR-0010 §4) — تُشغَّل على محاكي iOS عبر
 * `:sharedLogic:iosSimulatorArm64Test` حصراً.
 */
class IosContactsPlatformSourceMappingTest {

    private val source = IosContactsPlatformSource()

    // ------------------------------------------------------------------ mapping

    @Test
    fun cnMutableContact_mapsAllSupportedFieldsWithPureMapper() {
        val fixture = CNMutableContact().apply {
            namePrefix = "السيدة"
            givenName = "سارة"
            middleName = "الوسطى"
            familyName = "أحمد"
            nameSuffix = "الشريفية"
            nickname = "سس"
            phoneticGivenName = "سارة صوتياً"
            phoneticFamilyName = "أحمد صوتياً"
            organizationName = "شركة الأفق"
            departmentName = "قسم المحمول"
            jobTitle = "مهندسة برمجيات"
            note = "زميلة من العمل"
            phoneNumbers = listOf(
                CNLabeledValue(
                    label = CNLabelPhoneNumberMobile,
                    value = CNPhoneNumber(stringValue = "+20 100 123 4567")
                ),
                CNLabeledValue(
                    label = CNLabelHome,
                    value = CNPhoneNumber(stringValue = "+20 2 555 0100")
                )
            )
            emailAddresses = listOf(
                CNLabeledValue(
                    label = CNLabelWork,
                    value = NSString.create(string = "sara@example.com") as NSCopyingProtocol
                ),
                CNLabeledValue(
                    label = CNLabelHome,
                    value = NSString.create(string = "sara.home@example.com") as NSCopyingProtocol
                )
            )
            urlAddresses = listOf(
                CNLabeledValue(
                    label = CNLabelWork,
                    value = NSString.create(string = "https://sara.example.com") as NSCopyingProtocol
                )
            )
        }

        val values = fixture.toIosContactValues()

        // التسميات النظامية تُطبَّع إلى lowercase (home/work/other/mobile) قبل المُطبَّع النقي.
        assertEquals("mobile", values.phones[0].label)
        assertEquals("+20 100 123 4567", values.phones[0].raw)
        assertEquals("home", values.phones[1].label)
        assertEquals("work", values.emails[0].label)
        assertEquals("sara@example.com", values.emails[0].value)
        assertEquals("home", values.emails[1].label)
        assertEquals("work", values.urlAddresses[0].label)
        assertEquals("https://sara.example.com", values.urlAddresses[0].value)

        val mapped = mapIosContact(values)

        // الهوية الدائمة من identifier حصراً — لا lookupKey على iOS (ADR-0009 §4).
        assertEquals(values.identifier, mapped.systemContactId)
        assertNull(mapped.lookupKey, "iOS never carries a lookupKey (ADR-0009 §4).")
        assertEquals("سارة أحمد", mapped.name.displayName)
        assertEquals("أحمد سارة", mapped.name.fileAs)
        assertEquals("سارة", mapped.name.firstName)
        assertEquals("أحمد", mapped.name.lastName)
        assertEquals("سارة صوتياً أحمد صوتياً", mapped.name.phoneticName)
        assertEquals(2, mapped.phones.size)
        assertEquals("MOBILE", mapped.phones[0].type)
        assertEquals("HOME", mapped.phones[1].type)
        assertEquals(2, mapped.emails.size)
        assertEquals("WORK", mapped.emails[0].type)
        assertEquals("HOME", mapped.emails[1].type)
        assertNotNull(mapped.organization)
        assertEquals("شركة الأفق", mapped.organization.company)
        assertEquals("مهندسة برمجيات", mapped.organization.jobTitles.single())
        assertEquals(listOf("https://sara.example.com"), mapped.websites)
        assertEquals("زميلة من العمل", mapped.notes)
        assertNull(mapped.account)
    }

    @Test
    fun mapIosContact_fallsBackAndNormalizesLabelsForHandBuiltValues() {
        // لا اسم بشري → عرض من المؤسسة؛ الهوية آخر عتبة.
        val orgOnly = mapIosContact(
            IosContactValues(identifier = "AB12CD34", organizationName = "استوديو التصميم")
        )
        assertEquals("استوديو التصميم", orgOnly.name.displayName)
        assertEquals("AB12CD34", orgOnly.systemContactId)
        // الاسم المؤسسي يبني Organization فعلياً (نفس سلوك المُطبَّع في الاختبار الأول).
        assertEquals("استوديو التصميم", orgOnly.organization?.company)

        // بلا تسمية → النوع الافتراضي للهاتف MOBILE، وللبريد PERSONAL (بلا label ظاهر).
        val noLabel = mapIosContact(
            IosContactValues(identifier = "X1", phones = listOf(IosPhoneValue(raw = "+200000")))
        )
        assertEquals("MOBILE", noLabel.phones.single().type)
        assertNull(noLabel.phones.single().label)

        // تسمية غير معروفة → OTHER مع حفظ التسمية الخام.
        val unknownLabel = mapIosContact(
            IosContactValues(
                identifier = "X2",
                phones = listOf(IosPhoneValue(raw = "+201111", label = "assistant")),
                emails = listOf(IosLabeledValue(label = "assistant", value = "s@example.com"))
            )
        )
        assertEquals("OTHER", unknownLabel.phones.single().type)
        assertEquals("assistant", unknownLabel.phones.single().label)
        assertEquals("OTHER", unknownLabel.emails.single().type)

        // التسمية "none" تعامل كسلاسة كالتسمية الافتراضية.
        val none = mapIosContact(
            IosContactValues(
                identifier = "X3",
                emails = listOf(IosLabeledValue(label = "none", value = "n@example.com"))
            )
        )
        assertEquals("PERSONAL", none.emails.single().type)
        assertNull(none.emails.single().label)
    }

    // --------------------------------------------------------------------- delta

    @Test
    fun assembleIosDelta_keepsDeletionsAndUpsertsForNormalBatches() {
        val snapshot = ContactsSnapshot(platformMarker = ContactsSnapshot.PLATFORM_IOS)
        val delta = assembleIosDelta(
            events = listOf(
                IosHistoryEvent.Upsert(IosContactValues(identifier = "up-1")),
                IosHistoryEvent.Delete(systemContactId = "gone-1"),
                IosHistoryEvent.Delete(systemContactId = "gone-1"), // التكرار يُدمج لا يُكرَّر.
                IosHistoryEvent.Ignored
            ),
            newSnapshot = snapshot
        )
        assertFalse(delta.isFullResync)
        assertEquals(1, delta.upserted.size)
        assertEquals("up-1", delta.upserted.single().systemContactId)
        assertEquals(listOf("gone-1"), delta.deletedSystemContactIds)
        assertSame(snapshot, delta.newSnapshot)
    }

    @Test
    fun assembleIosDelta_suppressesAllDeletesOnDropEverythingAndForcesFullResync() {
        val snapshot = ContactsSnapshot(platformMarker = ContactsSnapshot.PLATFORM_IOS)
        val delta = assembleIosDelta(
            events = listOf(
                IosHistoryEvent.Delete(systemContactId = "gone-1"),
                IosHistoryEvent.Upsert(IosContactValues(identifier = "fresh-1")),
                IosHistoryEvent.DropEverything,
                IosHistoryEvent.Upsert(IosContactValues(identifier = "fresh-2"))
            ),
            newSnapshot = snapshot
        )
        // `.dropEverything` يُلغي الحذف نهائياً ويفرض إعادة مزامنة كاملة (ADR-0010 §4).
        assertTrue(delta.isFullResync)
        assertTrue(delta.deletedSystemContactIds.isEmpty())
        assertEquals(setOf("fresh-1", "fresh-2"), delta.upserted.map { it.systemContactId }.toSet())
        assertSame(snapshot, delta.newSnapshot)
    }

    // --------------------------------------------------------------------- token

    @Test
    fun tokenRoundTrip_recoversExactBytes() {
        val original = byteArrayOf(0x00, 0x7F, 0x1A, 0x10, 0x43)
        val data = source.decodeIosHistoryToken(original)
        assertNotNull(data, "Non-empty bytes must decode to a token NSData.")
        val recovered = source.encodeIosHistoryToken(data)
        assertNotNull(recovered)
        assertTrue(original.contentEquals(recovered), "Token round trip must preserve exact bytes.")
    }

    @Test
    fun emptyOrMissingTokensDecodeToNullAndForceFullResync() {
        assertNull(source.decodeIosHistoryToken(null), "null bytes → null token → full resync.")
        assertNull(source.decodeIosHistoryToken(ByteArray(0)), "Empty bytes → null token → full resync.")
        assertNull(source.encodeIosHistoryToken(null))
        assertNull(source.encodeIosHistoryToken(NSData()), "Zero-length NSData → null bytes.")
    }

    @Test
    fun garbageBytesDecodeAsOpaqueTokenDataForTheStoreToRespondWithDropEverything() {
        val garbage = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val decoded = source.decodeIosHistoryToken(garbage)
        assertNotNull(decoded)
        assertEquals(garbage.size, decoded.length.toInt())
        // البايتات التالفة تُمرَّر كما هي؛ المخزن يردّها بـ `.dropEverything` + إضافات
        // كاملة (TN3149) فتتحقق دلالات "رمز غير صالح → إعادة مزامنة كاملة بلا حذف".
        assertTrue(garbage.contentEquals(source.encodeIosHistoryToken(decoded)))
    }
}
