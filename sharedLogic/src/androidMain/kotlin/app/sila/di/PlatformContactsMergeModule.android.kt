package app.sila.di

import android.content.Context
import app.sila.feature.contacts.data.AndroidContactsMergeWriter
import app.sila.feature.contacts.domain.ContactsMergeWriter
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * ربط Android لوحدة الدمج المنصوي (T7 · ADR-0013): [AndroidContactsMergeWriter]
 * (AggregationExceptions + طفير حقول الناجي الضيق + إعادة حل LOOKUP_KEY بعد التجميع).
 *
 * **مسار مضيف الاختبارات فقط (host JVM):** بلا `Context` في الحاوية يُربط
 * [HostAbsentContactsMergeWriter] — كل عملية تردّ [DataError.Permission] (J-1: فشل
 * المنصة يمنع أي مساس Room، فدلالة الرفض آمنة بنيوياً).
 */
internal actual val platformContactsMergeModule: Module = module {
    single<ContactsMergeWriter> {
        val context = getOrNull<Context>()
        if (context != null) {
            AndroidContactsMergeWriter(
                contentResolver = context.contentResolver,
                applicationContext = context,
            )
        } else {
            HostAbsentContactsMergeWriter
        }
    }
}

/** كاتب دمج «منصة غائبة» لمضيف JVM حصراً — بلا كتابة ولا مسّ (نمط HostAbsentContactsWriter). */
private data object HostAbsentContactsMergeWriter : ContactsMergeWriter {
    private val permissionError = AppResult.Error(DataError.Permission(permissionName = "android.permission.WRITE_CONTACTS"))

    override suspend fun mergeContacts(request: PlatformMergeRequest): AppResult<PlatformMergeResult> = permissionError

    override suspend fun unmergeContacts(request: PlatformUnmergeRequest): AppResult<PlatformUnmergeResult> = permissionError
}
