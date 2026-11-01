package app.sila.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert
import app.sila.core.database.entity.ContactEntity
import app.sila.core.database.entity.ContactPhoneEntity
import app.sila.core.model.Category
import kotlinx.coroutines.flow.Flow

/**
 * DAO كيانات جهات الاتصال فوق Room 3 (طبقة data حصراً — ADR-0011).
 *
 * يعامل الصفوف كـ [ContactEntity] خام؛ التحويل إلى نماذج النطاق مسؤولية
 * [app.sila.core.database.entity.EntityMappers] في الواجهة الخارجية (facade).
 *
 * مزامنة فهرس FTS4 المستقل ليست هنا — `contacts_fts` ليس كياناً مُداراً من Room
 * (يُنشأ عبر `RoomDatabase.Callback` بـ DDL تراثي حرفي واحد — [app.sila.core.database.legacy.LegacyFtsDdl]،
 * ADR-0011 §FTS4) وكل عملياته (بحث MATCH/حذف/إدراج/إعادة ملء) عبر SQL خام في
 * الواجهة الخارجية (facade) — نفس دلالات القاعدة اليدوية السابقة بلا مشغّلات.
 */
@Dao
interface ContactEntityDao {

    // ===== قراءات contacts =====

    @Query("SELECT * FROM contacts WHERE systemDeletedAt IS NULL ORDER BY id ASC")
    suspend fun getActiveContacts(): List<ContactEntity>

    @Query("SELECT * FROM contacts ORDER BY id ASC")
    suspend fun getAllContacts(): List<ContactEntity>

    /** مرصد قائمة جهات الاتصال الحيّة (Flow تفاعلي عبر InvalidationTracker). */
    @Query("SELECT * FROM contacts WHERE systemDeletedAt IS NULL ORDER BY id ASC")
    fun observeActiveContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE id = :id LIMIT 1")
    suspend fun getContactById(id: Long): ContactEntity?

    /** أول تطابق حيّ بمعرّف دفتر النظام (Lookup-first-match — نفس دلالات القاعدة السابقة). */
    @Query("SELECT * FROM contacts WHERE systemContactId = :systemContactId AND systemDeletedAt IS NULL LIMIT 1")
    suspend fun findActiveBySystemContactId(systemContactId: String): ContactEntity?

    /** صفوف فهرس المزامنة لمصالحة الانجراف (إسقاط جزئي بلا دلالات عرض). */
    @Query("SELECT id, systemContactId, systemDeletedAt FROM contacts")
    suspend fun getSyncIndexRows(): List<ContactSyncIndexRow>

    @Query("SELECT COUNT(*) FROM contacts WHERE systemDeletedAt IS NULL")
    suspend fun countActiveContacts(): Int

    // ===== كتابة contacts =====
    // تحديثات مُوجَّهة (PR B — ADR-0011): بدلاً من إعادة كتابة الصف كاملاً كما كانت الشل
    // القديمة — الحالة المُلاحَظة نفسها (لا يُلمس updatedAt ولا العلاقات ولا FTS؛
    // بلا حقل id في نموذج Phone فلا آثار جانبية على هويات الهواتف).

    @Upsert
    suspend fun upsertContact(entity: ContactEntity)

    @Upsert
    suspend fun upsertContacts(entities: List<ContactEntity>)

    @Query("UPDATE contacts SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean)

    @Query("UPDATE contacts SET isFavorite = :isFavorite WHERE id IN (:ids)")
    suspend fun setFavorites(ids: Collection<Long>, isFavorite: Boolean)

    @Query("UPDATE contacts SET category = :category WHERE id IN (:ids)")
    suspend fun updateCategories(ids: Collection<Long>, category: Category)

    @Query("SELECT DISTINCT contactId FROM contact_phones WHERE e164 LIKE '%' || :digits || '%' OR raw LIKE '%' || :digits || '%'")
    suspend fun findContactIdsByPhoneDigits(digits: String): List<Long>

    /** حذف فعلي لصفوف جهات (يُستدعى داخل معاملة مع حذف العلاقات وFTS المرافق). */
    @Query("DELETE FROM contacts WHERE id IN (:ids)")
    suspend fun deleteContactsByIds(ids: Collection<Long>)

    // ===== contact_phones =====

    @Query("SELECT * FROM contact_phones WHERE contactId = :contactId ORDER BY id ASC")
    suspend fun getPhonesForContact(contactId: Long): List<ContactPhoneEntity>

    @Query("SELECT * FROM contact_phones ORDER BY id ASC")
    suspend fun getAllPhones(): List<ContactPhoneEntity>

    @Query("DELETE FROM contact_phones WHERE contactId = :contactId")
    suspend fun deletePhonesForContact(contactId: Long)

    @Insert
    suspend fun insertPhones(phones: List<ContactPhoneEntity>)
}
