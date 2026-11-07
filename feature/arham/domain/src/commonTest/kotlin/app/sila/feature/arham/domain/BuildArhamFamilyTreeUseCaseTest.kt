package app.sila.feature.arham.domain

import app.sila.core.common.AppClock
import app.sila.core.common.AppDispatchers
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.ArhamStatus
import app.sila.core.model.KinshipLayer
import app.sila.core.model.Relation
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant

/**
 * اختبارات بناء شجرة العائلة (T8-07 · AC-13): التوليدات على طبقات الصلة بترتيبها
 * القانوني (الأسلاف أولاً)، والوصلات (ابن←والد) للأبوين الموجود حصراً، مع أمان
 * اليتيم والرابط الذاتي والحلقات (لا مشىً على السلسلة — لا تعليق مطلقاً).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BuildArhamFamilyTreeUseCaseTest {

    private class FakeArhamRepository : ArhamRepository {
        var contacts: List<Contact> = emptyList()
        override fun observeArhamContacts(): Flow<List<Contact>> = MutableStateFlow(contacts)
        override suspend fun upsertArhamInfo(contactId: Long, info: ArhamInfo) = Unit
    }

    private val unconfined = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }

    private val fixedClock = object : AppClock {
        override fun now(): Instant = Instant.parse("2026-02-27T10:00:00Z")
    }

    private fun contact(
        id: Long,
        name: String,
        relation: Relation,
        layer: KinshipLayer? = null,
        parentContactId: Long? = null,
    ) = Contact(
        id = id,
        systemContactId = "sys-$id",
        name = ContactName(displayName = name),
        arham = ArhamInfo(
            relation = relation,
            layer = layer ?: relation.layer,
            parentContactId = parentContactId,
            frequency = ArhamFrequency.WEEKLY,
        ),
    )

    private fun useCase(repo: FakeArhamRepository) =
        BuildArhamFamilyTreeUseCase(repository = repo, clock = fixedClock, dispatchers = unconfined)

    @Test
    fun generations_groupByLayer_inCanonicalOrder_nonEmptyOnly() = runTest {
        val repo = FakeArhamRepository().apply {
            contacts = listOf(
                contact(1, "الجد", Relation.GRANDFATHER),                    // ANCESTOR
                contact(2, "الأخ", Relation.BROTHER, parentContactId = 1),   // SELF_BRANCH
                contact(3, "العم", Relation.UNCLE_PATERNAL),                 // COLLATERAL_PATERNAL
                contact(4, "الخالة", Relation.AUNT_MATERNAL),                // COLLATERAL_MATERNAL
                contact(5, "الحمو", Relation.IN_LAW),                        // SPOUSE_SIDE
                contact(6, "الصديق", Relation.CLOSE_FRIEND),                 // OTHER
            )
        }
        val tree = useCase(repo)().first()
        assertEquals(
            listOf(
                KinshipLayer.ANCESTOR,
                KinshipLayer.SELF_BRANCH,
                KinshipLayer.COLLATERAL_PATERNAL,
                KinshipLayer.COLLATERAL_MATERNAL,
                KinshipLayer.SPOUSE_SIDE,
                KinshipLayer.OTHER,
            ),
            tree.generations.map { it.layer },
        )
        assertEquals(listOf(1L), tree.generations.first().nodes.map { it.contactId })
    }

    @Test
    fun edges_builtForPresentParents_danglingAndSelfDropped() = runTest {
        val repo = FakeArhamRepository().apply {
            contacts = listOf(
                contact(1, "الأب", Relation.FATHER),
                contact(2, "الابن", Relation.SON, parentContactId = 1),
                // ابنٌ أبوه غير موجود في الشجرة (يتيم) — العقدة تبقى ووصلتها تُهمَل
                contact(3, "ابن يتيم", Relation.SON, parentContactId = 77L),
                // رابط ذاتي — يُهمَل
                contact(4, "ذاتي", Relation.CUSTOM, layer = KinshipLayer.OTHER, parentContactId = 4),
            )
        }
        val tree = useCase(repo)().first()
        assertEquals(listOf(ArhamTreeEdge(childContactId = 2L, parentContactId = 1L)), tree.edges)
        // اليتيم والذاتي في توليداتهما رغم إهمال الوصلة
        assertTrue(tree.generations.flatMap { it.nodes }.any { it.contactId == 3L })
        assertTrue(tree.generations.flatMap { it.nodes }.any { it.contactId == 4L })
    }

    @Test
    fun cycle_betweenTwoNodes_doesNotHang_andNodesStillPlacedByLayer() = runTest {
        val repo = FakeArhamRepository().apply {
            // حلقة أفرادها من نفس الطبقة: 5 أبُ 6 وأبُ 6 هو 5 — البناء بالطبقات لا يمشي
            contacts = listOf(
                contact(5, "دائري أول", Relation.CUSTOM, layer = KinshipLayer.OTHER, parentContactId = 6),
                contact(6, "دائري ثان", Relation.CUSTOM, layer = KinshipLayer.OTHER, parentContactId = 5),
            )
        }
        val tree = useCase(repo)().first()
        assertEquals(1, tree.generations.size)
        assertEquals(KinshipLayer.OTHER, tree.generations.first().layer)
        assertEquals(listOf(5L, 6L), tree.generations.first().nodes.map { it.contactId })
        assertEquals(2, tree.edges.size) // الوصلات بيانات رسم — الحلقة لا تعطّل البناء
    }

    @Test
    fun nodes_sortedDeterministically_withinGeneration() = runTest {
        val repo = FakeArhamRepository().apply {
            contacts = listOf(
                contact(9, "ب", Relation.BROTHER),
                contact(7, "ب", Relation.SISTER), // نفس الاسم — التسوية بالمعرف
                contact(8, "أ", Relation.BROTHER),
            )
        }
        val tree = useCase(repo)().first()
        assertEquals(listOf(8L, 7L, 9L), tree.generations.first().nodes.map { it.contactId })
    }

    @Test
    fun statusRing_derivedFromUrgencyAtBuildTime() = runTest {
        val repo = FakeArhamRepository().apply {
            contacts = listOf(contact(1, "متأخر أسبوعياً", Relation.BROTHER)) // بلا تواصل → أحمر
        }
        val node = useCase(repo)().first().generations.first().nodes.single()
        assertEquals(ArhamStatus.OVERDUE, node.status)
        assertEquals(null, node.daysSinceLastContact)
    }
}
