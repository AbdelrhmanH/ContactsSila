package app.sila.feature.arham.domain

import kotlinx.coroutines.flow.first

/**
 * مزوِّد لقطة الودجت (T8-10 · AC-15 · Issue #29) — تركيبة واحدة من مصدرَي T8-07
 * (القائمة المرتبة + إحصاءات اللوحة)، قيم أولى حصراً: الودجت لقطةٌ لحظية يُنعشها
 * الـ worker (D3) — بلا اشتراك حيّ (Glance يعيد التركيب عند كل تحديث).
 */
class GetArhamWidgetSnapshotUseCase(
    private val getPriorityList: GetArhamPriorityListUseCase,
    private val getDashboardStats: GetArhamDashboardStatsUseCase,
) {

    suspend operator fun invoke(): ArhamWidgetSnapshot {
        val items = getPriorityList().first()
        val stats = getDashboardStats().first()
        return buildArhamWidgetSnapshot(items = items, stats = stats)
    }
}
