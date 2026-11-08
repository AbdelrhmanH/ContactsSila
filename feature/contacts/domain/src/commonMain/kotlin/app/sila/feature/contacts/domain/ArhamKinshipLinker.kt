package app.sila.feature.contacts.domain

import app.sila.core.common.AppResult
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.Relation

/**
 * عقد ربط قرابة أرحام (T7 · Issue #28 — agy F5): صِلَة لا ترى ميزة أرحام إطلاقاً —
 * التنفيذ في **جذر التركيب** بنداء مباشر على [app.sila.feature.arham.domain.
 * ArhamRepository.upsertArhamInfo] (نمط [ArhamOccasionRecorder] الجسري — قاعدة
 * «الميزات لا تعتمد على بعضها» وفحص `checkArchitectureBoundaries`).
 *
 * لماذا لا [app.sila.feature.arham.domain.UpdateKinshipUseCase]؟ استعلامه يمرّ على
 * `WHERE category='FAMILY' AND arham row EXISTS` فيُخفق NotFound على الجهة المُصنَّفة
 * للتو من الفلترة الذكية (صف أرحام غير موجود بعد) — agy F5.
 */
interface ArhamKinshipLinker {
    suspend fun link(contactId: Long, relation: Relation): AppResult<Unit>
}

/**
 * الوتيرة الافتراضية لكل القرابات عند الربط من الفلترة الذكية (agy F5 — **غير-صفرية
 * إجبارياً لكل قرابة**: الافتراضي الفارغ كان يُسقط في UseCase أرحام) — جدول موثق:
 * الوالدان والزوج والأبناء يومياً، الأجداد والإخوة أسبوعياً، العمامات والأخوال كل
 * أسبوعين، أبناء العمومة شهرياً، المصاهرة والأصدقاء والمناسبات.
 */
object KinshipDefaultFrequency {

    fun of(relation: Relation): ArhamFrequency = when (relation) {
        Relation.FATHER,
        Relation.MOTHER,
        Relation.SPOUSE,
        Relation.SON,
        Relation.DAUGHTER,
        -> ArhamFrequency.DAILY

        Relation.GRANDFATHER,
        Relation.GRANDMOTHER,
        Relation.BROTHER,
        Relation.SISTER,
        -> ArhamFrequency.WEEKLY

        Relation.UNCLE_PATERNAL,
        Relation.AUNT_PATERNAL,
        Relation.UNCLE_MATERNAL,
        Relation.AUNT_MATERNAL,
        -> ArhamFrequency.BIWEEKLY

        Relation.COUSIN_PATERNAL,
        Relation.COUSIN_MATERNAL,
        -> ArhamFrequency.MONTHLY

        Relation.IN_LAW,
        Relation.CLOSE_FRIEND,
        Relation.CUSTOM,
        -> ArhamFrequency.OCCASIONS
    }
}
