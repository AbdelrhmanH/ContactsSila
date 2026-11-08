package app.sila.settings.ringtone

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.sila.core.model.Category
import app.sila.feature.settings.domain.PlatformRingtoneSource
import app.sila.feature.settings.domain.RingtoneItem

/**
 * تنفيذ مصدر نغمات الرنين لنظام أندرويد (PRD §11 و 07-ringtones-preferences.md).
 * يدير نغمات النظام، وملفات الصوت المخصصة عبر PersistableUriPermission،
 * وتطبيق نغمات التصنيف عبر WorkManager.
 */
class AndroidRingtoneSource(
    private val context: Context
) : PlatformRingtoneSource {

    override suspend fun applyCategoryRingtone(category: Category, ringtoneUri: String?) {
        val request = OneTimeWorkRequestBuilder<ApplyCategoryRingtoneWorker>()
            .setInputData(ApplyCategoryRingtoneWorker.workData(category, ringtoneUri))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "${ApplyCategoryRingtoneWorker.WORK_NAME_PREFIX}${category.name}",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    override fun getSystemDefaultRingtoneUri(): String =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)?.toString() ?: ""

    override fun getAvailableRingtones(): List<RingtoneItem> {
        val ringtoneManager = RingtoneManager(context).apply {
            setType(RingtoneManager.TYPE_RINGTONE)
        }
        val cursor = ringtoneManager.cursor ?: return emptyList()
        val items = mutableListOf<RingtoneItem>()
        while (cursor.moveToNext()) {
            val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
            val uri = ringtoneManager.getRingtoneUri(cursor.position).toString()
            items += RingtoneItem(title = title, uri = uri)
        }
        return items
    }

    fun takePersistableUriPermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }
    }
}
