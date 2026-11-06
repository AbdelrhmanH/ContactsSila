package app.sila.feature.arham.domain

import app.sila.core.common.AppResult
import app.sila.core.common.DataError
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.Relation
import kotlinx.coroutines.flow.first

/**
 * تحديث قرابة قريب (T8-11 · AC-1 · Issue #29) — قراءة-تعديل-كتابة لـ [ArhamInfo]
 * داخل معاملة المستودع المنطقية: `lastContactedAt` **لا يُمسّ أبداً** (بوابة AC-8 —
 * القرابة ليست تواصلاً)، و`pausedUntil` يُستبدل فقط بطلبٍ صريح.
 *
 * **الوتيرة الافتراضية الموثقة حسب القرابة (AC-1 — ورقة ميزة أرحام §Kinship Setup
 * Sheet):** أب/أم ← يومياً، إخوة ← أسبوعياً، أعمام/عمات (جهة الأب والأم) ← كل
 * أسبوعين، أبناء/بنات العمومة ← شهرياً. القرابات غير المذكورة في الموثق
 * (الأجداد/الأبناء/الزوج/حماية/أصدقاء/مخصص) **بلا اقتراح** — [defaultFrequencyFor]
 * يعيد `null` والورقة تُظهر الاختيار فارغاً إلزامياً (لا اختراعٌ لقواعد غير موثقة).
 *
 * الطبقة [ArhamInfo.layer] تُشتق دائماً من `relation.layer` عند الحفظ (المصدر
 * الواحد — O-7)، و`parentContactId` هو حافة الشجرة الاختيارية (§AC-13).
 */
class UpdateKinshipUseCase(
    private val repository: ArhamRepository,
) {

    suspend operator fun invoke(
        contactId: Long,
        relation: Relation,
        frequency: ArhamFrequency,
        parentContactId: Long? = null,
        pausedUntilMillis: Long? = null,
    ): AppResult<Unit> {
        val contacts = repository.observeArhamContacts().first()
        val current = contacts.firstOrNull { it.id == contactId }
            ?: return AppResult.Error(DataError.NotFound(entityId = contactId.toString()))
        repository.upsertArhamInfo(
            contactId = contactId,
            info = ArhamInfo(
                relation = relation,
                layer = relation.layer,
                parentContactId = parentContactId,
                frequency = frequency,
                // lastContactedAt محفوظ حصراً — القرابة لا تلمس خطّ التواصل (AC-8).
                lastContactedAt = current.arham?.lastContactedAt,
                pausedUntil = pausedUntilMillis,
            ),
        )
        return AppResult.Success(Unit)
    }

    companion object {

        /**
         * الوتيرة الافتراضية **الموثقة حصراً** (AC-1): القرابات المذكورة في ورقة
         * أرحام تعيد قيمتها الموثقة، وما عداها يعيد `null` (بلا اقتراح — الاختيار
         * إلزامي). دالة نقية تُختبر جدولياً.
         */
        fun defaultFrequencyFor(relation: Relation): ArhamFrequency? = when (relation) {
            Relation.FATHER, Relation.MOTHER -> ArhamFrequency.DAILY
            Relation.BROTHER, Relation.SISTER -> ArhamFrequency.WEEKLY
            Relation.UNCLE_PATERNAL,
            Relation.AUNT_PATERNAL,
            Relation.UNCLE_MATERNAL,
            Relation.AUNT_MATERNAL,
            -> ArhamFrequency.BIWEEKLY
            Relation.COUSIN_PATERNAL, Relation.COUSIN_MATERNAL -> ArhamFrequency.MONTHLY
            Relation.GRANDFATHER,
            Relation.GRANDMOTHER,
            Relation.SON,
            Relation.DAUGHTER,
            Relation.SPOUSE,
            Relation.IN_LAW,
            Relation.CLOSE_FRIEND,
            Relation.CUSTOM,
            -> null
        }
    }
}
