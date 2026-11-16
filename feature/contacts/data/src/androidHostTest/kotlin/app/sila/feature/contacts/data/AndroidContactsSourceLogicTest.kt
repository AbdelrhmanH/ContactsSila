package app.sila.feature.contacts.data

import app.sila.core.model.ContactName
import app.sila.feature.contacts.domain.AndroidRawContactState
import app.sila.feature.contacts.domain.ContactsSnapshot
import app.sila.feature.contacts.domain.PlatformContact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `reparent with sibling edit in same window keeps the shared live key`() {
        // D1 (جولة 5): الشقيق 10 تغيّر أيضاً في نفس النافذة وبقي بتجمّعته 100 بمفتاح
        // lookup-A الحي — مفتاحه الحالي ضمن upserts الدلتا، فلا يصح أن يظهر lookup-A
        // محذوفاً في نفس الدلتا التي تُعيد تأكيده.
        val saved = mapOf(
            "10" to AndroidRawContactState(version = 4L, lookupKey = "lookup-A", aggregateContactId = 100L),
            "11" to AndroidRawContactState(version = 2L, lookupKey = "lookup-A", aggregateContactId = 100L)
        )
        val superseded = supersededLookupKeys(
            resolvedLookupKeys = mapOf("10" to "lookup-A", "11" to "lookup-B"),
            changedAggregatesById = mapOf("10" to 100L, "11" to 101L),
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

    // ---------------------------------------------------- isFullReadIncomplete

    /**
     * خريطة سيناريو ← اختبار (شرط C-2 من بوابة مراجعة التصميم `G7_DESIGN_REVIEW_GATE.md`):
     *
     * | السيناريو الإلزامي | الاختبار |
     * |---|---|
     * | 63 مرجعاً معلقاً شبيه-تيليجرام | `danglingTelegramShapedReferencesAreExemptFromCoverage` |
     * | 34 مرجعاً معلقاً شبيه-واتساب | `danglingWhatsAppShapedReferencesAreExemptFromCoverage` |
     * | كلاهما (97 · تركيبة MIUI الميدانية) | `miuiFieldShapedMixImportsSnapshotAroundDanglingReferences` |
     * | قطع جزئي حول معلّق | `partialPagingCutAroundDanglingReferencesStillReportsIncomplete` |
     * | مزيج صالح + مراجع شرعية | `fullCoverageOfCatalogAggregatesReportsReadComplete` |
     * | مرجع محلي فاسد غائب عن المسح | `genuinelyInvalidLocalReferenceAbsentFromScanRequiresNoCoverage` |
     * | مزوّد مجهول (محايدة-المزود) | `unknownProviderDanglingReferencesAreExemptProviderNeutral` |
     * | دفتر فارغ | `emptyCatalogWithNoUpsertsIsLegitimateEmptyBook` |
     * | مكررات | `duplicateRawRowsSharingOneDanglingAggregateIdAreExemptOnce` |
     * | حدود + كبير | `unaggregatedSentinelAggregateIdZeroIsExemptFromCoverage` + `miuiFieldShapedMix…` (816 صفاً) |
     * | بذرة 5000 بعد A+ | `emptyResolvableCatalogPassesGuardLayerForSeed5000Scenario`
     *   (طبقة 1) + `fullResyncCatalogWithoutImportRefusesCheckpointAndStaysFailed`
     *   (طبقة 3 — يبقى غير معدَّل أخضر) |
     * | مُعادَّ بمفتاح `null` | `resolvableAggregateWithNullLookupKeyIsExemptAndNotResolvable` |
     * | معلّق يصير مُعادَّ | `danglingReferenceBecomingResolvableIsImported` |
     * | استخراج المجموعة المُعادَّة (C-1/C-6) | قسم `extractResolvableAggregateContactIds` |
     * | تكامل المحرك بشكل الميدان | `ContactsSyncCheckpointIntegrityTest.miuiFieldShapedFullResyncImportsCatalogAndStampsReconcile` |
     *
     * «المرجع المعلق» هنا اصطناعي حصراً: معرف مجمّعة موجب حي **غائب عن المجموعة المُعادَّة**
     * (بلا صف مجمّع في `Contacts` ⇒ غير قابل للاستيراد بنائها) — مطابق لتصنيف الميدان
     * في `DIAG_rootcause_summary.md` (G7). لا بيانات حقيقية إطلاقاً (`SeedContact_%04d`).
     */

    @Test
    fun catalogAggregatesMissingFromPagedReadReportFullReadIncomplete() {
        // FINDING-SYNC-1 (ADR-0022): مُعادَّ غائب عن صفوف الترقيم = خرق مرجعي حقيقي
        // (صف موجود لم يُقرأ) — القطع الجزئي يبقى مكشوفاً بعد A+.
        val scan = listOf(
            RawContactScanRow(rawContactId = "10", version = 1L, deleted = false, aggregateContactId = 100L),
            RawContactScanRow(rawContactId = "11", version = 1L, deleted = false, aggregateContactId = 101L)
        )
        assertTrue(
            isFullReadIncomplete(
                scanRows = scan,
                resolvableAggregateContactIds = setOf(100L, 101L),
                pagedAggregateContactIds = emptySet()
            )
        )
    }

    @Test
    fun fullCoverageOfCatalogAggregatesReportsReadComplete() {
        val scan = listOf(
            RawContactScanRow(rawContactId = "10", version = 1L, deleted = false, aggregateContactId = 100L),
            RawContactScanRow(rawContactId = "11", version = 1L, deleted = false, aggregateContactId = 101L),
            // شقيقان لتجمّعة واحدة: التجمّعة تُطالَب تغطيتها مرة واحدة لا كل سجل خام.
            RawContactScanRow(rawContactId = "12", version = 1L, deleted = false, aggregateContactId = 101L)
        )
        assertFalse(
            isFullReadIncomplete(
                scanRows = scan,
                resolvableAggregateContactIds = setOf(100L, 101L),
                pagedAggregateContactIds = setOf(100L, 101L)
            )
        )
    }

    @Test
    fun partialPagingTruncationReportsReadIncomplete() {
        val scan = listOf(
            RawContactScanRow(rawContactId = "10", version = 1L, deleted = false, aggregateContactId = 100L),
            RawContactScanRow(rawContactId = "11", version = 1L, deleted = false, aggregateContactId = 101L)
        )
        assertTrue(
            isFullReadIncomplete(
                scanRows = scan,
                resolvableAggregateContactIds = setOf(100L, 101L),
                pagedAggregateContactIds = setOf(100L)
            )
        )
    }

    @Test
    fun emptyCatalogWithNoUpsertsIsLegitimateEmptyBook() {
        assertFalse(
            isFullReadIncomplete(
                scanRows = emptyList(),
                resolvableAggregateContactIds = emptySet(),
                pagedAggregateContactIds = emptySet()
            )
        )
    }

    @Test
    fun deletedAndUnaggregatedRawRowsAreExemptFromCoverage() {
        val scan = listOf(
            RawContactScanRow(rawContactId = "10", version = 1L, deleted = true, aggregateContactId = 100L),
            RawContactScanRow(rawContactId = "11", version = 1L, deleted = false, aggregateContactId = null)
        )
        assertFalse(
            isFullReadIncomplete(
                scanRows = scan,
                resolvableAggregateContactIds = emptySet(),
                pagedAggregateContactIds = emptySet()
            )
        )
    }

    @Test
    fun unaggregatedSentinelAggregateIdZeroIsExemptFromCoverage() {
        // القيمة المرسلة `CONTACT_ID = 0` (قبل إسناد تجمّعة عند بعض مزودات البائعين)
        // لا يمكن أن تظهر معرّفاً في جدول `Contacts` (_ID يبدأ من 1) — إعفاؤها يمنع
        // حلقة فشل دائمة على مزوّد كهذا؛ الحارس الثاني في المحرك يحمي صفر الاستيراد.
        val scan = listOf(
            RawContactScanRow(rawContactId = "10", version = 1L, deleted = false, aggregateContactId = 0L),
            RawContactScanRow(rawContactId = "11", version = 1L, deleted = false, aggregateContactId = 101L)
        )
        assertFalse(
            isFullReadIncomplete(
                scanRows = scan,
                resolvableAggregateContactIds = setOf(101L),
                pagedAggregateContactIds = setOf(101L)
            )
        )
    }

    @Test
    fun danglingTelegramShapedReferencesAreExemptFromCoverage() {
        // الميدان MIUI 2026-11-16 (G7): 63 سجلاً خاماً من مزامنة قراءة-فقط تعلن
        // `contact_id` موجباً بلا صف مجمّع — غير قابلة للاستيراد بنائها فلا تُطالَب تغطية.
        val scan = validScanRows(count = 717) +
            danglingShapedScanRows(count = 63, aggregateIdStart = 5000L, rawIdStart = 5000)
        assertFalse(
            isFullReadIncomplete(
                scanRows = scan,
                resolvableAggregateContactIds = (1L..717L).toSet(),
                pagedAggregateContactIds = (1L..717L).toSet()
            )
        )
    }

    @Test
    fun danglingWhatsAppShapedReferencesAreExemptFromCoverage() {
        // الميدان MIUI 2026-11-16 (G7): 34 سجلاً خاماً بنفس البنية — الإعفاء محايد-المزود.
        val scan = validScanRows(count = 717) +
            danglingShapedScanRows(count = 34, aggregateIdStart = 5100L, rawIdStart = 5100)
        assertFalse(
            isFullReadIncomplete(
                scanRows = scan,
                resolvableAggregateContactIds = (1L..717L).toSet(),
                pagedAggregateContactIds = (1L..717L).toSet()
            )
        )
    }

    @Test
    fun partialPagingCutAroundDanglingReferencesStillReportsIncomplete() {
        // إعفاء المعلّق لا يُطفئ كشف القطع الجزئي: مُعادَّ ({1..500 مقطوع}) غائب عن الترقيم
        // = خرق مرجعي حقيقي. الاستقلالية بين مصدرَي القياس هي ما يجعل التمييز ممكناً (B-1).
        val scan = validScanRows(count = 717) +
            danglingShapedScanRows(count = 97, aggregateIdStart = 5000L, rawIdStart = 5000)
        assertTrue(
            isFullReadIncomplete(
                scanRows = scan,
                resolvableAggregateContactIds = (1L..717L).toSet(),
                pagedAggregateContactIds = (1L..500L).toSet()
            )
        )
    }

    @Test
    fun unknownProviderDanglingReferencesAreExemptProviderNeutral() {
        // لا allowlist أسماء تطبيقات: أي مزوّد (موجود أو مستقبلي) يعلن إيجاباً بلا صف
        // يُعفى بنفس القاعدة — القاعدة بالبنية لا بالاسم.
        val scan = validScanRows(count = 10) +
            danglingShapedScanRows(count = 5, aggregateIdStart = 990_001L, rawIdStart = 1)
        assertFalse(
            isFullReadIncomplete(
                scanRows = scan,
                resolvableAggregateContactIds = (1L..10L).toSet(),
                pagedAggregateContactIds = (1L..10L).toSet()
            )
        )
    }

    @Test
    fun duplicateRawRowsSharingOneDanglingAggregateIdAreExemptOnce() {
        val scan = validScanRows(count = 1) + List(3) { i ->
            RawContactScanRow(
                rawContactId = "raw-dup-$i",
                version = 3L,
                deleted = false,
                aggregateContactId = 7000L
            )
        }
        assertFalse(
            isFullReadIncomplete(
                scanRows = scan,
                resolvableAggregateContactIds = setOf(1L),
                pagedAggregateContactIds = setOf(1L)
            )
        )
    }

    @Test
    fun genuinelyInvalidLocalReferenceAbsentFromScanRequiresNoCoverage() {
        // مرجع فاسد فعلاً اختفى سجله الخام من المسح: الحارس لا يطالب بتغطية غير المعلن —
        // ومعرفات قديمة في `paged`/`resolvable` لا تجعل القراءة ناقصة.
        val scan = validScanRows(count = 1)
        assertFalse(
            isFullReadIncomplete(
                scanRows = scan,
                resolvableAggregateContactIds = setOf(1L, 42L),
                pagedAggregateContactIds = setOf(1L, 42L)
            )
        )
    }

    @Test
    fun emptyResolvableCatalogPassesGuardLayerForSeed5000Scenario() {
        // بذرة 5000 (المخاطرة الأصلية لـ ADR-0022): كتالوج `Contacts` عابر الفارغ ⇒ لا
        // أزواج مُعادَّة ⇒ طبقتا 1/2 تعديان — **رفض «صفر استيراد» انتقل إلى حارس طبقة 3
        // في المحرك** (`fullResyncCatalogWithoutImportRefusesCheckpointAndStaysFailed`
        // يبقى غير معدَّل أخضر): تحوّل موضع الرفض بنفس النتيجة المرئية (F-2 → C-3/ج).
        val scan = validScanRows(count = 5000)
        assertFalse(
            isFullReadIncomplete(
                scanRows = scan,
                resolvableAggregateContactIds = emptySet(),
                pagedAggregateContactIds = emptySet()
            )
        )
    }

    @Test
    fun resolvableAggregateWithNullLookupKeyIsExemptAndNotResolvable() {
        // تجمّعة صفها موجود لكن استعلام المزوّد عاد بمفتاح `LOOKUP_KEY` فارغ: غير مُعادَّ
        // (لا يمكن بناء استيراد منها — `mapAndroidContactRows` يرمي بلا مفتاح) فمعفاة
        // من التغطية، وتُقرأ صفّها في الترقيم وتدخل اللقطة عبر سجلها الخام كالمعتاد.
        val scan = listOf(
            RawContactScanRow(rawContactId = "10", version = 1L, deleted = false, aggregateContactId = 100L),
            RawContactScanRow(rawContactId = "11", version = 1L, deleted = false, aggregateContactId = 300L)
        )
        val resolvable = extractResolvableAggregateContactIds(
            scanRows = scan,
            resolvedPairs = listOf(
                ResolvedAggregateLookup(aggregateContactId = 100L, lookupKey = "lookup-100"),
                ResolvedAggregateLookup(aggregateContactId = 300L, lookupKey = null)
            )
        )
        assertEquals(setOf(100L), resolvable)
        assertFalse(
            isFullReadIncomplete(
                scanRows = scan,
                resolvableAggregateContactIds = resolvable,
                pagedAggregateContactIds = setOf(100L)
            )
        )
    }

    @Test
    fun danglingReferenceBecomingResolvableIsImported() {
        // معلّق حلّه المزوّد لاحقاً (أنشأ صفه المجمّع): صار مُعادَّ ومقروءاً في الترقيم ⇒
        // التغطية مكتملة ويُستورد — مسار الاسترداد الأول (رفع `VERSION`) والمصالحة
        // الدورية مسار ثانٍ مستقل (ADR-0010 §3).
        val scan = validScanRows(count = 2) +
            danglingShapedScanRows(count = 1, aggregateIdStart = 5000L, rawIdStart = 1)
        val delta = buildFullResyncDelta(
            scanRows = scan,
            resolvableAggregateContactIds = setOf(1L, 2L, 5000L),
            pagedAggregateIds = setOf(1L, 2L, 5000L),
            resolvedLookupKeys = mapOf(
                "raw-1" to "lookup-1",
                "raw-2" to "lookup-2",
                "raw-dangling-1" to "lookup-5000"
            ),
            upserted = listOf(
                PlatformContact(
                    systemContactId = "lookup-5000",
                    lookupKey = "lookup-5000",
                    name = ContactName(displayName = "SeedContact_5000")
                )
            ),
            previousSnapshot = null
        )
        val snapshot = assertNotNull(delta.newSnapshot)
        assertEquals("lookup-5000", snapshot.androidRawContacts.getValue("raw-dangling-1").lookupKey)
    }

    // --------------------------------- extractResolvableAggregateContactIds (شرط C-1)

    @Test
    fun extractResolvableIgnoresNullLookupKeys() {
        val scan = validScanRows(count = 2)
        val resolvable = extractResolvableAggregateContactIds(
            scanRows = scan,
            resolvedPairs = listOf(
                ResolvedAggregateLookup(aggregateContactId = 1L, lookupKey = "lookup-1"),
                ResolvedAggregateLookup(aggregateContactId = 2L, lookupKey = null)
            )
        )
        assertEquals(setOf(1L), resolvable)
    }

    @Test
    fun extractResolvableIgnoresZeroAndNegativeAggregateIds() {
        val scan = validScanRows(count = 1)
        val resolvable = extractResolvableAggregateContactIds(
            scanRows = scan,
            resolvedPairs = listOf(
                ResolvedAggregateLookup(aggregateContactId = 0L, lookupKey = "sentinel"),
                ResolvedAggregateLookup(aggregateContactId = -7L, lookupKey = "negative"),
                ResolvedAggregateLookup(aggregateContactId = 1L, lookupKey = "lookup-1")
            )
        )
        assertEquals(setOf(1L), resolvable)
    }

    @Test
    fun extractResolvableOnlyKeepsDeclaredLiveAggregates() {
        // C-6: المجموعة من أزواج المزوّد العائدة حصراً ∧ المعلن الحي الموجب من المسح —
        // معرف صف محذوف أو غير معلن (900) لا يصير «مُعادَّ» بالخطأ.
        val scan = listOf(
            RawContactScanRow(rawContactId = "10", version = 1L, deleted = false, aggregateContactId = 100L),
            RawContactScanRow(rawContactId = "11", version = 1L, deleted = false, aggregateContactId = 101L),
            RawContactScanRow(rawContactId = "12", version = 1L, deleted = true, aggregateContactId = 102L)
        )
        val resolvable = extractResolvableAggregateContactIds(
            scanRows = scan,
            resolvedPairs = listOf(
                ResolvedAggregateLookup(aggregateContactId = 100L, lookupKey = "lookup-100"),
                ResolvedAggregateLookup(aggregateContactId = 101L, lookupKey = null),
                ResolvedAggregateLookup(aggregateContactId = 102L, lookupKey = "lookup-102"),
                ResolvedAggregateLookup(aggregateContactId = 900L, lookupKey = "lookup-900")
            )
        )
        assertEquals(setOf(100L), resolvable)
    }

    @Test
    fun extractResolvableMergesDuplicatePairs() {
        val scan = validScanRows(count = 1)
        val resolvable = extractResolvableAggregateContactIds(
            scanRows = scan,
            resolvedPairs = List(4) {
                ResolvedAggregateLookup(aggregateContactId = 1L, lookupKey = "lookup-1")
            }
        )
        assertEquals(setOf(1L), resolvable)
    }

    // ------------------------------------------------------- buildFullResyncDelta

    @Test
    fun fullResyncDeltaDecisionScanWithoutPagedCoverageYieldsNullSnapshot() {
        // FINDING-SYNC-1 (ADR-0022): تثبيت طبقة التوصيل نفسها — مُعادَّ غير مغطى بالترقيم
        // ← بلا لقطة إطلاقاً مهما كانت upserts والمفاتيح.
        val scan = listOf(
            RawContactScanRow(rawContactId = "10", version = 1L, deleted = false, aggregateContactId = 100L)
        )
        val delta = buildFullResyncDelta(
            scanRows = scan,
            resolvableAggregateContactIds = setOf(100L),
            pagedAggregateIds = emptySet(),
            resolvedLookupKeys = mapOf("10" to "lookup-10"),
            upserted = emptyList(),
            previousSnapshot = null
        )
        assertNull(delta.newSnapshot)
        assertTrue(delta.isFullResync)
        assertTrue(delta.deletedSystemContactIds.isEmpty())
    }

    @Test
    fun fullResyncDeltaDecisionCompleteCoverageBuildsT0Snapshot() {
        val scan = listOf(
            RawContactScanRow(rawContactId = "10", version = 4L, deleted = false, aggregateContactId = 100L)
        )
        val delta = buildFullResyncDelta(
            scanRows = scan,
            resolvableAggregateContactIds = setOf(100L),
            pagedAggregateIds = setOf(100L),
            resolvedLookupKeys = mapOf("10" to "lookup-10"),
            upserted = listOf(
                PlatformContact(
                    systemContactId = "lookup-10",
                    lookupKey = "lookup-10",
                    name = ContactName(displayName = "عينة التغطية")
                )
            ),
            previousSnapshot = null
        )
        val snapshot = assertNotNull(delta.newSnapshot)
        assertEquals(ContactsSnapshot.PLATFORM_ANDROID, snapshot.platformMarker)
        assertEquals(4L, snapshot.androidRawContacts.getValue("10").version)
        assertEquals("lookup-10", snapshot.androidRawContacts.getValue("10").lookupKey)
        assertTrue(delta.isFullResync)
    }

    @Test
    fun miuiFieldShapedMixImportsSnapshotAroundDanglingReferences() {
        // التركيبة الميدانية الكاملة (G7 · `DIAG_rootcause_summary.md`، اصطناعية):
        // 717 مُعادَّ + 63 معلّقاً شبيه-تيليجرام + 34 معلّقاً شبيه-واتساب + صفوف حدودية.
        val scan = validScanRows(count = 717) +
            danglingShapedScanRows(count = 63, aggregateIdStart = 5000L, rawIdStart = 5000) +
            danglingShapedScanRows(count = 34, aggregateIdStart = 5100L, rawIdStart = 5100) +
            listOf(
                RawContactScanRow(rawContactId = "raw-del", version = 1L, deleted = true, aggregateContactId = 6000L),
                RawContactScanRow(rawContactId = "raw-null", version = 1L, deleted = false, aggregateContactId = null),
                RawContactScanRow(rawContactId = "raw-zero", version = 1L, deleted = false, aggregateContactId = 0L)
            )
        val importableCatalog = (1L..717L).toSet()
        val delta = buildFullResyncDelta(
            scanRows = scan,
            resolvableAggregateContactIds = importableCatalog,
            pagedAggregateIds = importableCatalog,
            resolvedLookupKeys = (1L..717L).associate { id -> "raw-$id" to "lookup-$id" },
            upserted = (1L..717L).map { id ->
                PlatformContact(
                    systemContactId = "lookup-$id",
                    lookupKey = "lookup-$id",
                    name = ContactName(displayName = "SeedContact_%04d".format(id))
                )
            },
            previousSnapshot = null
        )
        val snapshot = assertNotNull(delta.newSnapshot, "الكتالوج القابل للاستيراد مقروء بالكامل ← لقطة تُقدَّم")
        assertTrue(delta.isFullResync)
        assertEquals(717, delta.upserted.size)
        assertEquals(
            717 + 63 + 34 + 2,
            snapshot.androidRawContacts.size,
            "المعلّق يبقى في اللقطة كما اليوم (لا إخفاء ولا حذف) — المحذوف والبلا تجمّعة خارجان"
        )
        assertNull(snapshot.androidRawContacts.getValue("raw-dangling-5000").lookupKey)
    }

    // --------------------------------------------------- أُصناف مساعدة اصطناعية (G7)

    /** صفوف مسح صالحة اصطناعية: `SeedContact_%04d` بلا أي بيانات حقيقية. */
    private fun validScanRows(count: Int, idStart: Long = 1L): List<RawContactScanRow> =
        (idStart until idStart + count).map { id ->
            RawContactScanRow(
                rawContactId = "raw-$id",
                version = 3L,
                deleted = false,
                aggregateContactId = id
            )
        }

    /** صفوف «مراجع معلقة» اصطناعية: معرف مجمّعة موجب حي لا صف مجمّع له في `Contacts`. */
    private fun danglingShapedScanRows(
        count: Int,
        aggregateIdStart: Long,
        rawIdStart: Int,
    ): List<RawContactScanRow> = (0 until count).map { i ->
        RawContactScanRow(
            rawContactId = "raw-dangling-${rawIdStart + i}",
            version = 3L,
            deleted = false,
            aggregateContactId = aggregateIdStart + i
        )
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
