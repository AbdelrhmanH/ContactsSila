package app.sila.feature.contacts.domain

import app.sila.core.common.AppClock
import app.sila.core.model.Category
import kotlin.random.Random

/**
 * تصنيف دفعة واحدة لعدة جهات (اختيار متعدد — Issue #24). كتابة واحدة ذرّية في الـ DAO.
 */
class BulkCategorizeUseCase(private val repository: ContactsRepository) {

    suspend operator fun invoke(contactIds: Collection<Long>, category: Category) {
        repository.updateCategories(contactIds, category)
    }
}

/**
 * حذف فعلي دفعة واحدة لعدة جهات (اختيار متعدد مع تأكيد في العرض — Issue #24).
 *
 * **ملاحظة سلوك موثقة:** الحذف فعلي (نفس دلالات `DeleteContact` القائمة) — جهة
 * ما تزال في دفتر المنصة ستظهر مجدداً بعد المصالحة الكاملة التالية لأن صِلَة لا
 * تكتب في دفتر الهاتف (الكتابة T6). تاكيد الحذف في العرض يخفف ذلك، والحل
 * الجذري (حذف من المنصة أو إخفاء محلي دائم) قرار منتج لاحق.
 */
class DeleteContactsUseCase(private val repository: ContactsRepository) {

    suspend operator fun invoke(contactIds: Collection<Long>) {
        repository.deleteContacts(contactIds)
    }
}

/**
 * تبديل مفضلة جهة واحدة (عمود صِلَة — لا تلمسه المزامنة أبداً، ADR-0010 §7).
 * الحالة الجديدة تُمرَّر صريحة من العرض (المصدر: حالة الصف في الـ UiState).
 */
class ToggleFavoriteUseCase(private val repository: ContactsRepository) {

    suspend operator fun invoke(contactId: Long, isFavorite: Boolean) {
        repository.setFavorite(contactId, isFavorite)
    }
}

/**
 * مفضلة دفعة واحدة لعدة جهات (اختيار متعدد — Issue #24). كتابة واحدة ذرّية في الـ DAO.
 */
class BulkSetFavoriteUseCase(private val repository: ContactsRepository) {

    suspend operator fun invoke(contactIds: Collection<Long>, isFavorite: Boolean) {
        repository.setFavorites(contactIds, isFavorite)
    }
}

/**
 * تسجيل محاولة اتصال `IN_APP` محلياً (ADR-0006 §: أفعال الاتصال من القائمة/المفضلة/
 * السحب تسجَّل في `call_events` بلا `READ_CALL_LOG` وبلا أي وصول شبكي).
 */
class RecordInAppCallUseCase(
    private val repository: ContactsRepository,
    private val clock: AppClock,
) {

    suspend operator fun invoke(e164: String) {
        val nowMillis = clock.nowEpochMillis()
        repository.recordInAppCallAttempt(
            callLogId = "inapp-$nowMillis-${Random.nextLong().toULong().toString(16)}",
            e164 = e164,
            timestampMillis = nowMillis
        )
    }
}
