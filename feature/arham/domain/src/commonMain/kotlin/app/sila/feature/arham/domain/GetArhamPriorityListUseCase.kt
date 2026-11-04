package app.sila.feature.arham.domain

import app.sila.core.common.AppClock
import app.sila.core.common.AppDispatchers
import app.sila.core.common.DefaultAppDispatchers
import app.sila.core.model.ArhamItem
import app.sila.core.model.rankable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * حالة استخدام أحادية المسؤولية: جلب قائمة أولويات أرحام مرتبة تنازلياً حسب Urgency (PRD §10.2).
 *
 * استبعاد OCCASIONS (AC-2 — خطة §P-T8-05): جهات التكرار «ب المناسبات» لا تدخل الترتيب
 * حصراً — إنما تظهر عبر بطاقة/تنبيه المناسبة قبل موعدها (`ObserveUpcomingOccasionsUseCase`)
 * وفق المواصفة §القائمة المرقمة وD1 المصادَق (اقتضائية: urgency=0.0 لها دوماً، فلا معنى لترتيبها).
 * القاعدة موحَّدة في `ArhamFrequency.rankable` (توحيد O-7 — T8-07).
 *
 * إخفاء الموقوفين بعدد (AC-2 «موقوف بعدد» — مؤجل من T8-05 إلى T8-07): جهة `pausedUntil`
 * في المستقبل تخرج من القائمة حصراً (بلا فراغ ترقيم بعد إعادة الترقيم)؛ عددها يظهر في
 * `ArhamDashboardStats.pausedCount` عبر `GetArhamDashboardStatsUseCase`.
 */
class GetArhamPriorityListUseCase(
    private val repository: ArhamRepository,
    private val clock: AppClock,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers()
) {
    operator fun invoke(): Flow<List<ArhamItem>> = repository.observeArhamContacts().map { contacts ->
        val now = clock.now()
        val nowMs = now.toEpochMilliseconds()

        contacts.mapNotNull { contact ->
            val info = contact.arham ?: return@mapNotNull null
            // الموقوفة حالياً مخفية حصراً (AC-2) — لا تُرتب ولا تُعرض حتى انتهاء التوقيف.
            val paused = info.pausedUntil
            if (paused != null && paused > nowMs) return@mapNotNull null
            contact.toArhamItem(now)
        }
            .filter { it.frequency.rankable }
            .sortedByDescending { it.urgency }
            .mapIndexed { index, item -> item.copy(rank = index + 1) }
    }.flowOn(dispatchers.default)
}
