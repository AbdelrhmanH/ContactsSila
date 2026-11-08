package app.sila.feature.settings.data.ringtone

import android.Manifest
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
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext

/**
 * عامل خلفي يطبق نغمة التصنيف على الأعضاء الذين ليس لديهم نغمة فردية (PRD §11 و 07-ringtones-preferences.md).
 * يكتب CUSTOM_RINGTONE في دفتر هاتف أندرويد لجهات اتصال التصنيف.
 * يتعامل مع الهوية الدائمة عبر CONTENT_LOOKUP_URI ويفحص صلاحية WRITE_CONTACTS بدقة (ADR-0009 / ADR-0012).
 */
class ApplyCategoryRingtoneWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val categoryName = inputData.getString(KEY_CATEGORY_NAME) ?: return Result.failure()
        val ringtoneUri = inputData.getString(KEY_RINGTONE_URI)

        val category = runCatching { Category.valueOf(categoryName) }.getOrNull()
            ?: return Result.failure()

        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "WRITE_CONTACTS permission not granted, skipping apply category ringtones")
            return Result.success()
        }

        val koin = GlobalContext.getOrNull()
        val contactDao = koin?.getOrNull<ContactDao>()

        return runCatching {
            val contacts = contactDao?.observeContacts(category = category)?.first() ?: emptyList()
            // التحديث يشمل فقط الأعضاء الذين ليس لديهم نغمة فردية مخصصة
            val membersWithoutIndividualRingtone = contacts.filter { it.ringtoneUri.isNullOrBlank() }

            val contentResolver = applicationContext.contentResolver
            for (contact in membersWithoutIndividualRingtone) {
                val key = contact.lookupKey ?: contact.systemContactId
                if (key.isBlank()) continue

                val lookupUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, key)
                val values = ContentValues().apply {
                    put(ContactsContract.Contacts.CUSTOM_RINGTONE, ringtoneUri)
                }
                runCatching {
                    contentResolver.update(lookupUri, values, null, null)
                }
            }
            Result.success()
        }.getOrElse {
            Log.w(TAG, "ApplyCategoryRingtoneWorker failed: ${it.javaClass.simpleName}")
            Result.retry()
        }
    }

    companion object {
        const val KEY_CATEGORY_NAME = "category_name"
        const val KEY_RINGTONE_URI = "ringtone_uri"
        const val WORK_NAME_PREFIX = "apply_category_ringtone_"
        private const val TAG = "ApplyCategoryRingtone"

        fun workData(category: Category, ringtoneUri: String?) = workDataOf(
            KEY_CATEGORY_NAME to category.name,
            KEY_RINGTONE_URI to ringtoneUri
        )
    }
}
