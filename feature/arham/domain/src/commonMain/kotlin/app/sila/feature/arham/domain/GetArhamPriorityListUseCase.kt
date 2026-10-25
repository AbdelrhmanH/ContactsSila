package app.sila.feature.arham.domain

import app.sila.core.common.AppClock
import app.sila.core.common.AppDispatchers
import app.sila.core.common.DefaultAppDispatchers
import app.sila.core.model.ArhamItem
import app.sila.core.model.calculateUrgency
import app.sila.core.model.toArhamStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * حالة استخدام أحادية المسؤولية: جلب قائمة أولويات أرحام مرتبة تنازلياً حسب Urgency (PRD §10.2).
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
            val urgency = info.calculateUrgency(now)
            val daysSince = info.lastContactedAt?.let { last ->
                ((nowMs - last).coerceAtLeast(0L) / MILLIS_PER_DAY).toInt()
            }
            ArhamItem(
                rank = 0,
                contactId = contact.id,
                displayName = contact.name.displayName,
                photoUri = contact.photoUri,
                relation = info.relation,
                frequency = info.frequency,
                daysSinceLastContact = daysSince,
                urgency = urgency,
                status = urgency.toArhamStatus(),
                primaryPhone = contact.phones.firstOrNull()?.e164 ?: ""
            )
        }
            .sortedByDescending { it.urgency }
            .mapIndexed { index, item -> item.copy(rank = index + 1) }
    }.flowOn(dispatchers.default)
}
