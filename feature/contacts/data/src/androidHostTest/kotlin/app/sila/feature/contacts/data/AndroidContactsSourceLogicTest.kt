package app.sila.feature.contacts.data

import app.sila.feature.contacts.domain.AndroidRawContactState
import app.sila.feature.contacts.domain.ContactsSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * اختبارات المنطق النقي لمصدر جهات الاتصال على Android — JVM خالص بلا Robolectric
 * ولا أنواع `android.*` (Issue #22 · ADR-0010 §3).
 */
class AndroidContactsSourceLogicTest {

    // ------------------------------------------------------------ diffRawContactVersions

    @Test
    fun `version bump of an existing raw contact is reported as a change`() {
        val saved = mapOf(
            "10" to AndroidRawContactState(version = 4L, lookupKey = "lookup-10", aggregateContactId = 100L)
        )
        val diff = diffRawContactVersions(
            savedRawContacts = saved,
            currentRows = listOf(
                RawContactScanRow(rawContactId = "10", version = 5L, deleted = false, aggregateContactId = 100L)
            )
        )
        assertEquals(listOf("10"), diff.changedRawContactIds)
        assertTrue(diff.deletedSystemContactIds.isEmpty())
        assertTrue(diff.deletedRawContactIds.isEmpty())
    }

    @Test
    fun `unchanged versions produce no changes`() {
        val saved = mapOf(
            "10" to AndroidRawContactState(version = 4L, lookupKey = "lookup-10", aggregateContactId = 100L)
        )
        val diff = diffRawContactVersions(
            savedRawContacts = saved,
            currentRows = listOf(
                RawContactScanRow(rawContactId = "10", version = 4L, deleted = false, aggregateContactId = 100L)
            )
        )
        assertTrue(diff.changedRawContactIds.isEmpty())
        assertTrue(diff.deletedSystemContactIds.isEmpty())
    }

    @Test
    fun `new raw contact is reported as a change`() {
        val diff = diffRawContactVersions(
            savedRawContacts = emptyMap(),
            currentRows = listOf(
                RawContactScanRow(rawContactId = "20", version = 1L, deleted = false, aggregateContactId = 200L)
            )
        )
        assertEquals(listOf("20"), diff.changedRawContactIds)
    }

    @Test
    fun `row marked DELETED=1 resolves deletion through the saved lookup key`() {
        val saved = mapOf(
            "10" to AndroidRawContactState(version = 4L, lookupKey = "lookup-10", aggregateContactId = 100L),
            "11" to AndroidRawContactState(version = 2L, lookupKey = "lookup-11", aggregateContactId = 101L)
        )
        val diff = diffRawContactVersions(
            savedRawContacts = saved,
            currentRows = listOf(
                // 10 اختفى كلياً، 11 معلَّم DELETED=1.
                RawContactScanRow(rawContactId = "11", version = 2L, deleted = true, aggregateContactId = 101L)
            )
        )
        assertEquals(listOf("lookup-11", "lookup-10"), diff.deletedSystemContactIds)
        assertEquals(setOf("11", "10"), diff.deletedRawContactIds.toSet())
        // السجل المحذوف لا يُعد تغييراً.
        assertTrue(diff.changedRawContactIds.isEmpty())
    }

    @Test
    fun `deleted raw contact without a saved lookup key produces no system-contact deletion`() {
        val saved = mapOf(
            "10" to AndroidRawContactState(version = 4L, lookupKey = null, aggregateContactId = 100L)
        )
        val diff = diffRawContactVersions(
            savedRawContacts = saved,
            currentRows = listOf(
                RawContactScanRow(rawContactId = "10", version = 4L, deleted = true, aggregateContactId = 100L)
            )
        )
        assertTrue(diff.deletedSystemContactIds.isEmpty())
        assertEquals(listOf("10"), diff.deletedRawContactIds)
    }

    @Test
    fun `deleting one sibling raw contact keeps the aggregate alive`() {
        val saved = mapOf(
            "10" to AndroidRawContactState(version = 4L, lookupKey = "lookup-A", aggregateContactId = 100L),
            "11" to AndroidRawContactState(version = 2L, lookupKey = "lookup-A", aggregateContactId = 100L)
        )
        val diff = diffRawContactVersions(
            savedRawContacts = saved,
            currentRows = listOf(
                // العضو 10 ما يزال نشطاً بنفس المفتاح، والعضو 11 فُصل (DELETED=1).
                RawContactScanRow(rawContactId = "10", version = 4L, deleted = false, aggregateContactId = 100L),
                RawContactScanRow(rawContactId = "11", version = 2L, deleted = true, aggregateContactId = 100L)
            )
        )
        // العضو المفصول يُزال من اللقطة، لكن الجهة المجمّعة الحيّة لا تُبلَّغ محذوفة.
        assertEquals(listOf("11"), diff.deletedRawContactIds)
        assertTrue(
            diff.deletedSystemContactIds.isEmpty(),
            "An aggregate with an active sibling raw must never be reported deleted."
        )
    }

    @Test
    fun `unlinking one sibling raw contact keeps the aggregate alive`() {
        val saved = mapOf(
            "10" to AndroidRawContactState(version = 4L, lookupKey = "lookup-A", aggregateContactId = 100L),
            "11" to AndroidRawContactState(version = 2L, lookupKey = "lookup-A", aggregateContactId = 100L)
        )
        val diff = diffRawContactVersions(
            savedRawContacts = saved,
            currentRows = listOf(
                // العضو 11 اختفى كلياً من المسح (فصل تجميع) دون DELETED=1، والعضو 10 نشط.
                RawContactScanRow(rawContactId = "10", version = 4L, deleted = false, aggregateContactId = 100L)
            )
        )
        assertEquals(listOf("11"), diff.deletedRawContactIds)
        assertTrue(
            diff.deletedSystemContactIds.isEmpty(),
            "Unlinking one raw of an aggregate with an active sibling must not delete the contact."
        )
    }

    @Test
    fun `deleting the last active raw of a lookup key reports the aggregate deleted`() {
        val saved = mapOf(
            "10" to AndroidRawContactState(version = 4L, lookupKey = "lookup-A", aggregateContactId = 100L),
            "11" to AndroidRawContactState(version = 2L, lookupKey = "lookup-B", aggregateContactId = 101L)
        )
        val diff = diffRawContactVersions(
            savedRawContacts = saved,
            currentRows = listOf(
                // كلا العضوين حُذفا: المفتاح A يُبلَّغ محذوفاً (لا عضو نشط) كما B.
                RawContactScanRow(rawContactId = "10", version = 4L, deleted = true, aggregateContactId = 100L),
                RawContactScanRow(rawContactId = "11", version = 2L, deleted = true, aggregateContactId = 101L)
            )
        )
        assertEquals(setOf("lookup-A", "lookup-B"), diff.deletedSystemContactIds.toSet())
        assertEquals(setOf("10", "11"), diff.deletedRawContactIds.toSet())
    }

    // ------------------------------------------------- supersededLookupKeys

    @Test
    fun `regenerated lookup key reports the old key as superseded`() {
        val saved = mapOf(
            "10" to AndroidRawContactState(version = 4L, lookupKey = "old-10", aggregateContactId = 100L)
        )
        val superseded = supersededLookupKeys(
            resolvedLookupKeys = mapOf("10" to "new-10"),
            changedAggregatesById = mapOf("10" to 100L),
            savedRawContacts = saved
        )
        assertEquals(listOf("old-10"), superseded)
    }

    @Test
    fun `unchanged lookup keys and brand new raws report nothing superseded`() {
        val saved = mapOf(
            "10" to AndroidRawContactState(version = 4L, lookupKey = "lookup-10", aggregateContactId = 100L)
        )
        val superseded = supersededLookupKeys(
            resolvedLookupKeys = mapOf(
                "10" to "lookup-10", // نفس المفتاح — لا تغيير هوية.
                "77" to "lookup-77" // سجل خام جديد بلا حالة محفوظة.
            ),
            changedAggregatesById = mapOf("10" to 100L, "77" to 102L),
            savedRawContacts = saved
        )
        assertTrue(superseded.isEmpty())
    }

    @Test
    fun `two raws of one aggregate whose key changed report the old key once`() {
        val saved = mapOf(
            "10" to AndroidRawContactState(version = 4L, lookupKey = "old-A", aggregateContactId = 100L),
            "11" to AndroidRawContactState(version = 2L, lookupKey = "old-A", aggregateContactId = 100L)
        )
        val superseded = supersededLookupKeys(
            resolvedLookupKeys = mapOf("10" to "new-A", "11" to "new-A"),
            changedAggregatesById = mapOf("10" to 100L, "11" to 100L),
            savedRawContacts = saved
        )
        assertEquals(listOf("old-A"), superseded)
    }

    @Test
    fun `reparenting raw to another aggregate keeps the shared old key live`() {
        // F1 (جولة 4): سجل خام أعيد ربطه بتجمّعة أخرى لا يُبطل مفتاح التجمّعة القديمة —
        // الشقيق غير المتغير ما يزال حياً بالمفتاح نفسه، والإبلاغ عنه كان يمحو جهة حيّة.
        val saved = mapOf(
            "10" to AndroidRawContactState(version = 4L, lookupKey = "lookup-A", aggregateContactId = 100L),
            "11" to AndroidRawContactState(version = 2L, lookupKey = "lookup-A", aggregateContactId = 100L)
        )
        val superseded = supersededLookupKeys(
            resolvedLookupKeys = mapOf("11" to "lookup-B"),
            changedAggregatesById = mapOf("11" to 101L),
            savedRawContacts = saved
        )
        assertTrue(superseded.isEmpty())
    }

    @Test
    fun `joining aggregates emits both dissolved keys`() {
        // تجمّعتان تنضمان إلى واحدة بمفتاح جديد: كلا المفتاحين القديمين مات (لا سجل
        // غير متغير يحملهما) فيُبلَغان محذوفين مع upsert المفتاح الجديد.
        val saved = mapOf(
            "10" to AndroidRawContactState(version = 4L, lookupKey = "lookup-A", aggregateContactId = 100L),
            "11" to AndroidRawContactState(version = 2L, lookupKey = "lookup-B", aggregateContactId = 101L)
        )
        val superseded = supersededLookupKeys(
            resolvedLookupKeys = mapOf("10" to "lookup-K", "11" to "lookup-K"),
            changedAggregatesById = mapOf("10" to 100L, "11" to 100L),
            savedRawContacts = saved
        )
        assertEquals(setOf("lookup-A", "lookup-B"), superseded.toSet())
    }

    // ------------------------------------------------- resolveProviderDeletedContacts

    @Test
    fun `provider deletions resolve only through snapshot saved aggregate ids`() {
        val saved = mapOf(
            "10" to AndroidRawContactState(version = 4L, lookupKey = "lookup-10", aggregateContactId = 100L),
            "11" to AndroidRawContactState(version = 4L, lookupKey = "lookup-11", aggregateContactId = null),
            "12" to AndroidRawContactState(version = 4L, lookupKey = "lookup-12", aggregateContactId = 102L)
        )
        val resolved = resolveProviderDeletedContacts(
            deletedRecords = listOf(
                DeletedAggregateContactRecord(aggregateContactId = 100L, deletedAtMillis = 1_000L),
                DeletedAggregateContactRecord(aggregateContactId = 999L, deletedAtMillis = 2_000L)
            ),
            savedRawContacts = saved
        )
        // 100 حُذف فعلاً في اللقطة، و999 ليس له حالة محفوظة، و11 بلا aggregateContactId.
        assertEquals(listOf("lookup-10"), resolved)
    }

    @Test
    fun `provider deletions never invent deletions for unknown contacts`() {
        val resolved = resolveProviderDeletedContacts(
            deletedRecords = listOf(DeletedAggregateContactRecord(aggregateContactId = 42L)),
            savedRawContacts = emptyMap()
        )
        assertTrue(resolved.isEmpty())
    }

    // ------------------------------------------------------- buildNextAndroidSnapshot

    @Test
    fun `unchanged raw contacts keep their previously saved lookup keys`() {
        val previous = ContactsSnapshot(
            platformMarker = ContactsSnapshot.PLATFORM_ANDROID,
            androidRawContacts = mapOf(
                "10" to AndroidRawContactState(version = 4L, lookupKey = "old-lookup-10", aggregateContactId = 100L)
            )
        )
        val next = buildNextAndroidSnapshot(
            currentRows = listOf(
                RawContactScanRow(rawContactId = "10", version = 4L, deleted = false, aggregateContactId = 100L)
            ),
            resolvedLookupKeys = emptyMap(),
            previousSnapshot = previous
        )
        assertEquals("old-lookup-10", next.androidRawContacts.getValue("10").lookupKey)
        assertEquals(4L, next.androidRawContacts.getValue("10").version)
        assertEquals(ContactsSnapshot.PLATFORM_ANDROID, next.platformMarker)
        assertNull(next.iosHistoryTokenBytes)
    }

    @Test
    fun `resolved lookup keys take precedence for changed raw contacts`() {
        val previous = ContactsSnapshot(
            platformMarker = ContactsSnapshot.PLATFORM_ANDROID,
            androidRawContacts = mapOf(
                "10" to AndroidRawContactState(version = 4L, lookupKey = "old-lookup-10", aggregateContactId = 100L)
            )
        )
        val next = buildNextAndroidSnapshot(
            currentRows = listOf(
                RawContactScanRow(rawContactId = "10", version = 5L, deleted = false, aggregateContactId = 100L)
            ),
            resolvedLookupKeys = mapOf("10" to "new-lookup-10"),
            previousSnapshot = previous
        )
        assertEquals("new-lookup-10", next.androidRawContacts.getValue("10").lookupKey)
        assertEquals(5L, next.androidRawContacts.getValue("10").version)
    }

    @Test
    fun `deleted rows and snapshot platform marker are handled`() {
        val previousIos = ContactsSnapshot(platformMarker = ContactsSnapshot.PLATFORM_IOS)
        val next = buildNextAndroidSnapshot(
            currentRows = listOf(
                RawContactScanRow(rawContactId = "10", version = 4L, deleted = false, aggregateContactId = 100L),
                RawContactScanRow(rawContactId = "11", version = 6L, deleted = true, aggregateContactId = 101L)
            ),
            resolvedLookupKeys = emptyMap(),
            previousSnapshot = previousIos
        )
        // الصفوف المحذوفة (DELETED=1) لا تدخل اللقطة، والعلامة تفرض ملكية Android.
        assertEquals(setOf("10"), next.androidRawContacts.keys)
        assertEquals(ContactsSnapshot.PLATFORM_ANDROID, next.platformMarker)
    }

    // ------------------------------------------------------------ mapAndroidContactRows

    @Test
    fun `contact row maps name parts phones emails organization websites and notes`() {
        val rows = listOf(
            AndroidContactRow(
                aggregateContactId = 100L,
                lookupKey = "lookup-100",
                displayName = null,
                photoUri = "content://photo",
                isFavorite = false
            )
        )
        val data = listOf(
            // الأعمدة الحقيقية لـ CommonDataKinds.StructuredName (AOSP):
            // DISPLAY_NAME=DATA1, GIVEN_NAME=DATA2, FAMILY_NAME=DATA3, PREFIX=DATA4,
            // MIDDLE_NAME=DATA5, SUFFIX=DATA6, PHONETIC_GIVEN/MIDDLE/FAMILY=DATA7/8/9.
            androidNameRecord(
                contactId = 100L,
                data1 = "سارة أحمد",
                data2 = "سارة",
                data3 = "أحمد",
                data4 = "الأستاذة",
                data5 = "الوسطى",
                data6 = "الشريفية",
                data7 = "سارة",
                data8 = "الوسطى",
                data9 = "أحمد"
            ),
            // Phone: NUMBER=DATA1, TYPE=DATA2, LABEL=DATA3, NORMALIZED_NUMBER=DATA4.
            androidPhoneRecord(
                contactId = 100L,
                data1 = "0777 123 456",
                data2 = "2",
                data3 = null,
                data4 = "+964777123456"
            ),
            androidPhoneRecord(
                contactId = 100L,
                data1 = "0790 000 111",
                data2 = "0",
                data3 = "بيت العم",
                data4 = null
            ),
            androidEmailRecord(contactId = 100L, data1 = "sara@example.com", data2 = "2", data3 = null),
            androidEmailRecord(contactId = 100L, data1 = "sara.work@example.com", data2 = "4", data3 = null),
            // Organization: COMPANY=DATA1, LABEL=DATA3, TITLE=DATA4, DEPARTMENT=DATA5.
            androidRecord(
                contactId = 100L,
                mimeType = AndroidDataMimeTypes.ORGANIZATION,
                data1 = "شركة الأفق",
                data4 = "مهندسة برمجيات",
                data5 = "قسم المحمول"
            ),
            androidRecord(
                contactId = 100L,
                mimeType = AndroidDataMimeTypes.WEBSITE,
                data1 = "https://sara.example.com"
            ),
            androidRecord(contactId = 100L, mimeType = AndroidDataMimeTypes.NOTE, data1 = "زميلة من العمل")
        )

        val contacts = mapAndroidContactRows(rows, data)
        assertEquals(1, contacts.size)
        val contact = contacts.single()

        // الهوية من LOOKUP_KEY حصراً — لا تخزين لـ Contacts._ID.
        assertEquals("lookup-100", contact.systemContactId)
        assertEquals("lookup-100", contact.lookupKey)

        assertEquals("سارة أحمد", contact.name.displayName)
        assertEquals("الأستاذة", contact.name.prefix)
        assertEquals("سارة", contact.name.firstName)
        assertEquals("الوسطى", contact.name.middleName)
        assertEquals("أحمد", contact.name.lastName)
        assertEquals("الشريفية", contact.name.suffix)
        assertEquals("سارة الوسطى أحمد", contact.name.phoneticName)

        assertEquals(2, contact.phones.size)
        val mobile = contact.phones.first { it.raw == "0777 123 456" }
        assertEquals("+964777123456", mobile.e164)
        assertEquals("MOBILE", mobile.type)
        val custom = contact.phones.first { it.raw == "0790 000 111" }
        // النوع 0 غير معروف → التسمية المخصصة، وبلا e164 → الخام البديل.
        assertEquals("بيت العم", custom.type)
        assertEquals("بيت العم", custom.label)
        assertEquals("0790 000 111", custom.e164)

        assertEquals(2, contact.emails.size)
        assertEquals("WORK", contact.emails.first { it.address == "sara@example.com" }.type)
        assertEquals("MOBILE", contact.emails.first { it.address == "sara.work@example.com" }.type)

        assertNotNull(contact.organization)
        assertEquals("شركة الأفق", contact.organization?.company)
        assertEquals("مهندسة برمجيات", contact.organization?.jobTitles?.single())
        assertEquals("قسم المحمول", contact.organization?.department)

        assertEquals(listOf("https://sara.example.com"), contact.websites)
        assertEquals("زميلة من العمل", contact.notes)
    }

    @Test
    fun `row displays fall back to structured name DATA1 for the display name`() {
        val contacts = mapAndroidContactRows(
            contactRows = listOf(
                AndroidContactRow(
                    aggregateContactId = 100L,
                    lookupKey = "lookup-100",
                    displayName = null,
                    photoUri = null,
                    isFavorite = false
                )
            ),
            dataRecords = listOf(androidNameRecord(contactId = 100L, data1 = "الاسم من Data"))
        )
        assertEquals("الاسم من Data", contacts.single().name.displayName)
    }

    @Test
    fun `row without lookup key or any name is skipped`() {
        val contacts = mapAndroidContactRows(
            contactRows = listOf(
                AndroidContactRow(
                    aggregateContactId = 100L,
                    lookupKey = null,
                    displayName = "بلا مفتاح",
                    photoUri = null,
                    isFavorite = false
                ),
                AndroidContactRow(
                    aggregateContactId = 101L,
                    lookupKey = "lookup-101",
                    displayName = null,
                    photoUri = null,
                    isFavorite = false
                )
            ),
            dataRecords = emptyList()
        )
        // الأولى بلا LOOKUP_KEY، والثانية بلا أي اسم — كلتاهما لا تُنتج جهة.
        assertTrue(contacts.isEmpty())
    }

    @Test
    fun `data records of other contacts do not leak across`() {
        val contacts = mapAndroidContactRows(
            contactRows = listOf(
                AndroidContactRow(
                    aggregateContactId = 100L,
                    lookupKey = "lookup-100",
                    displayName = "أحمد",
                    photoUri = null,
                    isFavorite = false
                )
            ),
            dataRecords = listOf(
                androidPhoneRecord(contactId = 999L, data1 = "0500000000", data2 = "2", data3 = null, data4 = null)
            )
        )
        val contact = contacts.single()
        assertEquals("أحمد", contact.name.displayName)
        assertTrue(contact.phones.isEmpty())
        assertTrue(contact.emails.isEmpty())
        assertNull(contact.organization)
    }

    // ------------------------------------------------------------ type mappers

    @Test
    fun `android phone type mapping covers known and unknown types`() {
        assertEquals("HOME", mapAndroidPhoneType(1, null))
        assertEquals("MOBILE", mapAndroidPhoneType(2, null))
        assertEquals("WORK", mapAndroidPhoneType(3, null))
        assertEquals("MAIN", mapAndroidPhoneType(12, null))
        assertEquals("PAGER", mapAndroidPhoneType(6, null))
        assertEquals("مخصص", mapAndroidPhoneType(0, "مخصص"))
        assertEquals("OTHER", mapAndroidPhoneType(0, null))
        assertEquals("OTHER", mapAndroidPhoneType(null, "  "))
    }

    @Test
    fun `android email type mapping covers known and unknown types`() {
        assertEquals("HOME", mapAndroidEmailType(1, null))
        assertEquals("WORK", mapAndroidEmailType(2, null))
        assertEquals("MOBILE", mapAndroidEmailType(4, null))
        assertEquals("PERSONAL", mapAndroidEmailType(3, null))
        assertEquals("مخصص", mapAndroidEmailType(3, "مخصص"))
        assertEquals("PERSONAL", mapAndroidEmailType(null, null))
    }

    // ---------------------------------------------------------------- fixtures

    private fun androidRecord(
        contactId: Long,
        mimeType: String,
        data1: String? = null,
        data2: String? = null,
        data3: String? = null,
        data4: String? = null,
        data5: String? = null,
        data6: String? = null,
        data7: String? = null,
        data8: String? = null,
        data9: String? = null,
    ): AndroidDataRecord = AndroidDataRecord(
        aggregateContactId = contactId,
        mimeType = mimeType,
        values = mapOf(
            AndroidContactsColumns.DATA1 to data1,
            AndroidContactsColumns.DATA2 to data2,
            AndroidContactsColumns.DATA3 to data3,
            AndroidContactsColumns.DATA4 to data4,
            AndroidContactsColumns.DATA5 to data5,
            AndroidContactsColumns.DATA6 to data6,
            AndroidContactsColumns.DATA7 to data7,
            AndroidContactsColumns.DATA8 to data8,
            AndroidContactsColumns.DATA9 to data9
        )
    )

    private fun androidNameRecord(
        contactId: Long,
        data1: String? = null,
        data2: String? = null,
        data3: String? = null,
        data4: String? = null,
        data5: String? = null,
        data6: String? = null,
        data7: String? = null,
        data8: String? = null,
        data9: String? = null,
    ): AndroidDataRecord = androidRecord(
        contactId = contactId,
        mimeType = AndroidDataMimeTypes.STRUCTURED_NAME,
        data1 = data1, data2 = data2, data3 = data3, data4 = data4, data5 = data5, data6 = data6,
        data7 = data7, data8 = data8, data9 = data9
    )

    private fun androidPhoneRecord(
        contactId: Long,
        data1: String? = null,
        data2: String? = null,
        data3: String? = null,
        data4: String? = null,
    ): AndroidDataRecord = androidRecord(
        contactId = contactId,
        mimeType = AndroidDataMimeTypes.PHONE,
        data1 = data1, data2 = data2, data3 = data3, data4 = data4
    )

    private fun androidEmailRecord(
        contactId: Long,
        data1: String? = null,
        data2: String? = null,
        data3: String? = null,
    ): AndroidDataRecord = androidRecord(
        contactId = contactId,
        mimeType = AndroidDataMimeTypes.EMAIL,
        data1 = data1, data2 = data2, data3 = data3
    )
}
