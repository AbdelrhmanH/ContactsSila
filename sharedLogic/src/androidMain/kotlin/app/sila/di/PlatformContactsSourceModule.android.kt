package app.sila.di

import android.content.Context
import app.sila.feature.contacts.data.AndroidContactsPlatformSource
import app.sila.feature.contacts.domain.ContactsChangeSignal
import app.sila.feature.contacts.domain.ContactsDelta
import app.sila.feature.contacts.domain.ContactsPage
import app.sila.feature.contacts.domain.ContactsPermissionState
import app.sila.feature.contacts.domain.ContactsPlatformSource
import app.sila.feature.contacts.domain.ContactsSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * ربط Android لمصدر جهات الاتصال من المنصة (Issue #22 · ADR-0009 §3):
 * يوفر [AndroidContactsPlatformSource] (قراءة ContactsContract فقط — لا كتابة ولا شبكة).
 *
 * **مسار مضيف الاختبارات فقط (host JVM):** حين لا يُسجَّل `android.content.Context` في
 * الحاوية (اختبارات JVM للمنطق المشترك — لا تطبيق أندرويد)، تُربَط [HostAbsentPlatformSource]
 * بدل الطرح: صلاحية DENIED — دلالات ADR-0010 §5 القياسية (توقف المزامنة وتبقى Room مقروءة،
 * وبذور العرض/البيانات المحلية تعمل). على الجهاز الحقيقي يوجد Context دائماً
 * (SilaApplication يزوّده عبر `androidContext`) فلا يُنفَّذ هذا المسار أبداً.
 */
internal actual val platformContactsSourceModule: Module = module {
    single<ContactsPlatformSource> {
        val context = getOrNull<Context>()
        if (context != null) {
            AndroidContactsPlatformSource(
                contentResolver = context.contentResolver,
                applicationContext = context
            )
        } else {
            HostAbsentPlatformSource
        }
    }
}

/**
 * مصدر "منصة غائبة" لمضيف JVM حصراً: يقرأ لا شيء، والصلاحية DENIED (لا مزامنة —
 * ADR-0010 §5)، والدلتا بلا لقطة فيَعِد المحرك «لا تقديم جزئي» حصراً.
 * هذا كائن إنتاج مسنن لكنه غير قابل للوصول على أي جهاز (Context موجود دائماً).
 */
private data object HostAbsentPlatformSource : ContactsPlatformSource {

    private val permission = MutableStateFlow(ContactsPermissionState.DENIED)

    override suspend fun readPage(offset: Int, limit: Int): ContactsPage =
        ContactsPage(contacts = emptyList(), offset = offset, limit = limit, hasMore = false)

    override suspend fun readDelta(previousSnapshot: ContactsSnapshot?): ContactsDelta =
        ContactsDelta(
            upserted = emptyList(),
            deletedSystemContactIds = emptyList(),
            newSnapshot = null, // لا لقطة ⇒ لا التزام (ADR-0010 §8).
            isFullResync = false
        )

    override fun observeChangeSignals(): Flow<ContactsChangeSignal> =
        MutableStateFlow(ContactsChangeSignal())

    override fun observePermissionState(): Flow<ContactsPermissionState> = permission
}
