package app.sila.feature.arham

import app.sila.core.common.AppDispatchers
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.datastore.SilaPreferencesDataSource
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Relation
import app.sila.core.model.TopFiveSelection
import app.sila.feature.arham.data.TopFiveMaterializationRepositoryImpl
import app.sila.feature.arham.domain.MaterializeTopFiveUseCase
import app.sila.feature.arham.domain.TopFiveMaterializationOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * اختبارات تكامل الطبقتين فوق قاعدة حية وDataStore حقيقي (T8-04 · §F.11 أ–د · Issue #29):
 * - **(أ) الإعادة:** تشغيل ناجح ثانٍ يعيد `AlreadyMaterialized` بلا أي كتابة (idempotent §F.6).
 * - **(ب) حفظ الملكية:** معلومة أرحام قائمة (بما فيها `lastContactedAt`) تبقى حرفاً —
 *   التصنيف وحده يُوحَّد (§F.5 — AC-8).
 * - **(ج) الإسقاط:** غير المطابق يُحسب إسقاطاً بلا صف وهمي؛ والدفتر غير الجاهز
 *   إسقاطٌ بلا علامة (§F.2/§F.7 — حماية من الفقد الدائم).
 * - **(د) الفشل الذرّي عبر المستودع:** فشل المعاملة ينتشر والخام يبقى غير مرفوع —
 *   وإعادة المحاولة تكمل (§F.8/§F.9).
 * لا اختبارات وهمية للطريقة هنا — الحقيقة تُقرأ من قاعدة Room حية ومن تدفق DataStore.
 */
class TopFiveMaterializationRepositoryImplTest {

    private val testDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    private fun contact(
        systemContactId: String,
        displayName: String,
        arham: ArhamInfo? = null,
    ) = Contact(
        id = 0L,
        systemContactId = systemContactId,
        name = ContactName(displayName = displayName),
        phones = listOf(
            app.sila.core.model.Phone(raw = "01000000000", e164 = "+201000000000", isPrimary = true),
        ),
        category = Category.WORK,
        arham = arham,
        normalizedName = displayName,
    )

    private fun selection(systemContactId: String, displayName: String) = TopFiveSelection(
        systemContactId = systemContactId,
        displayName = displayName,
        relation = Relation.UNCLE_PATERNAL,
        frequency = ArhamFrequency.WEEKLY,
    )

    /** (أ) التشغيل الناجح ثم الإعادة: لا كتابة ثانية ولا انحراف (idempotent — §F.6). */
    @Test
    fun endToEnd_replayRun_alreadyMaterialized_stateUnchanged() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        val prefs = SilaPreferencesDataSource()
        try {
            prefs.setOnboardingTopFiveSelections(
                listOf(selection("sys_mother", "الأم فاطمة"), selection("sys_uncle", "العم خالد")),
            )
            db.upsertContact(contact("sys_mother", "الأم فاطمة"))
            db.upsertContact(contact("sys_uncle", "العم خالد"))
            val repository = TopFiveMaterializationRepositoryImpl(dao = db, userPreferences = prefs)
            val useCase = MaterializeTopFiveUseCase(repository)

            val first = useCase()
            val firstCompleted = assertIs<TopFiveMaterializationOutcome.Completed>(first)
            assertEquals(2, firstCompleted.applied, "§F.4: كلا المطابق مُوِّد")
            assertTrue(prefs.preferences.first().topFiveMaterialized, "§F.9: العلامة بعد نجاح المعاملة")
            val afterFirst = db.observeArhamContacts().first().associate { it.id to it.arham }

            // F.11-أ: الإعادة فوراً وبعد تغيّر القائمة — بلا أي أثر كتابة.
            val replay = useCase()
            assertIs<TopFiveMaterializationOutcome.AlreadyMaterialized>(replay)
            val afterReplay = db.observeArhamContacts().first().associate { it.id to it.arham }
            assertEquals(afterFirst, afterReplay, "الإعادة بلا أي كتابة (no overwrite — §F.6)")
            assertTrue(
                db.observeArhamContacts().first().all {
                    it.category == Category.FAMILY && it.arham?.relation == Relation.UNCLE_PATERNAL
                },
            )
        } finally {
            db.closeConnection()
        }
    }

    /** (ب) معلومة المستخدم القائمة تكسب — التصنيف وحده يُوحَّد (§F.5 — AC-8). */
    @Test
    fun endToEnd_preservation_existingArhamWins_lastContactedUntouched() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        val prefs = SilaPreferencesDataSource()
        try {
            val lastContacted = 1_790_000_000_000L
            prefs.setOnboardingTopFiveSelections(listOf(selection("sys_grandpa", "الجد عبده")))
            db.upsertContact(
                contact(
                    "sys_grandpa",
                    "الجد عبده",
                    arham = ArhamInfo(
                        relation = Relation.AUNT_MATERNAL,
                        frequency = ArhamFrequency.DAILY,
                        lastContactedAt = lastContacted,
                    ),
                ),
            )
            val repository = TopFiveMaterializationRepositoryImpl(dao = db, userPreferences = prefs)

            val outcome = MaterializeTopFiveUseCase(repository)()

            val completed = assertIs<TopFiveMaterializationOutcome.Completed>(outcome)
            assertEquals(0, completed.applied, "لا استبدال لمعلومة المستخدم (§F.5)")
            assertEquals(1, completed.preservedUserOwned, "التصنيف وحده وُحِّد")
            val arham = assertNotNull(
                db.observeArhamContacts().first().single().arham,
                "صف أرحام قائم",
            )
            assertEquals(Relation.AUNT_MATERNAL, arham.relation, "العلاقة الأصلية باقية")
            assertEquals(ArhamFrequency.DAILY, arham.frequency, "الوتيرة الأصلية باقية")
            assertEquals(lastContacted, arham.lastContactedAt, "AC-8: آخر تواصل لا يُلمس إطلاقاً")
            assertEquals(Category.FAMILY, db.observeArhamContacts().first().single().category)
        } finally {
            db.closeConnection()
        }
    }

    /** (ج) الإسقاط: بلا صفوف وهمية، والدفتر غير الجاهز بلا إسقاط دائم (§F.2/§F.7). */
    @Test
    fun endToEnd_notReadyThenPartialSync_droppedNoPhantom_markerOnlyAfterApply() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        val prefs = SilaPreferencesDataSource()
        try {
            prefs.setOnboardingTopFiveSelections(
                listOf(selection("sys_synced", "المتزامنة"), selection("sys_absent", "غير المُزامنة")),
            )
            val repository = TopFiveMaterializationRepositoryImpl(dao = db, userPreferences = prefs)
            val useCase = MaterializeTopFiveUseCase(repository)

            // الدفتر غير الجاهز: بلا إسقاط وبلا علامة — يعاد المحاولة (حماية §F.2).
            assertIs<TopFiveMaterializationOutcome.ContactsNotReady>(useCase())
            assertTrue(!prefs.preferences.first().topFiveMaterialized, "لا علامة على فشل جاهزي")

            // المزامنة تدخل جهة واحدة فقط:
            db.upsertContact(contact("sys_synced", "المتزامنة"))
            val outcome = useCase()

            val completed = assertIs<TopFiveMaterializationOutcome.Completed>(outcome)
            assertEquals(1, completed.applied)
            assertEquals(1, completed.dropped, "§F.7: غير المُزامنة إسقاط محسوب — بلا صف وهمي")
            assertEquals(1, db.observeArhamContacts().first().size, "لا صف وهمي للإسقاط")
            assertTrue(prefs.preferences.first().topFiveMaterialized)
        } finally {
            db.closeConnection()
        }
    }

    /**
     * (د) فشل ذرّي عبر حدود المستودع: عطل مُحقَن مرة واحدة في حدود DAO ينتشر بلا ابتلاع،
     * والخام (DataStore حقيقي) يبقى غير مرفوع (§F.9) — والإعادة على القاعدة الحية تكمل
     * idempotently (§F.6). إثبات التراجع الفيزيائي نفسه (صفوف/كاش) في اختبارات
     * `:core:database` (ArhamMaterializationTest) حيث خطاف الفشل الداخلي — سبق T8-03.
     */
    @Test
    fun endToEnd_batchFailureThroughRepository_markerUnset_retryCompletes() = runTest {
        val db = LocalSilaDatabase(dispatchers = testDispatchers)
        val prefs = SilaPreferencesDataSource()
        try {
            prefs.setOnboardingTopFiveSelections(listOf(selection("sys_one", "العم أيمن")))
            db.upsertContact(contact("sys_one", "العم أيمن"))
            var failNextApply = true
            val failingOnceDao = object : app.sila.core.database.dao.TopFiveMaterializationDao by db {
                override suspend fun applyTopFiveMaterialization(
                    updates: List<app.sila.core.database.dao.TopFiveMaterializationUpdate>,
                ): app.sila.core.database.dao.TopFiveMaterializationWriteResult {
                    if (failNextApply) {
                        failNextApply = false
                        throw IllegalStateException("فشل معاملة مُحاكى (§F.8)")
                    }
                    return db.applyTopFiveMaterialization(updates)
                }
            }
            val repository = TopFiveMaterializationRepositoryImpl(dao = failingOnceDao, userPreferences = prefs)
            val useCase = MaterializeTopFiveUseCase(repository)

            assertFailsWith<IllegalStateException> { useCase() }
            assertTrue(!prefs.preferences.first().topFiveMaterialized, "الخام لا يُرفع على فشل المعاملة (§F.9)")
            assertEquals(0, db.observeArhamContacts().first().size, "لا تجسيد جزئي وصل القاعدة")

            val outcome = useCase()
            val completed = assertIs<TopFiveMaterializationOutcome.Completed>(outcome)
            assertEquals(1, completed.applied, "§F.6: إعادة التشغيل تكمل idempotently")
            assertTrue(prefs.preferences.first().topFiveMaterialized)
        } finally {
            db.closeConnection()
        }
    }
}
