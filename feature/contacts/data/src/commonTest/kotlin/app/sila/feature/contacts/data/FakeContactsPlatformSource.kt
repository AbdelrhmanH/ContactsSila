package app.sila.feature.contacts.data

import app.sila.core.model.ContactName
import app.sila.core.model.PlatformContact
import app.sila.feature.contacts.domain.ContactsChangeSignal
import app.sila.feature.contacts.domain.ContactsDelta
import app.sila.feature.contacts.domain.ContactsPage
import app.sila.feature.contacts.domain.ContactsPermissionState
import app.sila.feature.contacts.domain.ContactsPlatformSource
import app.sila.feature.contacts.domain.ContactsSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * كائن اختبار (Fixture) قابل للتهيئة لعقد [ContactsPlatformSource] — بلا منصة ولا شبكة
 * (خصوصية ADR-0009 §6) ويُستخدم في اختبارات العقد المشتركة (Issue #22).
 *
 * قابلية التهيئة:
 * - [pages]: صفحات القراءة الكاملة المقطّعة (كل عنصر = صفحة واحدة تُعاد في [readPage]).
 * - [deltaQueue]: دلتا متسلسلة تُستهلك بترتيب [readDelta]؛ يُرد آخر دلتا إن انتهت القائمة.
 * - مسار إعادة المزامنة الكاملة يُفعَّل تلقائياً عند `readDelta(null)` أو لقطة بلا علامة منصة.
 * - إشارات التغيير عبر [sendChangeSignal] — ولا تعترف بالسجل المتغيّر (عقد ثابت).
 * - انتقالات الصلاحية: [grantPermission] / [limitPermission] / [denyPermission] /
 *   [permanentlyDenyPermission] ومراقبها يُنبض تبعياً لحظة القيمة (Flow المشترك).
 */
class FakeContactsPlatformSource(
    initialPermission: ContactsPermissionState = ContactsPermissionState.DENIED,
) : ContactsPlatformSource {

    /** صفحات القراءة الكاملة: كل عنصر صفحة معينة في [readPage]. */
    val pages: MutableList<List<PlatformContact>> = mutableListOf()

    /** دلتا متسلسلة تُستمر بحسب طلب [readDelta]. */
    val deltaQueue: ArrayDeque<ContactsDelta> = ArrayDeque()

    /** أقرب دلتا مرة أخرى للأتصال السابق بلا عنصر متبقي في [deltaQueue]. */
    var finalDelta: ContactsDelta? = null

    var readDeltaRequestCount: Int = 0
        private set

    /** طلبات [readPage] بترتيبها (للتحقق من التقسيم/المقاطع). */
    val readPageRequests: MutableList<PageRequest> = mutableListOf()

    /** لقطات [readDelta] بحسب ترتيبها (للاختبار: العقد لا يتغير `null` مع الجداول). */
    val readDeltaSnapshots: MutableList<ContactsSnapshot?> = mutableListOf()

    private val _permission = MutableStateFlow(initialPermission)
    val permissionState: StateFlow<ContactsPermissionState> get() = _permission.asStateFlow()

    private val _changeSignals = MutableSharedFlow<ContactsChangeSignal>(extraBufferCapacity = 16)
    val changeSignals: SharedFlow<ContactsChangeSignal> get() = _changeSignals.asSharedFlow()

    /** اللقطة المُعاد تحديدها للدلة القادمة (مثلاً: iOS أو Android اختيارياً في الاختبارات). */
    var snapshotForDelta: ContactsSnapshot? = null

    fun withPages(vararg pageContacts: List<PlatformContact>): FakeContactsPlatformSource = apply {
        pages.clear()
        pages.addAll(pageContacts)
    }

    fun withDeltas(vararg deltas: ContactsDelta?): FakeContactsPlatformSource = apply {
        deltaQueue.clear()
        deltas.filterNotNull().forEach(deltaQueue::addLast)
    }

    fun grantPermission() {
        _permission.value = ContactsPermissionState.GRANTED
    }

    fun limitPermission() {
        _permission.value = ContactsPermissionState.LIMITED
    }

    fun denyPermission() {
        _permission.value = ContactsPermissionState.DENIED
    }

    fun permanentlyDenyPermission() {
        _permission.value = ContactsPermissionState.PERMANENTLY_DENIED
    }

    /** إصدار إشارة "الدفتر اتّسخ" فقط — بلا معرفة بالسجل المتغيّر (عقد ثابت). */
    fun sendChangeSignal(occurredAtMillis: Long = 0L) {
        _changeSignals.tryEmit(ContactsChangeSignal(occurredAtMillis))
    }

    override suspend fun readPage(offset: Int, limit: Int): ContactsPage {
        readPageRequests.add(PageRequest(offset, limit))
        val index = offset / limit.coerceAtLeast(1)
        if (offset < 0 || index >= pages.size || offset % limit.coerceAtLeast(1) != 0) {
            return ContactsPage(emptyList(), offset, limit, hasMore = false)
        }
        return ContactsPage(
            contacts = pages[index],
            offset = offset,
            limit = limit,
            hasMore = index + 1 < pages.size
        )
    }

    override suspend fun readDelta(previousSnapshot: ContactsSnapshot?): ContactsDelta {
        readDeltaRequestCount++
        readDeltaSnapshots.add(previousSnapshot)
        deltaQueue.removeFirstOrNull()?.let { return it }
        if (previousSnapshot == null || previousSnapshot.platformMarker == null) {
            // مسار إعادة المزامنة الكاملة: قراءة كل الصفحات، مفاتيناً وأي صف مستقل — بلا حذف.
            return ContactsDelta(
                upserted = pages.flatten(),
                deletedSystemContactIds = emptyList(),
                newSnapshot = snapshotForDelta ?: ContactsSnapshot(),
                isFullResync = true
            )
        }
        return finalDelta ?: ContactsDelta(
            upserted = emptyList(),
            deletedSystemContactIds = emptyList(),
            newSnapshot = previousSnapshot,
            isFullResync = false
        )
    }

    override fun observeChangeSignals(): Flow<ContactsChangeSignal> = _changeSignals.asSharedFlow()

    override fun observePermissionState(): Flow<ContactsPermissionState> = _permission.asStateFlow()

    /** طلب [readPage] المسجَّل (للتحقق من التقسيم/المقاطع في الاختبار). */
    data class PageRequest(val offset: Int, val limit: Int)

    companion object {
        /** بناء [PlatformContact] مطابق للعقد (مفتاح موحّد + مرآة LOOKUP على Android). */
        fun contact(
            systemContactId: String,
            lookupKey: String? = systemContactId,
            displayName: String = systemContactId,
        ): PlatformContact = PlatformContact(
            systemContactId = systemContactId,
            lookupKey = lookupKey,
            name = ContactName(displayName = displayName)
        )
    }
}
