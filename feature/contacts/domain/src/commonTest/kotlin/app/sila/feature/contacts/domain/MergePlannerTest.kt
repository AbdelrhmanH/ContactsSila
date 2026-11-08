package app.sila.feature.contacts.domain

import app.sila.core.model.Address
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.CustomField
import app.sila.core.model.Email
import app.sila.core.model.ImportantDate
import app.sila.core.model.Organization
import app.sila.core.model.Phone
import app.sila.core.model.Relation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * عقود قواعد الدمج لـ T7 (Issue #28 — spec 04 §أ) + **اختبار خاصية عدم فقدان البيانات**:
 * اتحاد الأرقام الفريدة قبل الدمج = بعده (والبريد والمواقع — بالحدود المدعومة في النموذج).
 */
class MergePlannerTest {

    private fun contact(
        id: Long,
        createdAt: Long,
        name: String,
        phones: List<String> = emptyList(),
        emails: List<String> = emptyList(),
        websites: List<String> = emptyList(),
        category: Category = Category.UNCATEGORIZED,
        favorite: Boolean = false,
        ringtone: String? = null,
        notes: String? = null,
        exclude: Boolean = false,
        arham: ArhamInfo? = null,
    ) = Contact(
        id = id,
        systemContactId = "sys_$id",
        name = ContactName(displayName = name),
        phones = phones.map { Phone(raw = it, e164 = it) },
        emails = emails.map { Email(address = it) },
        websites = websites,
        category = category,
        isFavorite = favorite,
        ringtoneUri = ringtone,
        excludeFromSmartFilter = exclude,
        notes = notes,
        arham = arham,
        createdAt = createdAt,
    )

    // ——— قواعد الحقول ———

    @Test
    fun survivor_isOldestCreated_andKeepsItsPlatformIdentity() {
        val plan = MergePlanner.plan(
            listOf(
                contact(2, createdAt = 200L, name = "أحدث"),
                contact(1, createdAt = 100L, name = "الأقدم"),
            ),
        )
        assertEquals(1L, plan.merged.id)
        assertEquals("sys_1", plan.merged.systemContactId)
        assertEquals(listOf<Long>(2), plan.absorbed.map { it.id })
    }

    @Test
    fun name_mostComplete_thenLongest() {
        val plan = MergePlanner.plan(
            listOf(
                contact(1, 100L, "احمد"),
                contact(2, 200L, name = "احمد سيد إبراهيم"),
            ),
        )
        assertEquals("احمد سيد إبراهيم", plan.merged.name.displayName)
    }

    @Test
    fun phones_emails_websites_dates_customFields_areDedupedUnions() {
        val plan = MergePlanner.plan(
            listOf(
                contact(1, 100L, "نور", phones = listOf("+201000000001", "+201000000002"), emails = listOf("A@x.com")),
                contact(2, 200L, "نور عادل", phones = listOf("+201000000002", "+201000000003"), emails = listOf("a@x.com", "b@x.com"), websites = listOf("https://nour.example")),
            ),
        )
        assertEquals(listOf("+201000000001", "+201000000002", "+201000000003"), plan.merged.phones.map { it.e164 })
        assertEquals(listOf("A@x.com", "b@x.com"), plan.merged.emails.map { it.address })
        assertEquals(listOf("https://nour.example"), plan.merged.websites)
        assertEquals(0, plan.merged.phones.count { it.isPrimary } - if (plan.merged.phones.isEmpty()) 0 else 1)
    }

    @Test
    fun datesAndCustomFields_unionDeduped_agyF4() {
        val richer = contact(1, 100L, "نور").copy(
            dates = listOf(ImportantDate(type = "BIRTHDAY", label = null, month = 3, day = 21, year = null)),
            customFields = listOf(CustomField(label = "ميعاد", value = "الخامسة")),
        )
        val other = contact(2, 200L, "نور عادل").copy(
            dates = listOf(
                ImportantDate(type = "BIRTHDAY", label = null, month = 3, day = 21, year = null),
                ImportantDate(type = "ANNIVERSARY", label = null, month = 7, day = 2, year = null),
            ),
            customFields = listOf(CustomField(label = "ميعاد", value = "الخامسة")),
        )
        val plan = MergePlanner.plan(listOf(richer, other))
        assertEquals(2, plan.merged.dates.size, "التاريخ المكرر لا يتضاعف والجديد يُحفظ")
        assertEquals(1, plan.merged.customFields.size)
    }

    @Test
    fun category_priority_beatsEnumOrder_andNeverMeaninglessWhenMeaningfulExists() {
        // SERVICES أولى في التعداد لكن الأولوية الصريحة: FAMILY > EMERGENCY > WORK > FRIENDS > SERVICES
        val plan = MergePlanner.plan(
            listOf(
                contact(1, 100L, "طبيب", category = Category.SERVICES),
                contact(2, 200L, "قريب", category = Category.FAMILY),
            ),
        )
        assertEquals(Category.FAMILY, plan.merged.category)
    }

    @Test
    fun notes_mergedWithDocumentedSeparator_distinctOnly() {
        val plan = MergePlanner.plan(
            listOf(
                contact(1, 100L, "نور", notes = "جار الحديقة"),
                contact(2, 200L, "نور عادل", notes = "جار الحديقة"), // مكررة — تُستبعد
                contact(3, 300L, "نور الضايع", notes = "تحب الفول"),
            ),
        )
        assertEquals("جار الحديقة${MergePlanner.NOTES_SEPARATOR}تحب الفول", plan.merged.notes)
    }

    @Test
    fun favorite_anyTrue_wins_andExcludeFlagIsOr() {
        val plan = MergePlanner.plan(
            listOf(
                contact(1, 100L, "نور", favorite = false, exclude = true),
                contact(2, 200L, "نور عادل", favorite = true, exclude = false),
            ),
        )
        assertTrue(plan.merged.isFavorite)
        assertTrue(plan.merged.excludeFromSmartFilter, "OR المحافظ: الاستبعاد يبقى")
    }

    @Test
    fun ringtone_singleKept_conflictFlaggedNotSilentlyDropped() {
        val single = MergePlanner.plan(
            listOf(
                contact(1, 100L, "نور", ringtone = "content://ring1"),
                contact(2, 200L, "نور عادل"),
            ),
        )
        assertEquals("content://ring1", single.merged.ringtoneUri)
        assertFalse(single.ringtoneConflict)

        val conflict = MergePlanner.plan(
            listOf(
                contact(1, 100L, "نور", ringtone = "content://ring1"),
                contact(2, 200L, "نور عادل", ringtone = "content://ring2"),
            ),
        )
        assertEquals("content://ring1", conflict.merged.ringtoneUri, "التعارض: نغمة الناجي حتماً")
        assertTrue(conflict.ringtoneConflict, "التعارض يُعلَم للمعاينة — لا إسقاط صامت")
    }

    @Test
    fun arham_carriedFromSurvivor_andEmptyNotesStayNull() {
        val withArham = contact(1, 100L, "نور", arham = ArhamInfo(relation = Relation.SISTER, frequency = ArhamFrequency.WEEKLY))
        val plan = MergePlanner.plan(listOf(withArham, contact(2, 200L, "نور عادل")))
        assertEquals(Relation.SISTER, plan.merged.arham?.relation)
        assertNull(plan.merged.notes)
    }

    @Test
    fun arham_pickedFromOldestMember_withNewestLastContactedAt_andTieResolvedById() {
        val newerMember = contact(
            id = 2,
            createdAt = 200L,
            name = "أحمد الحديث",
            arham = ArhamInfo(
                relation = Relation.COUSIN_PATERNAL,
                frequency = ArhamFrequency.MONTHLY,
                lastContactedAt = 5000L,
            ),
        )
        val olderMember = contact(
            id = 1,
            createdAt = 100L,
            name = "أحمد الأقدم",
            arham = ArhamInfo(
                relation = Relation.BROTHER,
                frequency = ArhamFrequency.WEEKLY,
                lastContactedAt = 1000L,
            ),
        )

        // إدخال بترتيب عكسي
        val plan = MergePlanner.plan(listOf(newerMember, olderMember))

        // علاقة الأقدم إنشاءً (100L < 200L)
        assertEquals(Relation.BROTHER, plan.merged.arham?.relation)
        // أحدث lastContactedAt عبر كل الأعضاء
        assertEquals(5000L, plan.merged.arham?.lastContactedAt)
    }

    @Test
    fun arham_createdAtTie_resolvedByLowestId() {
        val memberB = contact(
            id = 5,
            createdAt = 100L,
            name = "ب",
            arham = ArhamInfo(relation = Relation.UNCLE_MATERNAL, frequency = ArhamFrequency.MONTHLY),
        )
        val memberA = contact(
            id = 3,
            createdAt = 100L,
            name = "أ",
            arham = ArhamInfo(relation = Relation.AUNT_MATERNAL, frequency = ArhamFrequency.WEEKLY),
        )

        val plan = MergePlanner.plan(listOf(memberB, memberA))
        assertEquals(Relation.AUNT_MATERNAL, plan.merged.arham?.relation, "التعادل في createdAt يُحل بالأصغر id")
    }

    // ——— خاصية عدم فقدان البيانات (Issue #28 AC) ———

    @Test
    fun property_noDataLoss_uniqueNumbersEmailsWebsitesPreserved_acrossSeededScenarios() {
        val seed = 0x7C1A7
        val random = kotlin.random.Random(seed)
        repeat(20) { scenario ->
            val memberCount = 2 + random.nextInt(4) // 2..5 أعضاء
            val members = (1..memberCount).map { index ->
                contact(
                    id = index.toLong(),
                    createdAt = index * 100L,
                    name = "عضو $index",
                    phones = (1..(1 + random.nextInt(3))).map { "+2010${random.nextInt(10_000_000, 99_999_999)}" },
                    emails = (1..random.nextInt(3)).map { "user$index.$it@ex.com" },
                    websites = if (random.nextBoolean()) listOf("https://m$index.ex") else emptyList(),
                )
            }
            val plan = MergePlanner.plan(members)
            val beforeNumbers = members.flatMap { m -> m.phones.mapNotNull { p -> p.e164.ifBlank { null } } }.toSet()
            val afterNumbers = plan.merged.phones.mapNotNull { p -> p.e164.ifBlank { null } }.toSet()
            val beforeEmails = members.flatMap { it.emails.map { e -> e.address.trim().lowercase() } }.toSet()
            val afterEmails = plan.merged.emails.map { it.address.trim().lowercase() }.toSet()
            val beforeWebsites = members.flatMap { it.websites }.map { it.trim() }.toSet()
            val afterWebsites = plan.merged.websites.map { it.trim() }.toSet()

            assertTrue(
                afterNumbers.containsAll(beforeNumbers),
                "السيناريو $scenario: كل رقم فريد قبل الدمج يبقى بعده (فُقد: ${beforeNumbers - afterNumbers})",
            )
            assertTrue(afterEmails.containsAll(beforeEmails), "السيناريو $scenario: البريد محفوظ")
            assertTrue(afterWebsites.containsAll(beforeWebsites), "السيناريو $scenario: المواقع محفوظة")
        }
    }
}
