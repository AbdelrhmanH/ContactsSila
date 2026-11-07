package app.sila.feature.arham.domain

import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Relation
import app.sila.core.model.TopFiveSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * اختبارات حالة استخدام تجسيد «أهم 5» فوق مستودع وهمي (T8-04 · §F · Dependency Inversion):
 * - حارس العلامة الواحدة أولاً (F.11-هـ) — وربح تسلسل «تطبيق ثم علامة» (§F.9).
 * - لا شيء معلّقاً → لا كتابة ولا علامة؛ والدفتر غير الجاهز → بلا إسقاط دائم (§F.2/§F.6).
 * - المطابقة هوية النظام حصراً — الاسم للعرض فقط (§F.3)؛ تكرار الاختيار ينهار (idempotent §F.6).
 * - الإسقاط يُحسب ولا يُختلق (§F.7)؛ الفشل ينتشر والخام يبقى مرفوعاً (§F.8/§F.9).
 * حفظ الملكية (§F.5) سلوك طبقة الحقيقة — مُختبر فوق قاعدة حقيقية في
 * `:core:database` (ArhamMaterializationTest) و`:feature:arham:data` حصراً.
 */
class MaterializeTopFiveUseCaseTest {

    /** مستودع وهمي يسجّل تسلسل الاستدعاءات وقابليته لحقن العطل (قابلية تمييز الانحدار). */
    private class FakeTopFiveMaterializationRepository : TopFiveMaterializationRepository {
        var materialized: Boolean = false
        var selections: List<TopFiveSelection> = emptyList()
        var liveContacts: List<Contact> = emptyList()
        var writeCounts: TopFiveMaterializationWriteCounts = TopFiveMaterializationWriteCounts(0, 0, 0)
        var applyFault: Throwable? = null

        val callLog = mutableListOf<String>()
        var isMaterializedReads = 0
        var markCount = 0
        val appliedTargets = mutableListOf<TopFiveMaterializationTarget>()

        override suspend fun isMaterialized(): Boolean {
            isMaterializedReads++
            callLog += "read"
            return materialized
        }

        override suspend fun markMaterialized() {
            callLog += "mark"
            markCount++
            materialized = true
        }

        override suspend fun pendingSelections(): List<TopFiveSelection> {
            callLog += "pending"
            return selections
        }

        override suspend fun hasLiveContacts(): Boolean {
            callLog += "ready"
            return liveContacts.isNotEmpty()
        }

        override suspend fun findContactsBySystemContactIds(systemContactIds: Set<String>): List<Contact> {
            callLog += "find"
            return liveContacts.filter { it.systemContactId in systemContactIds }
        }

        override suspend fun applyMaterialization(
            targets: List<TopFiveMaterializationTarget>,
        ): TopFiveMaterializationWriteCounts {
            callLog += "apply"
            applyFault?.let { fault -> throw fault }
            appliedTargets += targets
            return writeCounts
        }
    }

    private fun contact(id: Long, systemContactId: String) = Contact(
        id = id,
        systemContactId = systemContactId,
        name = ContactName(displayName = "جهة $id"),
    )

    private fun selection(
        systemContactId: String,
        displayName: String,
        relation: Relation = Relation.UNCLE_PATERNAL,
        frequency: ArhamFrequency = ArhamFrequency.WEEKLY,
    ) = TopFiveSelection(
        systemContactId = systemContactId,
        displayName = displayName,
        relation = relation,
        frequency = frequency,
    )

    @Test
    fun invoke_alreadyMaterialized_noFurtherReadsOrWrites() = runTest {
        val repository = FakeTopFiveMaterializationRepository().apply { materialized = true }
        val useCase = MaterializeTopFiveUseCase(repository)

        val outcome = useCase()

        assertIs<TopFiveMaterializationOutcome.AlreadyMaterialized>(outcome)
        assertEquals(1, repository.isMaterializedReads, "قراءة العلامة وحدها")
        assertEquals(0, repository.markCount, "لا رفع ثانٍ للخام (one-shot §F.10)")
        assertTrue(repository.appliedTargets.isEmpty(), "لا كتابة بعد اكتمال التشغيل الأول")
    }

    @Test
    fun invoke_emptySelections_nothingPending_markerNotWritten() = runTest {
        val repository = FakeTopFiveMaterializationRepository()
        val useCase = MaterializeTopFiveUseCase(repository)

        val outcome = useCase()

        assertIs<TopFiveMaterializationOutcome.NothingPending>(outcome)
        assertEquals(0, repository.markCount, "العلامة لا تُرفع بلا تجسيد فعلي (§F.6 — إعادة الاختيار تكتب القائمة أولاً)")
        assertTrue(repository.appliedTargets.isEmpty())
    }

    @Test
    fun invoke_contactsNotReady_noDropNoMarker_noPhantom() = runTest {
        val repository = FakeTopFiveMaterializationRepository().apply {
            selections = listOf(selection("sys_one", "العم أيمن"))
        }
        val useCase = MaterializeTopFiveUseCase(repository)

        val outcome = useCase()

        assertIs<TopFiveMaterializationOutcome.ContactsNotReady>(outcome)
        assertEquals(
            listOf("read", "pending", "ready"),
            repository.callLog,
            "§F.2: الفشل الجاهزي لا يصير إسقاطاً دائماً بموجب §F.10 — لا find ولا apply ولا mark",
        )
        assertTrue(repository.appliedTargets.isEmpty(), "بلا كتابة قبل جاهزية الدفتر")
    }

    @Test
    fun invoke_happyPath_appliesThenMarks_thenAlreadyMaterialized() = runTest {
        val repository = FakeTopFiveMaterializationRepository().apply {
            selections = listOf(selection("sys_one", "العم أيمن"))
            liveContacts = listOf(contact(id = 7L, systemContactId = "sys_one"))
            writeCounts = TopFiveMaterializationWriteCounts(applied = 1, preservedUserOwned = 0, skippedMissing = 0)
        }
        val useCase = MaterializeTopFiveUseCase(repository)

        val outcome = useCase()

        val completed = assertIs<TopFiveMaterializationOutcome.Completed>(outcome)
        assertEquals(1, completed.applied)
        assertEquals(0, completed.dropped)
        assertEquals(
            listOf("read", "pending", "ready", "find", "apply", "mark"),
            repository.callLog,
            "§F.9: العلامة تُرفع بعد نجاح المعاملة حصراً (تطبيق ← علامة)",
        )
        assertEquals(
            TopFiveMaterializationTarget(
                contactId = 7L,
                arham = ArhamInfo(relation = Relation.UNCLE_PATERNAL, frequency = ArhamFrequency.WEEKLY),
            ),
            repository.appliedTargets.single(),
            "§F.4: الهدف على معرف القاعدة ومعلومة من الاختيار",
        )

        // §F.10: التشغيل التالي مكتمل — بلا أي كتابة.
        assertIs<TopFiveMaterializationOutcome.AlreadyMaterialized>(useCase())
        assertEquals(1, repository.markCount)
    }

    @Test
    fun invoke_matchesBySystemContactIdOnly_displayNameNeverAKey() = runTest {
        val repository = FakeTopFiveMaterializationRepository().apply {
            // اسم الاختيار يخالف اسم الجهة تماماً — الهوية النظامية وحدها أساس المطابقة (§F.3).
            selections = listOf(selection("sys_one", "اسم لا يشبه جهة الاتصال إطلاقاً"))
            liveContacts = listOf(contact(id = 3L, systemContactId = "sys_one"))
            writeCounts = TopFiveMaterializationWriteCounts(applied = 1, preservedUserOwned = 0, skippedMissing = 0)
        }

        val outcome = MaterializeTopFiveUseCase(repository)()

        val completed = assertIs<TopFiveMaterializationOutcome.Completed>(outcome)
        assertEquals(1, completed.applied, "المطابقة بالهوية حصراً — الاسم للعرض فقط")
        assertEquals(3L, repository.appliedTargets.single().contactId, "الهوية النظامية وحدها قررت المطابقة")
        assertEquals(0, completed.dropped)
    }

    @Test
    fun invoke_duplicateSystemContactIdInSource_lowestIdWins_deterministically() = runTest {
        val repository = FakeTopFiveMaterializationRepository().apply {
            selections = listOf(selection("sys_dup", "مكررة"))
            liveContacts = listOf(
                contact(id = 12L, systemContactId = "sys_dup"),
                contact(id = 5L, systemContactId = "sys_dup"),
            )
            writeCounts = TopFiveMaterializationWriteCounts(applied = 1, preservedUserOwned = 0, skippedMissing = 0)
        }

        val outcome = MaterializeTopFiveUseCase(repository)()

        val completed = assertIs<TopFiveMaterializationOutcome.Completed>(outcome)
        assertEquals(1, completed.applied, "تكرار الهوية في الدفتر يُحسم حتماً (الأصغر معرفاً)")
        assertEquals(5L, repository.appliedTargets.single().contactId)
    }

    @Test
    fun invoke_duplicateSelections_deduped_beforeMatching() = runTest {
        val repository = FakeTopFiveMaterializationRepository().apply {
            selections = listOf(
                selection("sys_one", "الاختيار الأول"),
                selection("sys_one", "إعادة اختيار بنفس الهوية واسم مغاير"),
            )
            liveContacts = listOf(contact(id = 9L, systemContactId = "sys_one"))
            writeCounts = TopFiveMaterializationWriteCounts(applied = 1, preservedUserOwned = 0, skippedMissing = 0)
        }

        val outcome = MaterializeTopFiveUseCase(repository)()

        val completed = assertIs<TopFiveMaterializationOutcome.Completed>(outcome)
        assertEquals(1, completed.applied, "idempotent §F.6: الاختيار المكرر ينهار قبل القراءة")
        assertEquals(1, repository.appliedTargets.size)
        assertEquals(0, completed.dropped)
    }

    @Test
    fun invoke_unmatchedAndSkippedMerged_intoDroppedCount() = runTest {
        val repository = FakeTopFiveMaterializationRepository().apply {
            selections = listOf(
                selection("sys_matched", "مُجسَّدة"),
                selection("sys_absent", "غير مُزامنة بعد"),
                selection("sys_absent2", "حُذفت من الدفتر"),
            )
            liveContacts = listOf(contact(id = 4L, systemContactId = "sys_matched"))
            // ما بعد التسابق: تخلّف وسطي واحد يُضاف إلى إسقاطات القرار (§F.7).
            writeCounts = TopFiveMaterializationWriteCounts(applied = 1, preservedUserOwned = 0, skippedMissing = 1)
        }

        val outcome = MaterializeTopFiveUseCase(repository)()

        val completed = assertIs<TopFiveMaterializationOutcome.Completed>(outcome)
        assertEquals(1, completed.applied)
        assertEquals(3, completed.dropped, "§F.7: إسقاطا القرار + المتخلّف وسطياً — بلا طابور إعادة")
    }

    @Test
    fun invoke_applyFailure_propagates_markerUnset_retryCompletes() = runTest {
        val repository = FakeTopFiveMaterializationRepository().apply {
            selections = listOf(selection("sys_one", "العم أيمن"))
            liveContacts = listOf(contact(id = 2L, systemContactId = "sys_one"))
        }
        val useCase = MaterializeTopFiveUseCase(repository)

        repository.applyFault = IllegalStateException("فشل معاملة مُحاكى (§F.8)")
        assertFailsWith<IllegalStateException> { useCase() }
        assertEquals(0, repository.markCount, "الخام لا يُرفع على فشل أبداً (§F.9)")
        assertTrue(!repository.materialized)

        // §F.6: إعادة التشغيل بعد زوال العطل تكمل بنفس القرارات (idempotent).
        repository.applyFault = null
        val outcome = useCase()
        assertIs<TopFiveMaterializationOutcome.Completed>(outcome)
        assertEquals(1, repository.markCount)
    }
}
