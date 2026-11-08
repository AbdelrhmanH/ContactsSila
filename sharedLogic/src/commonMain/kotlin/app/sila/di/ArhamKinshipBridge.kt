package app.sila.di

import app.sila.core.common.AppResult
import app.sila.core.common.DataError
import app.sila.core.model.ArhamInfo
import app.sila.feature.arham.domain.ArhamRepository
import app.sila.feature.contacts.domain.ArhamKinshipLinker
import app.sila.feature.contacts.domain.KinshipDefaultFrequency
import app.sila.core.model.Relation
import kotlinx.coroutines.CancellationException

/**
 * جسر ربط القرابة في **جذر التركيب** (T7 · Issue #28 — agy F5): نداء **مباشر** على
 * [ArhamRepository.upsertArhamInfo] — ليس [UpdateKinshipUseCase] الذي يُخفق NotFound
 * على الجهة المُصنَّفة للتو (صف أرحام غير موجود بعد؛ استعلامه `category='FAMILY' AND
 * arham EXISTS`). الوتيرة من [KinshipDefaultFrequency] — **غير-صفرية لكل قرابة**
 * إجبارياً، والطبقة مشتقة من العلاقة نفسها (عقد [ArhamInfo]).
 *
 * الدلالات: تأكيد المستخدم الصريح من شاشة مراجعة الفلترة الذكية («أضف لأرحام») يمنح
 * الكتابة حق الاستبدال — الربط لا يُستدعى آلياً أبداً (فصل الميزة: عائلة/أرحام).
 */
class ArhamBridgeKinshipLinker(
    private val arhamRepository: ArhamRepository,
) : ArhamKinshipLinker {

    override suspend fun link(contactId: Long, relation: Relation): AppResult<Unit> = try {
        arhamRepository.upsertArhamInfo(
            contactId = contactId,
            info = ArhamInfo(relation = relation, frequency = KinshipDefaultFrequency.of(relation)),
        )
        AppResult.Success(Unit)
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        AppResult.Error(DataError.Unknown(causeMessage = t::class.simpleName + ": " + (t.message ?: "no message")))
    }
}
