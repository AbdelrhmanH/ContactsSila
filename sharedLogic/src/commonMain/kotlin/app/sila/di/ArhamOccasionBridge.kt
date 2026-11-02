package app.sila.di

import app.sila.core.common.AppResult
import app.sila.core.common.DataError
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.Relation
import app.sila.feature.arham.domain.ArhamRepository
import app.sila.feature.contacts.domain.ArhamOccasionRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * تنفيذ جسر تسجيل مناسبات أرحام في **جذر التركيب** (T6 · J-4 · Issue #27) — مفوِّض
 * نقي إلى [ArhamRepository] بعكس التبعية: `:feature:contacts` يعرف عقد
 * [ArhamOccasionRecorder] حصراً، وجذر التركيب وحده يعرف الميزتين (قاعدة AGENTS.md:
 * الميزات لا تعتمد على بعضها أبداً — يفحصها `checkArchitectureBoundaries`).
 *
 * الدلالات (عقد الجسر): إنشاء رابط أرحام افتراضي
 * `ArhamInfo(relation = CUSTOM, frequency = OCCASIONS)` (الطبقة `OTHER` مشتقة من
 * `Relation.CUSTOM`) لجهة **لا رابط لها قائماً** — الوجود القائم ينجح idempotent
 * بلا إعادة كتابة. شهرياً/تواريخ المناسبة نفسها لا تُخزَّن هنا — عيد الميلاد يسكن
 * `Contact.dates` (ملكية صِلَة)، والرابط يقرأ مناسباته منها (T8 سيتوسع).
 */
class ArhamBridgeOccasionRecorder(
    private val arhamRepository: ArhamRepository,
) : ArhamOccasionRecorder {

    override suspend fun recordBirthdayOccasion(contactId: Long, month: Int, day: Int): AppResult<Unit> = try {
        val alreadyLinked = arhamRepository.observeArhamContacts().first().any { it.id == contactId }
        if (!alreadyLinked) {
            arhamRepository.upsertArhamInfo(
                contactId = contactId,
                info = ArhamInfo(relation = Relation.CUSTOM, frequency = ArhamFrequency.OCCASIONS),
            )
        }
        AppResult.Success(Unit)
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        // جانبي بنيوياً: فشل الجسر لا يُفشل حفظ جهة الاتصال أبداً (عقد [ArhamOccasionRecorder]).
        AppResult.Error(DataError.Unknown(causeMessage = t.message))
    }
}
