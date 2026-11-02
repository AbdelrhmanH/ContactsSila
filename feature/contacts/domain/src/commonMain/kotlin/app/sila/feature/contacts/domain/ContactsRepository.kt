package app.sila.feature.contacts.domain

import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.RecentInteraction
import kotlinx.coroutines.flow.Flow

/**
 * عقد مستودع جهات الاتصال (:feature:contacts:domain) — Dependency Inversion (DIP).
 */
interface ContactsRepository {
    fun observeContacts(category: Category? = null, searchQuery: String = ""): Flow<List<Contact>>
    fun observeFavorites(): Flow<List<Contact>>
    fun observeCategoryCounts(): Flow<Map<Category, Int>>
    suspend fun getContactById(id: Long): Contact?
    suspend fun upsertContact(contact: Contact)
    suspend fun updateCategory(contactId: Long, category: Category)
    suspend fun deleteContact(contactId: Long)

    /** البحث الإنتاجي عبر FTS4 + أجزاء الهواتف (Issue #24) — يستبعد القبور دائماً. */
    fun searchContacts(query: String): Flow<List<Contact>>

    /** تبديل مفضلة جهة واحدة (عمود صِلَة). */
    suspend fun setFavorite(contactId: Long, isFavorite: Boolean)

    /** تصنيف دفعة واحدة (اختيار متعدد — Issue #24). */
    suspend fun updateCategories(contactIds: Collection<Long>, category: Category)

    /** مفضلة دفعة واحدة (اختيار متعدد — Issue #24). */
    suspend fun setFavorites(contactIds: Collection<Long>, isFavorite: Boolean)

    /** حذف فعلي دفعة واحدة (بعد تأكيد المستخدم — Issue #24). */
    suspend fun deleteContacts(contactIds: Collection<Long>)

    /** تسجيل محاولة اتصال `IN_APP` محلياً (ADR-0006 — بلا أذونات سجل مكالمات). */
    suspend fun recordInAppCallAttempt(callLogId: String, e164: String, timestampMillis: Long)

    // ===== T6 — تفاصيل وتحرير جهة الاتصال (Issue #27 · ADR-0012) =====

    /** مرصد تفاصيل جهة واحدة تفاعلي — كامل الأطفال والأرحام ضمن الحالة (ADR-0012). */
    fun observeContactById(id: Long): Flow<Contact?>

    /** آخر N تفاعلات لأرقام جهة (Room SSOT — بلا CallLog منصوي). */
    suspend fun getRecentCallEvents(e164s: List<String>, limit: Int): List<RecentInteraction>

    /** تعليم قبر فردي (بعد نجاح حذف المنصة حصراً) — حذف ناعم 30 يوماً (J-5). */
    suspend fun tombstoneContact(contactId: Long, nowMillis: Long)

    /**
     * فحص التكرار المحلي (J-9): جهات حيّة تملك نفس الرقم E.164 — حصراً محلياً
     * (لا استعلام دليل شبكي في T6)، ولا يمنع الحفظ أبداً (تحذير عرض فقط).
     */
    suspend fun findContactsByE164(e164: String, excludeContactId: Long? = null): List<Contact>
}
