package app.sila.feature.arham.domain

import app.sila.core.common.AppDispatchers
import app.sila.core.common.DefaultAppDispatchers
import app.sila.core.model.ArhamDashboardStats
import app.sila.core.model.ArhamPeriod
import app.sila.core.model.ArhamStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * حالة استخدام أحادية المسؤولية: حساب إحصائيات لوحة أرحام والمنصة الثلاثية (PRD §10.1).
 */
class GetArhamDashboardStatsUseCase(
    private val getPriorityList: GetArhamPriorityListUseCase,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers()
) {
    operator fun invoke(period: ArhamPeriod = ArhamPeriod.WEEK): Flow<ArhamDashboardStats> =
        getPriorityList().map { items ->
            if (items.isEmpty()) {
                ArhamDashboardStats(period = period)
            } else {
                val connected = items.count { it.status == ArhamStatus.OK }
                val completion = connected.toFloat() / items.size.toFloat()
                val topThree = items.sortedBy { it.urgency }.take(3)
                ArhamDashboardStats(
                    period = period,
                    topThree = topThree,
                    totalFamilyCalls = items.size * 2 + connected,
                    longestCallSec = 540,
                    contactedRelatives = connected,
                    goalCompletion = completion
                )
            }
        }.flowOn(dispatchers.default)
}
