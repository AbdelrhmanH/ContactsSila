package app.sila.core.database.dao

import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.RecentInteraction
import kotlinx.coroutines.flow.Flow

/**
 * عقد DAO لبيانات جهات الاتصال المحلية (Single Source of Truth — ARCHITECTURE.md §5).
 * القراءات التفاعلية تُعرض كـ Flow<T>، وعمليات الكتابة معلّقة (suspend).
 *
 * PR B (ADR-0011): هذا **عقد نطاق** (domain-facing) — بلا أي شروح Room على هذا
 * المستوى؛ كيانات Room وتنفيذها الفعلي يعيش في طبقة data
 * (`LocalSilaDatabase` + `ContactEntityDao`)، والفهرسة الفعلية (incl. FTS4)
 * مسؤولية صاحب المخطط (`SilaRoomDatabase`).
 */
interface ContactDao {

    fun observeContacts(category: Category? = null, searchQuery: String = ""): Flow<List<Contact>>

    fun observeFavorites(): Flow<List<Contact>>

    fun observeCategoryCounts(): Flow<Map<Category, Int>>

    suspend fun getContactById(id: Long): Contact?

    /**
     * مرصد تفاصيل جهة واحدة تفاعلي (T6 — Issue #27): يبثّ الحالة الكاملة (الأطفال
     * والأرحام ضمنها) عند كل كتابة ناجحة. يُبنى فوق كاش الذاكرة — كل مسارات الكتابة
     * (`upsertContact`/`applySyncBatch`/`upsertArhamInfo`/`recordContactCall`) تُحدّثه
     * بعد نجاح COMMIT حصراً، فلا انحراف بين العرض والقرص.
     * القبور تُبثّ `null` (حُذفت — الشاشة تُظهر الخروج/الرسالة المناسبة).
     */
    fun observeContactById(id: Long): Flow<Contact?>

    suspend fun upsertContact(contact: Contact)

    suspend fun updateCategory(contactId: Long, category: Category)

    suspend fun deleteContact(contactId: Long)

    /**
     * البحث الإنتاجي عبر فهرس FTS4 + جزء من رقم الهاتف (Issue #24 · T3):
     * يُطابق `displayName`/`normalizedName`/`nickname`/`company`/`department`/`notes`
     * عبر `contacts_fts MATCH` (معامل مُقيَّد لا سلسلة مُسلسَلة)، وأي جزء من الرقم عبر
     * `contact_phones`، ويستبعد القبور (`systemDeletedAt IS NULL`) دائماً.
     * يعاد رصد التحديثات لأنه يُبنى فوق كاش الذاكرة الذي تُحدّثه معاملات الكتابة/المزامنة.
     */
    fun searchContacts(query: String): Flow<List<Contact>>

    /** تبديل المفضلة لجهة واحدة (عمود صِلَة — لا يلمسه أبداً عكس المنصة ADR-0010 §7). */
    suspend fun setFavorite(contactId: Long, isFavorite: Boolean)

    /** تصنيف دفعة واحدة ذرّية لعدة جهات (اختيار متعدد — Issue #24). */
    suspend fun updateCategories(contactIds: Collection<Long>, category: Category)

    /** مفضلة دفعة واحدة ذرّية لعدة جهات (اختيار متعدد — Issue #24). */
    suspend fun setFavorites(contactIds: Collection<Long>, isFavorite: Boolean)

    /** حذف فعلي دفعة واحدة لعدة جهات (اختيار متعدد مع تأكيد — Issue #24). */
    suspend fun deleteContacts(contactIds: Collection<Long>)

    /**
     * تسجيل محاولة اتصال من داخل التطبيق (ADR-0006: أفعال الاتصال من القائمة تسجّل
     * `IN_APP` محلياً — بلا `READ_CALL_LOG` وبلا أي وصول شبكي).
     */
    suspend fun recordInAppCallAttempt(callLogId: String, e164: String, timestampMillis: Long)

    /**
     * آخر N تفاعلات لأرقام جهة واحدة (T6 — «التفاعلات الأخيرة» في شاشة التفاصيل ·
     * Issue #27): قراءة حصراً من `call_events` (Room SSOT) — لا CallLog منصوي هنا.
     */
    suspend fun getRecentCallEvents(e164s: List<String>, limit: Int): List<RecentInteraction>

    /**
     * فحص التكرار المحلي (T6 · J-9): جهات حيّة تملك الرقم [e164] حرفياً — من كاش
     * الذاكرة الكامل (SSOT المتطابق مع Room بعد كل COMMIT). [excludeContactId]
     * يُستثنى (فحص التفاصيل قيد التحرير لا يكرر نفسه). لا يمنع الحفظ أبداً.
     */
    suspend fun findContactsByE164(e164: String, excludeContactId: Long? = null): List<Contact>

    /**
     * تعليم قبر فردي بحذف ناعم (T6 · J-5 — حذف شاشة التفاصيل بعد نجاح حذف المنصة
     * حصراً): نفس دلالات قبور [ContactsSyncDao.applySyncBatch] حرفياً — 30 يوماً
     * استرجاعاً (ADR-0010 §6). لا حذف فعلي في هذا المسار (مسار T3 الجماعي مجمد).
     */
    suspend fun tombstoneContact(contactId: Long, nowMillis: Long)
}
