package app.sila.feature.arham.domain

import app.sila.core.model.ArhamDashboardStats
import app.sila.core.model.ArhamItem
import app.sila.core.model.ArhamStatus
import app.sila.core.model.Relation

/**
 * لقطة الودجت الصافية (T8-10 · AC-15 · Issue #29) — رموز دلالية حصراً، **كل النصوص في
 * موارد الترجمة** (T8-11/العرض). تُركَّب من مخرجات use cases الطبيعية (T8-07) — قراءة
 * Room المشتقة حصراً (D3: «Glance يعرض حالة Room المشتقة»)، بلا أي PII إضافي.
 *
 * @param overdueCount العدد الأحمر — الأقارب المتأخرون (`status == OVERDUE`).
 * @param entries أعلى 3 بالاستعجال من القائمة المرتبة (ودجت 4×2/4×4 — المستند §أرحام).
 * @param totalFamilyCalls إجمالي مكالمات العائلة في الفترة (ودجت 4×4).
 * @param longestCallSec أطول مكالمة بالثواني (ودجت 4×4).
 * @param goalCompletionFraction نسبة الهدف **مقيَّدة 0..1** (T807-R1: التقييد عرضي هنا —
 *   شريط التقدم لا يقبل قيماً خارج المدى؛ قرار الخطة الأصلي `>1f` موثق بلا مساس).
 */
data class ArhamWidgetSnapshot(
    val overdueCount: Int,
    val entries: List<ArhamWidgetEntry>,
    val totalFamilyCalls: Int,
    val longestCallSec: Int,
    val goalCompletionFraction: Float,
)

/** صف ودجت واحد — الهوية الأدنى للعرض وزر الاتصال المباشر (ids + رموز، بلا PII إضافي). */
data class ArhamWidgetEntry(
    val contactId: Long,
    val displayName: String,
    val relation: Relation,
    val daysSinceLastContact: Int?,
    val status: ArhamStatus,
)

/**
 * التركيب الصافي: العدد الأحمر من القائمة الكاملة، والأعلى-3 رؤوسها الأولى (القائمة
 * مُرتَّبة بالاستعجال أصلاً — لا إعادة فرز هنا)، والإحصاءات من لوحة T8-07.
 */
fun buildArhamWidgetSnapshot(
    items: List<ArhamItem>,
    stats: ArhamDashboardStats,
): ArhamWidgetSnapshot = ArhamWidgetSnapshot(
    overdueCount = items.count { it.status == ArhamStatus.OVERDUE },
    entries = items.take(WIDGET_ENTRY_LIMIT).map { item ->
        ArhamWidgetEntry(
            contactId = item.contactId,
            displayName = item.displayName,
            relation = item.relation,
            daysSinceLastContact = item.daysSinceLastContact,
            status = item.status,
        )
    },
    totalFamilyCalls = stats.totalFamilyCalls,
    longestCallSec = stats.longestCallSec,
    goalCompletionFraction = stats.goalCompletion.coerceIn(0f, 1f),
)

private const val WIDGET_ENTRY_LIMIT = 3
