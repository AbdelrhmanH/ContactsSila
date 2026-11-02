package app.sila.di

import android.content.Context
import app.sila.core.common.AppResult
import app.sila.core.common.DataError
import app.sila.feature.contacts.data.AndroidConnectedAppsDetector
import app.sila.feature.contacts.data.AndroidContactImageProcessor
import app.sila.feature.contacts.data.AndroidContactsPlatformWriter
import app.sila.feature.contacts.domain.ConnectedApp
import app.sila.feature.contacts.domain.ConnectedAppsDetector
import app.sila.feature.contacts.domain.ContactImageProcessor
import app.sila.feature.contacts.domain.ContactsPlatformWriter
import app.sila.feature.contacts.domain.CreatedPlatformContact
import app.sila.feature.contacts.domain.PlatformWritableContact
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * ربط Android لوحدة كتابة جهات الاتصال من المنصة (T6 · Issue #27 · ADR-0012):
 * يوفر [AndroidContactsPlatformWriter] (كتابة ContactsContract — طفير ضيق حصراً)،
 * [AndroidContactImageProcessor] (قصّ مركزي + تصغير + JPEG)، و[AndroidConnectedAppsDetector]
 * (استعلام حزم مسماة حصراً — بلا `QUERY_ALL_PACKAGES`).
 *
 * **مسار مضيف الاختبارات فقط (host JVM):** حين لا يُسجَّل `android.content.Context` في
 * الحاوية (اختبارات JVM للمنطق المشترك — لا تطبيق أندرويد)، تُربَط بدائل [HostAbsent*]
 * بدل الطرح: الكاتب يردّ [DataError.Permission] دائماً (دلالات انعدام الصلاحية — J-1
 * تمنع المسّ أي فشل الحفظ هنا آمن)، والمعالج يعيد `null` (الصورة تحسين اختياري)،
 * والكاشف يعيد قائمة فارغة (العقد: «فارغة = لا شيء مثبت أو انعدام الكشف»).
 * على الجهاز الحقيقي يوجد Context دائماً فلا يُنفَّذ هذا المسار أبداً.
 */
internal actual val platformContactsWriterModule: Module = module {
    single<ContactsPlatformWriter> {
        val context = getOrNull<Context>()
        if (context != null) {
            AndroidContactsPlatformWriter(
                contentResolver = context.contentResolver,
                applicationContext = context
            )
        } else {
            HostAbsentContactsWriter
        }
    }
    single<ContactImageProcessor> {
        if (getOrNull<Context>() != null) AndroidContactImageProcessor() else HostAbsentContactImageProcessor
    }
    single<ConnectedAppsDetector> {
        val context = getOrNull<Context>()
        if (context != null) AndroidConnectedAppsDetector(applicationContext = context) else HostAbsentConnectedAppsDetector
    }
}

/**
 * كاتب «منصة غائبة» لمضيف JVM حصراً: كل عملية تعيد [DataError.Permission] — لا كتابة
 * ولا مسّ. هذا كائن إنتاج مسنن لكنه غير قابل للوصول على أي جهاز (Context موجود دائماً).
 */
private data object HostAbsentContactsWriter : ContactsPlatformWriter {

    private val permissionError = AppResult.Error(DataError.Permission(permissionName = WRITE_CONTACTS_NAME))

    override suspend fun createContact(writable: PlatformWritableContact): AppResult<CreatedPlatformContact> = permissionError

    override suspend fun updateContact(writable: PlatformWritableContact): AppResult<Unit> = permissionError

    override suspend fun deleteContact(systemContactId: String, lookupKey: String?): AppResult<Unit> = permissionError

    private const val WRITE_CONTACTS_NAME = "android.permission.WRITE_CONTACTS"
}

/** معالج صور «منصة غائبة» لمضيف JVM: فشل معالجة صامت — الحفظ يبقى بلا صورة (العقد). */
private data object HostAbsentContactImageProcessor : ContactImageProcessor {
    override suspend fun process(bytes: ByteArray, maxSide: Int): ByteArray? = null
}

/** كاشف «منصة غائبة» لمضيف JVM: قائمة فارغة — الواجهة تعرض الأزرار الأساسية حصراً (العقد). */
private data object HostAbsentConnectedAppsDetector : ConnectedAppsDetector {
    override suspend fun detect(): List<ConnectedApp> = emptyList()
}
