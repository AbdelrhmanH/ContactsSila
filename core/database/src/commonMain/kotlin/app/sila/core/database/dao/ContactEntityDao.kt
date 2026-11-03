package app.sila.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert
import app.sila.core.database.entity.ContactAddressEntity
import app.sila.core.database.entity.ContactCustomFieldEntity
import app.sila.core.database.entity.ContactDateEntity
import app.sila.core.database.entity.ContactEmailEntity
import app.sila.core.database.entity.ContactEntity
import app.sila.core.database.entity.ContactPhoneEntity
import app.sila.core.database.entity.ContactWebsiteEntity
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

    @Query("SELECT * FROM contact_phones WHERE contactId = :contactId ORDER BY sortOrder ASC, id ASC")
    suspend fun getPhonesForContact(contactId: Long): List<ContactPhoneEntity>

    /** مرصد هواتف جهة (T6 — تفاصيل جهة الاتصال تفاعلية عبر InvalidationTracker). */
    @Query("SELECT * FROM contact_phones WHERE contactId = :contactId ORDER BY sortOrder ASC, id ASC")
    fun observePhonesForContact(contactId: Long): Flow<List<ContactPhoneEntity>>

    @Query("SELECT * FROM contact_phones ORDER BY sortOrder ASC, id ASC")
    suspend fun getAllPhones(): List<ContactPhoneEntity>

    @Query("DELETE FROM contact_phones WHERE contactId = :contactId")
    suspend fun deletePhonesForContact(contactId: Long)

    @Insert
    suspend fun insertPhones(phones: List<ContactPhoneEntity>)

    // ===== contact_emails (T6 · v2) =====

    @Query("SELECT * FROM contact_emails WHERE contactId = :contactId ORDER BY sortOrder ASC, id ASC")
    suspend fun getEmailsForContact(contactId: Long): List<ContactEmailEntity>

    @Query("SELECT * FROM contact_emails WHERE contactId = :contactId ORDER BY sortOrder ASC, id ASC")
    fun observeEmailsForContact(contactId: Long): Flow<List<ContactEmailEntity>>

    @Query("DELETE FROM contact_emails WHERE contactId = :contactId")
    suspend fun deleteEmailsForContact(contactId: Long)

    @Insert
    suspend fun insertEmails(emails: List<ContactEmailEntity>)

    // ===== contact_addresses (T6 · v2) =====

    @Query("SELECT * FROM contact_addresses WHERE contactId = :contactId ORDER BY sortOrder ASC, id ASC")
    suspend fun getAddressesForContact(contactId: Long): List<ContactAddressEntity>

    @Query("SELECT * FROM contact_addresses WHERE contactId = :contactId ORDER BY sortOrder ASC, id ASC")
    fun observeAddressesForContact(contactId: Long): Flow<List<ContactAddressEntity>>

    @Query("DELETE FROM contact_addresses WHERE contactId = :contactId")
    suspend fun deleteAddressesForContact(contactId: Long)

    @Insert
    suspend fun insertAddresses(addresses: List<ContactAddressEntity>)

    // ===== contact_dates (T6 · v2) =====

    @Query("SELECT * FROM contact_dates WHERE contactId = :contactId ORDER BY sortOrder ASC, id ASC")
    suspend fun getDatesForContact(contactId: Long): List<ContactDateEntity>

    @Query("SELECT * FROM contact_dates WHERE contactId = :contactId ORDER BY sortOrder ASC, id ASC")
    fun observeDatesForContact(contactId: Long): Flow<List<ContactDateEntity>>

    @Query("DELETE FROM contact_dates WHERE contactId = :contactId")
    suspend fun deleteDatesForContact(contactId: Long)

    @Insert
    suspend fun insertDates(dates: List<ContactDateEntity>)

    // ===== contact_custom_fields (T6 · v2) =====

    @Query("SELECT * FROM contact_custom_fields WHERE contactId = :contactId ORDER BY sortOrder ASC, id ASC")
    suspend fun getCustomFieldsForContact(contactId: Long): List<ContactCustomFieldEntity>

    @Query("SELECT * FROM contact_custom_fields WHERE contactId = :contactId ORDER BY sortOrder ASC, id ASC")
    fun observeCustomFieldsForContact(contactId: Long): Flow<List<ContactCustomFieldEntity>>

    @Query("DELETE FROM contact_custom_fields WHERE contactId = :contactId")
    suspend fun deleteCustomFieldsForContact(contactId: Long)

    @Insert
    suspend fun insertCustomFields(fields: List<ContactCustomFieldEntity>)

    // ===== contact_websites (T6 · v2) =====

    @Query("SELECT * FROM contact_websites WHERE contactId = :contactId ORDER BY sortOrder ASC, id ASC")
    suspend fun getWebsitesForContact(contactId: Long): List<ContactWebsiteEntity>

    @Query("SELECT * FROM contact_websites WHERE contactId = :contactId ORDER BY sortOrder ASC, id ASC")
    fun observeWebsitesForContact(contactId: Long): Flow<List<ContactWebsiteEntity>>

    @Query("DELETE FROM contact_websites WHERE contactId = :contactId")
    suspend fun deleteWebsitesForContact(contactId: Long)

    @Insert
    suspend fun insertWebsites(websites: List<ContactWebsiteEntity>)

    // ===== قراءات جماعية (تغذية كاش الذاكرة — قراءة كاملة حصراً · ADR-0012) =====

    @Query("SELECT * FROM contact_emails ORDER BY sortOrder ASC, id ASC")
    suspend fun getAllEmails(): List<ContactEmailEntity>

    @Query("SELECT * FROM contact_addresses ORDER BY sortOrder ASC, id ASC")
    suspend fun getAllAddresses(): List<ContactAddressEntity>

    @Query("SELECT * FROM contact_dates ORDER BY sortOrder ASC, id ASC")
    suspend fun getAllDates(): List<ContactDateEntity>

    @Query("SELECT * FROM contact_custom_fields ORDER BY sortOrder ASC, id ASC")
    suspend fun getAllCustomFields(): List<ContactCustomFieldEntity>

    @Query("SELECT * FROM contact_websites ORDER BY sortOrder ASC, id ASC")
    suspend fun getAllWebsites(): List<ContactWebsiteEntity>
}
