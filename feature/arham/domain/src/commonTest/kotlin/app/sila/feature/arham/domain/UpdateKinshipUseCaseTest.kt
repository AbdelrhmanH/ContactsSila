package app.sila.feature.arham.domain

import app.sila.core.common.AppResult
import app.sila.core.common.DataError
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.KinshipLayer
import app.sila.core.model.Relation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/**
 * اختبارات تحديث القرابة (T8-11 · AC-1 · Issue #29): الجدول الموثق للوتيرة الافتراضية،
 * وقراءة-تعديل-كتابة تحفظ `lastContactedAt` (بوابة AC-8)، واشتقاق الطبقة من
 * `relation.layer` حصراً (O-7)، ومرور `parentContactId`/`pausedUntil`، وفشل `NotFound`
 * بلا أي كتابة لجهة مفقودة.
 */
class UpdateKinshipUseCaseTest {

    private class FakeArhamRepository(initial: List<Contact>) : ArhamRepository {
        val contactsFlow = MutableStateFlow(initial)
        val upserts = mutableListOf<Pair<Long, ArhamInfo>>()

        override fun observeArhamContacts(): Flow<List<Contact>> = contactsFlow

        override suspend fun upsertArhamInfo(contactId: Long, info: ArhamInfo) {
            upserts += contactId to info
        }
    }

    private val useCase = UpdateKinshipUseCase(
        repository = FakeArhamRepository(emptyList()),
    )

    private fun contact(id: Long, arham: ArhamInfo?) = Contact(
        id = id,
        systemContactId = "sys-$id",
        name = ContactName(displayName = "ج$id"),
        arham = arham,
    )

    // ===== الجدول الموثق للوتيرة الافتراضية (AC-1 — حرفياً من ورقة الميزة) =====

    @Test
    fun defaultFrequency_dailyForParents() {
        assertEquals(ArhamFrequency.DAILY, UpdateKinshipUseCase.defaultFrequencyFor(Relation.FATHER))
        assertEquals(ArhamFrequency.DAILY, UpdateKinshipUseCase.defaultFrequencyFor(Relation.MOTHER))
    }

    @Test
    fun defaultFrequency_weeklyForSiblings() {
        assertEquals(ArhamFrequency.WEEKLY, UpdateKinshipUseCase.defaultFrequencyFor(Relation.BROTHER))
        assertEquals(ArhamFrequency.WEEKLY, UpdateKinshipUseCase.defaultFrequencyFor(Relation.SISTER))
    }

    @Test
    fun defaultFrequency_biweeklyForUnclesAndAuntsBothSides() {
        assertEquals(ArhamFrequency.BIWEEKLY, UpdateKinshipUseCase.defaultFrequencyFor(Relation.UNCLE_PATERNAL))
        assertEquals(ArhamFrequency.BIWEEKLY, UpdateKinshipUseCase.defaultFrequencyFor(Relation.AUNT_PATERNAL))
        assertEquals(ArhamFrequency.BIWEEKLY, UpdateKinshipUseCase.defaultFrequencyFor(Relation.UNCLE_MATERNAL))
        assertEquals(ArhamFrequency.BIWEEKLY, UpdateKinshipUseCase.defaultFrequencyFor(Relation.AUNT_MATERNAL))
    }

    @Test
    fun defaultFrequency_monthlyForCousinsBothSides() {
        assertEquals(ArhamFrequency.MONTHLY, UpdateKinshipUseCase.defaultFrequencyFor(Relation.COUSIN_PATERNAL))
        assertEquals(ArhamFrequency.MONTHLY, UpdateKinshipUseCase.defaultFrequencyFor(Relation.COUSIN_MATERNAL))
    }

    @Test
    fun defaultFrequency_nullForUnlistedRelations_noInventedDefaults() {
        // الأجداد/الأبناء/الزوج/حماية/أصدقاء/مخصص: بلا اقتراح — الاختيار إلزامي.
        val unlisted = listOf(
            Relation.GRANDFATHER,
            Relation.GRANDMOTHER,
            Relation.SON,
            Relation.DAUGHTER,
            Relation.SPOUSE,
            Relation.IN_LAW,
            Relation.CLOSE_FRIEND,
            Relation.CUSTOM,
        )
        unlisted.forEach { relation ->
            assertNull(UpdateKinshipUseCase.defaultFrequencyFor(relation), "relation=$relation")
        }
    }

    // ===== قراءة-تعديل-كتابة (AC-8 / O-7 / AC-13) =====

    @Test
    fun invoke_preservesLastContactedAndDerivesLayer_passesParentAndPause() = runTest {
        val lastContactedAt = 1_760_000_000_000L
        val repo = FakeArhamRepository(
            listOf(
                contact(
                    id = 7L,
                    arham = ArhamInfo(
                        relation = Relation.UNCLE_PATERNAL,
                        frequency = ArhamFrequency.OCCASIONS,
                        lastContactedAt = lastContactedAt,
                        pausedUntil = null,
                    ),
                ),
            ),
        )
        val result = UpdateKinshipUseCase(repository = repo)(
            contactId = 7L,
            relation = Relation.BROTHER,
            frequency = ArhamFrequency.WEEKLY,
            parentContactId = 3L,
            pausedUntilMillis = 1_760_086_400_000L,
        )

        assertTrue(result is AppResult.Success)
        val (contactId, info) = repo.upserts.single()
        assertEquals(7L, contactId)
        assertEquals(Relation.BROTHER, info.relation)
        assertEquals(KinshipLayer.SELF_BRANCH, info.layer) // O-7: الطبقة مشتقة حصراً
        assertEquals(ArhamFrequency.WEEKLY, info.frequency)
        assertEquals(3L, info.parentContactId) // حافة الشجرة الاختيارية (AC-13)
        assertEquals(lastContactedAt, info.lastContactedAt) // AC-8: لا يُمسّ
        assertEquals(1_760_086_400_000L, info.pausedUntil) // يُستبدل بطلب صريح حصراً
    }

    @Test
    fun invoke_withoutPause_clearsPausedUntil_andHandlesContactWithoutArhamInfo() = runTest {
        val repo = FakeArhamRepository(listOf(contact(id = 2L, arham = null)))
        val result = UpdateKinshipUseCase(repository = repo)(
            contactId = 2L,
            relation = Relation.COUSIN_MATERNAL,
            frequency = ArhamFrequency.MONTHLY,
        )

        assertTrue(result is AppResult.Success)
        val info = repo.upserts.single().second
        assertEquals(KinshipLayer.COLLATERAL_MATERNAL, info.layer)
        assertNull(info.parentContactId) // افتراضي
        assertNull(info.pausedUntil) // بلا طلب إيقاف = بلا إيقاف
        assertNull(info.lastContactedAt) // لم يكن له سابق — يبقى null
    }

    // ===== فشل بلا كتابة =====

    @Test
    fun invoke_missingContact_returnsNotFound_withoutAnyUpsert() = runTest {
        val repo = FakeArhamRepository(listOf(contact(id = 1L, arham = null)))
        val result = UpdateKinshipUseCase(repository = repo)(
            contactId = 99L,
            relation = Relation.BROTHER,
            frequency = ArhamFrequency.WEEKLY,
        )

        assertIs<AppResult.Error>(result)
        assertIs<DataError.NotFound>(result.error)
        assertEquals("99", (result.error as DataError.NotFound).entityId)
        assertTrue(repo.upserts.isEmpty()) // فشل مبكر — لا كتابة أبداً
    }
}
