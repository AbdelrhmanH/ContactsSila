package app.sila.feature.arham.data

import app.sila.core.database.dao.ContactDao
import app.sila.core.database.dao.EmergencySessionDao
import app.sila.core.database.entity.EmergencyRingingSessionEntity
import app.sila.core.model.Contact
import app.sila.core.model.EmergencyRingingSession
import app.sila.core.model.RingingStatus
import app.sila.feature.arham.domain.EmergencySessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * مستودع جلسات الطوارئ (T8-10 · Issue #29) — تفويض 100% لـ [EmergencySessionDao]
 * (الجدول الأدنى D4 v3 حرفياً) + القراءة اللحظية للهوية عبر [ContactDao] حصراً
 * (بلا تخزين ولا ذاكرة — ظرف PII: الاسم/الرقم يُقرآن وقت التشغيل ولا يُخزَّنان
 * في صف الجلسة قط — ADR-0006/D4).
 *
 * جهةٌ محذوفة وسط جلسة ⇒ الهوية غير قابلة للاستعادة: الجلسة تُعرض بهوية فارغة
 * («غير قابلة للعرض») — الإجحاف الفعلي قرار [RecordEmergencyAttemptUseCase]/التسوية
 * (§N.4/§N.5)؛ لا تُختلق هوية قط.
 */
class EmergencySessionRepositoryImpl(
    private val emergencySessionDao: EmergencySessionDao,
    private val contactDao: ContactDao,
) : EmergencySessionRepository {

    override suspend fun countSessionsStartedSince(cutoffMillis: Long): Int =
        emergencySessionDao.countSessionsStartedSince(cutoffMillis)

    override suspend fun findInProgressSession(): EmergencyRingingSession? =
        emergencySessionDao.findInProgressSession()?.toDomain()

    /** مراقبة الجلسة الجارية بتتبع إبطال Room — الهوية تُحدَّد لحظياً عند كل نبضة. */
    override fun observeInProgressSession(): Flow<EmergencyRingingSession?> =
        emergencySessionDao.observeInProgressSession().map { it?.toDomain() }

    override suspend fun insertInProgressSession(contactId: Long, startedAtMillis: Long): Long =
        emergencySessionDao.insertInProgressSession(contactId, startedAtMillis)

    override suspend fun recordAttempt(sessionId: Long, atMillis: Long): Int? =
        emergencySessionDao.recordAttempt(sessionId, atMillis)

    override suspend fun finishSession(sessionId: Long, status: RingingStatus, atMillis: Long): Boolean =
        emergencySessionDao.finishSession(sessionId, status, atMillis)

    override suspend fun getContactById(contactId: Long): Contact? = contactDao.getContactById(contactId)

    /** تحويل الكيان إلى نموذج النطاق مع تحديد الهوية اللحظي (جهة محذوفة ⇒ هوية فارغة). */
    private suspend fun EmergencyRingingSessionEntity.toDomain(): EmergencyRingingSession {
        val contact = contactDao.getContactById(contactId)
        return EmergencyRingingSession(
            id = id,
            contactId = contactId,
            contactName = contact?.name?.displayName.orEmpty(),
            phoneE164 = contact?.phones?.firstOrNull { it.isPrimary }?.e164
                ?: contact?.phones?.firstOrNull()?.e164.orEmpty(),
            attempts = attempts,
            status = status,
            startedAt = startedAt,
            lastAttemptAt = lastAttemptAt,
        )
    }
}
