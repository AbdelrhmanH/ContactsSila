package app.sila.feature.arham.data

import app.sila.core.database.dao.ArhamDao
import app.sila.core.database.dao.LastContactedUpdate
import app.sila.core.model.CallEvent
import app.sila.feature.arham.domain.ArhamCallEventRepository
import app.sila.feature.arham.domain.CallEventStateMachine

/**
 * تنفيذ مستودع أحداث المكالمات (طبقة Data داخل :feature:arham:data — T8-03 · Issue #29).
 *
 * يعمل محلياً 100% فوق [ArhamDao] ولا يتصل بالشبكة أبداً (ADR-0007).
 *
 * **الكتابة المركّبة الذرية (قيود F-2 من مراجعة T8-01):** مسارات النطاق
 * (`IngestCallEventUseCase`/`RecordManualContactUseCase`/`ResolveConfirmationUseCase`)
 * تستدعي `upsertEvent` ثم `updateLastContacted` كعضوين مستقلين في العقد؛ هذا التنفيذ
 * يقيّم قاعدة AC-8 عبر [CallEventStateMachine.updatesLastContacted] — **آلة الحالات
 * نفسها** مصدر القاعدة الوحيد، بلا إعادة كتابتها هنا — ويمرّر التحديث المؤهَّل إلى
 * `ArhamDao.upsertCallEvent` لينفَّذ الحدث والتحديث داخل **معاملة IMMEDIATE واحدة**،
 * فأي فشل يُرجِع الاثنين معاً. الاستدعاء اللاحق من النطاق لـ [updateLastContacted]
 * يبقى idempotent بلا أثر دلالي (`max` لا تنقص قيمة أحدث قائماً).
 */
class ArhamCallEventRepositoryImpl(
    private val arhamDao: ArhamDao,
    private val stateMachine: CallEventStateMachine = CallEventStateMachine(),
) : ArhamCallEventRepository {

    override suspend fun getEvent(eventId: String): CallEvent? = arhamDao.getCallEventById(eventId)

    override suspend fun findEventsBetween(fromMillis: Long, toMillis: Long): List<CallEvent> =
        arhamDao.findCallEventsBetween(fromMillis = fromMillis, toMillis = toMillis)

    override suspend fun upsertEvent(event: CallEvent) {
        arhamDao.upsertCallEvent(event, lastContactedUpdate = lastContactedUpdateFor(event))
    }

    override suspend fun updateLastContacted(contactId: Long, timestampMillis: Long) {
        arhamDao.updateLastContacted(contactId = contactId, timestampMillis = timestampMillis)
    }

    /** تقييم قاعدة ADR-0006 §6 / AC-8 عبر آلة الحالات — `null` = لا تحديث مصاحب. */
    private fun lastContactedUpdateFor(event: CallEvent): LastContactedUpdate? {
        val contactId = event.contactId ?: return null
        return if (stateMachine.updatesLastContacted(event)) {
            LastContactedUpdate(contactId = contactId, timestampMillis = event.timestamp)
        } else {
            null
        }
    }
}
