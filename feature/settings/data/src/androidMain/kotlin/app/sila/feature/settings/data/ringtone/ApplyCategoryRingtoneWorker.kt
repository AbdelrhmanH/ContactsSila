package app.sila.feature.settings.data.ringtone

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.sila.core.database.dao.ContactDao
import app.sila.core.model.Category
import app.sila.core.model.Contact
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext

/**
 * ممرّ الأعضاء (S2-10 · تذكرة #63 — خياطة حقن): يعيد أعضاء تصنيفٍ من قاعدة الحقيقة
 * — `null` يعني «التهيئة غير محسومة» (الحاوية العالمية غائبة/مبكرة) فيُعاد
 * الجدولة بدل النجاح الصامت. الممرّ الافتراضي يفوّض [ContactDao] عبر الحاوية؛
 * اختبارات العامل تحقنه مباشرةً **بلا Koin حقيقية** (معيار القبول).
 */
internal fun interface CategoryMembersSource {
    suspend fun contactsFor(category: Category): List<Contact>?
}

/**
 * بوبة كتابة النغمة على دفتر الهاتف (S2-10 · تذكرة #63): تكتب `CUSTOM_RINGTONE`
 * بالهوية الدائمة — تعيد `true` عند كتابة صف (`lookupKey` أولاً وإلا معرّف النظام
 * القابل للتحليل — عهد ADR-0009)
 * و`false` عند تعذر حل المعرّف أو فشل الكتابة — **قراءة النتيجة تُوثَّق في ناتج العمل
 * ولا تُبتلَع**. التنفيذ الحقيقي فوق [ContentResolver]؛ الاختبارات تحقن مسجّلاً.
 */
internal interface ContactRingtoneWriteBoundary {
    fun updateCustomRingtone(contact: Contact, ringtoneUri: String?): Boolean
}

/** التنفيذ الحقيقي للبوبة: بناء URIs فوق `ContactsContract` وحل المعرّف الدائم. */
internal class ContentResolverContactRingtoneWriter(
    private val contentResolver: ContentResolver,
) : ContactRingtoneWriteBoundary {

    override fun updateCustomRingtone(contact: Contact, ringtoneUri: String?): Boolean {
        val target = if (!contact.lookupKey.isNullOrBlank()) {
            Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, Uri.encode(contact.lookupKey))
        } else {
            val systemId = contact.systemContactId.toLongOrNull() ?: return false
            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, systemId)
        }
        val values = ContentValues().apply {
            put(ContactsContract.Contacts.CUSTOM_RINGTONE, ringtoneUri)
        }
        return contentResolver.update(target, values, null, null) > 0
    }
}

/**
 * عامل خلفي يطبق نغمة التصنيف على الأعضاء الذين ليس لديهم نغمة فردية (PRD §11 و 07-ringtones-preferences.md).
 * يكتب CUSTOM_RINGTONE في دفتر هاتف أندرويد لجهات اتصال التصنيف.
 * يتعامل مع الهوية الدائمة عبر CONTENT_LOOKUP_URI ويفحص صلاحية WRITE_CONTACTS بدقة (ADR-0009 / ADR-0012).
 *
 * **نزاهة النتائج (S2-10 · تذكرة #63):** كل نتيجة تعلن ما فعلته في ناتج العمل
 * ([KEY_PENDING_MEMBERS]/[KEY_WRITTEN_COUNT]/[KEY_REASON]) — لا نجاح منظر:
 * * تهيئة غائبة (الحاوية مبكرة) ⇒ [Result.retry] مع سجلّ (إديوم عامل الودجت T8-10).
 * * صلاحية مرفوضة ⇒ تخطٍّ موثق (نجاح بمفتاح `reason` — لا لمس قبل الحصول بعهد ADR-0012).
 * * صفر أعضاء ⇒ نجاح موثق بعدّ صفر (سلوك لا رأي: لا شيء مُطلب منه).
 * * كوارث وسط العمل (Room/Resolver) ⇒ [Result.retry].
 *
 * **قابلية الاختبار:** حقن إنشائي اختياري ([membersSource]/[writeBoundary]) — البناةُ
 * الأساسي داخلي وتبقى بنايتُه الثنائية (`Context`, `WorkerParameters`) العامة حصراً
 * ليعكسها المصنع الافتراضي لـ WorkManager في الإنتاج دون أي إعداد إضافي.
 */
class ApplyCategoryRingtoneWorker internal constructor(
    context: Context,
    parameters: WorkerParameters,
    /** حقن قابل للاختبار: null ⇒ الحل الإنتاجي عبر الحاوية العالمية (سلوك سابق محفوظ). */
    private val membersSource: CategoryMembersSource?,
    private val writeBoundary: ContactRingtoneWriteBoundary?,
) : CoroutineWorker(context, parameters) {

    /** بناة التشغيل الافتراضي: الوحيدة العامة — يعكسها WorkManager بهذا التوقيع المزدوج. */
    constructor(context: Context, parameters: WorkerParameters) : this(context, parameters, null, null)

    override suspend fun doWork(): Result {
        val categoryName = inputData.getString(KEY_CATEGORY_NAME) ?: return Result.failure()
        val category = runCatching { Category.valueOf(categoryName) }.getOrNull()
            ?: return Result.failure()
        val ringtoneUri = inputData.getString(KEY_RINGTONE_URI)

        if (!hasWriteContactsPermission()) {
            Log.w(TAG, "WRITE_CONTACTS not granted — skip documented in work result (no silent success)")
            return Result.success(documentedOutcome(pending = 0, written = 0, reason = REASON_PERMISSION_DENIED))
        }

        val source = membersSource ?: globalScopeSource()
        val contacts = source?.contactsFor(category)
        if (contacts == null) {
            Log.w(TAG, "ContactDao unresolved (early process) — retrying instead of fake success")
            return Result.retry()
        }

        val boundary = writeBoundary ?: ContentResolverContactRingtoneWriter(applicationContext.contentResolver)
        return runCatching {
            var pending = 0
            var written = 0
            for (contact in contacts) {
                if (!contact.ringtoneUri.isNullOrBlank()) continue // لديه نغمة فردية — لا تُتجاوز (عهد §11)
                pending++
                if (boundary.updateCustomRingtone(contact, ringtoneUri)) written++
            }
            // صفر أعضاء ⇒ نجاح موثق بعدّ صفر في الناتج — لا نجاح منظر بلا أثر (S2-10).
            Result.success(documentedOutcome(pending = pending, written = written, reason = REASON_APPLIED))
        }.getOrElse {
            Log.w(TAG, "ApplyCategoryRingtoneWorker failed: ${it.javaClass.simpleName}")
            Result.retry()
        }
    }

    private fun hasWriteContactsPermission(): Boolean = ContextCompat.checkSelfPermission(
        applicationContext,
        Manifest.permission.WRITE_CONTACTS,
    ) == PackageManager.PERMISSION_GRANTED

    /** الممرّ الافتراضي: [ContactDao] من الحاوية العالمية — غيابها يُقرأ `null` لا يُرمى. */
    private fun globalScopeSource(): CategoryMembersSource? {
        val dao = GlobalContext.getOrNull()?.getOrNull<ContactDao>() ?: return null
        return CategoryMembersSource { category -> dao.observeContacts(category = category).first() }
    }

    private fun documentedOutcome(pending: Int, written: Int, reason: String) = workDataOf(
        KEY_PENDING_MEMBERS to pending,
        KEY_WRITTEN_COUNT to written,
        KEY_REASON to reason,
    )

    companion object {
        const val KEY_CATEGORY_NAME = "category_name"
        const val KEY_RINGTONE_URI = "ringtone_uri"
        const val WORK_NAME_PREFIX = "apply_category_ringtone_"
        const val KEY_PENDING_MEMBERS = "pending_members"
        const val KEY_WRITTEN_COUNT = "written_count"
        const val KEY_REASON = "reason"
        const val REASON_APPLIED = "applied"
        const val REASON_PERMISSION_DENIED = "permission_denied"
        private const val TAG = "ApplyCategoryRingtone"

        fun workData(category: Category, ringtoneUri: String?) = workDataOf(
            KEY_CATEGORY_NAME to category.name,
            KEY_RINGTONE_URI to ringtoneUri
        )
    }
}
