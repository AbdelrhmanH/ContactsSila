package app.sila.feature.arham.domain

import app.sila.core.model.ArhamDashboardStats
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamItem
import app.sila.core.model.ArhamStatus
import app.sila.core.model.Relation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * اختبارات لقطة الودجت (T8-10 · AC-15 · T807-R1 · Issue #29) — تركيب صافٍ من مخرجات
 * T8-07: العدد الأحمر، أعلى-3 (بلا إعادة فرز)، ونسبة الهدف **مقيَّدة 0..1** (التقييد
 * العرضي — قرار الخطة الأصلي `>1f` موثق بلا مساس).
 */
class ArhamWidgetSnapshotTest {

    private fun item(
        id: Long,
        status: ArhamStatus,
        name: String = "قريب $id",
    ) = ArhamItem(
        rank = 0,
        contactId = id,
        displayName = name,
        relation = Relation.GRANDFATHER,
        frequency = ArhamFrequency.WEEKLY,
        daysSinceLastContact = 9,
        urgency = 1.2,
        status = status,
        primaryPhone = "+2010000000$id",
    )

    private val stats = ArhamDashboardStats(
        totalFamilyCalls = 12,
        longestCallSec = 340,
        goalCompletion = 0.75f,
    )

    @Test
    fun overdueCount_countsFullList_notOnlyTopThree() {
        val items = listOf(
            item(1, ArhamStatus.OVERDUE),
            item(2, ArhamStatus.OVERDUE),
            item(3, ArhamStatus.OK),
            item(4, ArhamStatus.OVERDUE),
        )
        val snapshot = buildArhamWidgetSnapshot(items = items, stats = stats)
        assertEquals(3, snapshot.overdueCount) // الرابع خارج أعلى-3 لكن محسوب
    }

    @Test
    fun entries_takeTopThree_preservingUrgencyOrder() {
        val items = listOf(item(1, ArhamStatus.OVERDUE), item(2, ArhamStatus.SOON), item(3, ArhamStatus.OK), item(4, ArhamStatus.OK))
        val snapshot = buildArhamWidgetSnapshot(items = items, stats = stats)
        assertEquals(listOf(1L, 2L, 3L), snapshot.entries.map { it.contactId })
        // phoneE164 أُسقط من اللقطة (مراجعة T8-10 · P3-2): الرقم يُستعاد من Room لحظة
        // النقر عبر contactId حصراً — اللقطة تحمل أقل هوية للعرض (بلا PII إضافي).
    }

    @Test
    fun goalCompletion_isClampedToUnitRange_T807R1() {
        val over = buildArhamWidgetSnapshot(
            items = emptyList(),
            stats = stats.copy(goalCompletion = 1.8f),
        )
        assertEquals(1f, over.goalCompletionFraction)

        val under = buildArhamWidgetSnapshot(
            items = emptyList(),
            stats = stats.copy(goalCompletion = -0.2f),
        )
        assertEquals(0f, under.goalCompletionFraction)

        val inside = buildArhamWidgetSnapshot(items = emptyList(), stats = stats)
        assertEquals(0.75f, inside.goalCompletionFraction)
    }

    @Test
    fun statsPassThrough() {
        val snapshot = buildArhamWidgetSnapshot(items = emptyList(), stats = stats)
        assertEquals(12, snapshot.totalFamilyCalls)
        assertEquals(340, snapshot.longestCallSec)
    }
}
