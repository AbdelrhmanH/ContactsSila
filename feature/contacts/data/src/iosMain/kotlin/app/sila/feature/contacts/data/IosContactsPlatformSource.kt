package app.sila.feature.contacts.data

import app.sila.feature.contacts.domain.ContactsChangeSignal
import app.sila.feature.contacts.domain.ContactsDelta
import app.sila.feature.contacts.domain.ContactsPage
import app.sila.feature.contacts.domain.ContactsPermissionState
import app.sila.feature.contacts.domain.ContactsPlatformSource
import app.sila.feature.contacts.domain.ContactsSnapshot
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.Contacts.CNAuthorizationStatusAuthorized
import platform.Contacts.CNAuthorizationStatusDenied
import platform.Contacts.CNAuthorizationStatusLimited
import platform.Contacts.CNAuthorizationStatusNotDetermined
import platform.Contacts.CNAuthorizationStatusRestricted
import platform.Contacts.CNChangeHistoryAddContactEvent
import platform.Contacts.CNChangeHistoryDeleteContactEvent
import platform.Contacts.CNChangeHistoryDropEverythingEvent
import platform.Contacts.CNChangeHistoryFetchRequest
import platform.Contacts.CNChangeHistoryUpdateContactEvent
import platform.Contacts.CNContact
import platform.Contacts.CNContactDepartmentNameKey
import platform.Contacts.CNContactEmailAddressesKey
import platform.Contacts.CNContactFamilyNameKey
import platform.Contacts.CNContactFetchRequest
import platform.Contacts.CNContactGivenNameKey
import platform.Contacts.CNContactIdentifierKey
import platform.Contacts.CNContactJobTitleKey
import platform.Contacts.CNContactMiddleNameKey
import platform.Contacts.CNContactNamePrefixKey
import platform.Contacts.CNContactNameSuffixKey
import platform.Contacts.CNContactNicknameKey
import platform.Contacts.CNContactNoteKey
import platform.Contacts.CNContactOrganizationNameKey
import platform.Contacts.CNContactPhoneticFamilyNameKey
import platform.Contacts.CNContactPhoneticGivenNameKey
import platform.Contacts.CNContactPhoneticMiddleNameKey
import platform.Contacts.CNContactPhoneNumbersKey
import platform.Contacts.CNContactStore
import platform.Contacts.CNContactStoreDidChangeNotification
import platform.Contacts.CNContactUrlAddressesKey
import platform.Contacts.CNEntityType
import platform.Contacts.CNEntityTypeContacts
import platform.Contacts.CNLabelHome
import platform.Contacts.CNLabelOther
import platform.Contacts.CNLabelPhoneNumberMobile
import platform.Contacts.CNLabelWork
import platform.Contacts.CNLabeledValue
import platform.Contacts.CNPhoneNumber
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSEnumerator
import platform.Foundation.NSNotificationCenter
import platform.Foundation.timeIntervalSince1970

/**
 * تنفيذ iOS لعقد [ContactsPlatformSource] (Issue #22 · ADR-0009 §4 · ADR-0010 §4):
 *
 * - **readPage:** قراءة موحّدة مفتاح-مُوجّه (`identifier`): تعداد كل المعرفات عبر
 *   `enumeratorForContactFetchRequest` (مفتاح خفيف) ثم دفعة `unifiedContactsMatchingPredicate`
 *   لكل صفحة (≤ 500) — التقسيم بالإزاحة على ترتيب معرفات ثابت (ADR-0010 §2).
 * - **readDelta:** `CNChangeHistoryFetchRequest` مع `startingToken` المفكوك من
 *   [ContactsSnapshot.iosHistoryTokenBytes]؛ الأحداث تُحلَّل للنقية عبر [toIosHistoryEvent]
 *   ثم [assembleIosDelta]. لقطة من منصة أخرى أو رمز مفقود/فارغ → إعادة مزامنة كاملة
 *   بلا حذف، و`.dropEverything` يُلغي الحذف ويفرض إعادة مزامنة كاملة (ADR-0010 §4).
 *   رمز مشوّه شكلاً: المخزن يردّه بحدث `.dropEverything` + إضافات كاملة (TN3149)، فتتحقق
 *   دلالات "رمز غير صالح → إعادة مزامنة كاملة" دون أي خطر حذف.
 * - **observeChangeSignals:** `CNContactStoreDidChangeNotification` مُشغِّل فقط — بلا
 *   أي هُوية للسجل المتغيّر، ويُستخدم لاستدعاء [readDelta] لا لقراءة دلتا بذاتها.
 * - **observePermissionState:** [CNContactStore.authorizationStatusForEntityType] —
 *   `.limited` يُعرض [ContactsPermissionState.LIMITED] ولا يُعامل رفضاً أبداً (§5).
 *
 * كل وصول للمخزن معزول هنا؛ التحويل `CNContact ↔ PlatformContact` يمر حصراً عبر
 * [toIosContactValues] + [mapIosContact] (المنطق النقي، وتُختبر عبر `sharedLogic/src/iosTest`).
 *
 * ملاحظة: التحقق من هذه الشيفرة على macOS CI حصراً؛ أسماء واجهات cinterop مُستخرجة
 * من مواصفات Kotlin Native 2.4.20 الفعلية (`klib dump-metadata`).
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalForeignApi::class)
class IosContactsPlatformSource(
    private val store: CNContactStore = CNContactStore(),
) : ContactsPlatformSource {

    override suspend fun readPage(offset: Int, limit: Int): ContactsPage {
        val authorization = currentAuthorizationStatus()
        if (limit <= 0 || (authorization != CNAuthorizationStatusAuthorized && authorization != CNAuthorizationStatusLimited)) {
            // بلا صلاحية قراءة (مرفوضة/.restricted/غير محددة) — صفحة فارغة، الحالة تُقرأ من observePermissionState.
            return ContactsPage(emptyList(), offset, limit, hasMore = false)
        }
        return runCatching {
            fetchPage(offset = offset, limit = limit)
        }.getOrElse {
            // خطأ مؤقت (مخزن غير متاح/صلاحية سُحبت أثناء القراءة) — صفحة فارغة (العقد).
            ContactsPage(emptyList(), offset, limit, hasMore = false)
        }
    }

    override suspend fun readDelta(previousSnapshot: ContactsSnapshot?): ContactsDelta = runCatching {
        val token = previousSnapshot
            ?.takeIf { it.platformMarker == ContactsSnapshot.PLATFORM_IOS }
            ?.let { decodeIosHistoryToken(it.iosHistoryTokenBytes) }
        if (previousSnapshot == null || token == null) {
            // بلا لقطة أو رمز مفقود/فارغ → إعادة مزامنة كاملة، بلا حذف (ADR-0010 §4).
            fullResyncDelta(previousSnapshot)
        } else {
            incrementalDelta(previousSnapshot, token)
        }
    }.getOrElse {
        // أي فشل → لقطة جديدة null (لا تطبيق جزئي أبداً) وبلا حذف (ADR-0010 §8).
        ContactsDelta(upserted = emptyList(), deletedSystemContactIds = emptyList(), newSnapshot = null, isFullResync = false)
    }

    override fun observeChangeSignals(): Flow<ContactsChangeSignal> = callbackFlow {
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = CNContactStoreDidChangeNotification,
            `object` = store,
            queue = null
        ) { _ ->
            // مُشغِّل فقط: بلا أي معرفة بالسجل المتغيّر (العقد المجمد).
            trySend(ContactsChangeSignal(occurredAtMillis = currentWallClockMillis()))
        }
        awaitClose {
            NSNotificationCenter.defaultCenter.removeObserver(observer)
        }
    }

    override fun observePermissionState(): Flow<ContactsPermissionState> = callbackFlow {
        trySend(currentPermissionState())
        // iOS لا يبثّ تغييرات صلاحية دائماً؛ إعادة قراءة دورية خفيفة تلتقط تغيّر الإعدادات.
        val repoller = launch {
            while (isActive) {
                delay(PERMISSION_REPOLL_INTERVAL_MILLIS)
                trySend(currentPermissionState())
            }
        }
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = CNContactStoreDidChangeNotification,
            `object` = store,
            queue = null
        ) { _ -> trySend(currentPermissionState()) }
        awaitClose {
            repoller.cancel()
            NSNotificationCenter.defaultCenter.removeObserver(observer)
        }
    }

    // ------------------------------------------------------------- Permission

    private fun currentAuthorizationStatus(): Long =
        CNContactStore.authorizationStatusForEntityType(CNEntityTypeContacts)

    private fun currentPermissionState(): ContactsPermissionState = when (currentAuthorizationStatus()) {
        CNAuthorizationStatusAuthorized -> ContactsPermissionState.GRANTED
        CNAuthorizationStatusLimited -> ContactsPermissionState.LIMITED
        CNAuthorizationStatusNotDetermined, CNAuthorizationStatusDenied -> ContactsPermissionState.DENIED
        CNAuthorizationStatusRestricted -> ContactsPermissionState.PERMANENTLY_DENIED
        else -> ContactsPermissionState.DENIED
    }

    // ------------------------------------------------------------- Read page

    private fun fetchPage(offset: Int, limit: Int): ContactsPage {
        val identifiers = fetchAllContactIdentifiers()
        val pageIdentifiers = identifiers.drop(offset).take(limit)
        if (pageIdentifiers.isEmpty()) {
            return ContactsPage(emptyList(), offset, limit, hasMore = false)
        }
        val contacts = fetchContactsByIdentifiers(pageIdentifiers)
        val mappedContacts = contacts.map { contact -> mapIosContact(contact.toIosContactValues()) }
        return ContactsPage(
            contacts = mappedContacts,
            offset = offset,
            limit = limit,
            hasMore = offset + pageIdentifiers.size < identifiers.size
        )
    }

    /**
     * تعداد كل معرفات `CNContact` (مفتاح `CNContactIdentifierKey` كحده — خفيف عمداً)،
     * بترتيب ثابت لكل استدعاء يُبنى عليه التقسيم بالإزاحة.
     */
    private fun fetchAllContactIdentifiers(): List<String> = memScoped {
        val request = CNContactFetchRequest(keysToFetch = listOf(CNContactIdentifierKey))
        val error = alloc<ObjCObjectVar<NSError?>>()
        val result = store.enumeratorForContactFetchRequest(request, error.ptr)
        throwIfError(error.value, context = "enumeratorForContactFetchRequest")
        val enumerator = result?.value as? NSEnumerator
            ?: error("CNFetchResult.value did not expose an NSEnumerator for a contacts fetch.")
        buildList {
            while (true) {
                val next = enumerator.nextObject() ?: break
                val contact = next as? CNContact ?: continue
                add(contact.identifier)
            }
        }
    }

    /** قراءة الجهات الموحّدة بمعرفات صفحة واحدة فقط. */
    private fun fetchContactsByIdentifiers(identifiers: List<String>): List<CNContact> = memScoped {
        val predicate = CNContact.predicateForContactsWithIdentifiers(identifiers)
        val error = alloc<ObjCObjectVar<NSError?>>()
        val contacts = store.unifiedContactsMatchingPredicate(
            predicate = predicate,
            keysToFetch = FULL_CONTACT_KEYS,
            error = error.ptr
        ).orEmpty()
        throwIfError(error.value, context = "unifiedContactsMatchingPredicate")
        contacts.filterIsInstance<CNContact>()
    }

    // ------------------------------------------------------------- Delta

    private fun fullResyncDelta(previousSnapshot: ContactsSnapshot?): ContactsDelta {
        val upserted = buildList {
            var offset = 0
            while (true) {
                val page = fetchPage(offset = offset, limit = DEFAULT_PAGE_LIMIT)
                if (page.contacts.isEmpty()) break
                addAll(page.contacts)
                if (!page.hasMore) break
                offset = page.offset + page.contacts.size
            }
        }
        return ContactsDelta(
            upserted = upserted,
            // القراءة الكاملة لا تحذف أبداً — المصالحة الدورية تبقى مسار الحذف الآمن (ADR-0010 §3).
            deletedSystemContactIds = emptyList(),
            newSnapshot = freshSnapshot(),
            isFullResync = true
        )
    }

    private fun incrementalDelta(previousSnapshot: ContactsSnapshot, token: NSData): ContactsDelta =
        memScoped {
            val request = CNChangeHistoryFetchRequest()
            request.startingToken = token
            val error = alloc<ObjCObjectVar<NSError?>>()
            val result = store.enumeratorForChangeHistoryFetchRequest(request, error.ptr)
            throwIfError(error.value, context = "enumeratorForChangeHistoryFetchRequest")
            val enumerator = result?.value as? NSEnumerator
                ?: error("CNFetchResult.value did not expose an events NSEnumerator.")

            val events = buildList {
                while (true) {
                    val next = enumerator.nextObject() ?: break
                    val event = toIosHistoryEvent(next)
                    if (event is IosHistoryEvent.Ignored) continue
                    add(event)
                }
            }

            // رمز الاستئناف الجديد من نتيجة جلب السجل نفسها.
            val newSnapshot = ContactsSnapshot(
                platformMarker = ContactsSnapshot.PLATFORM_IOS,
                iosHistoryTokenBytes = encodeIosHistoryToken(result?.currentHistoryToken)
            )
            assembleIosDelta(events, newSnapshot)
        }

    /** لقطة نقطة الاستئناف من رمز المخزن الحالي - إن كان مفقوداً تسجل بايتات null لإعادة المزامنة لاحقاً. */
    private fun freshSnapshot(): ContactsSnapshot = ContactsSnapshot(
        platformMarker = ContactsSnapshot.PLATFORM_IOS,
        iosHistoryTokenBytes = encodeIosHistoryToken(store.currentHistoryToken)
    )

    /** حدث واحد من سجل التغييرات → [IosHistoryEvent] النقي (بلا أنواع منصة في النتيجة). */
    private fun toIosHistoryEvent(raw: Any?): IosHistoryEvent = when (raw) {
        is CNChangeHistoryAddContactEvent -> IosHistoryEvent.Upsert(raw.contact.toIosContactValues())
        is CNChangeHistoryUpdateContactEvent -> IosHistoryEvent.Upsert(raw.contact.toIosContactValues())
        is CNChangeHistoryDeleteContactEvent -> IosHistoryEvent.Delete(raw.contactIdentifier)
        is CNChangeHistoryDropEverythingEvent -> IosHistoryEvent.DropEverything
        else -> IosHistoryEvent.Ignored
    }

    // ------------------------------------------------------------- Token bytes

    /**
     * رمز سجل التغييرات (`CNFetchResult.currentHistoryToken` — يُقدَّم كـ NSData تحت
     * اسم `CNChangeHistoryToken`) ينتقل حصراً عبر بايتات نقية (مثل العقد).
     */
    public fun encodeIosHistoryToken(token: NSData?): ByteArray? {
        if (token == null || token.length.toInt() == 0) return null
        return token.iosDataToByteArray()
    }

    /**
     * بايتات اللقطة → رمز. الفارغة تعود `null` → إعادة المزامنة الكاملة (رمز مفقود).
     * الرموز المشوّهة تعود NSData بايتاتها كما هي: طلب الجلب يردّها بحدث
     * `.dropEverything` + إضافات كاملة، فتتحقق دلالات "رمز غير صالح → إعادة المزامنة" (TN3149).
     */
    public fun decodeIosHistoryToken(bytes: ByteArray?): NSData? =
        bytes.takeIf { it.isNotEmpty() }?.iosByteArrayToData()

    private companion object {
        const val DEFAULT_PAGE_LIMIT = 500
        const val PERMISSION_REPOLL_INTERVAL_MILLIS = 5_000L

        /** مفاتيح القراءة الكاملة (إعداد `List<*>` في cinterop 2.4.20 — لا سكبات غير مُسندة). */
        val FULL_CONTACT_KEYS: List<String> = listOf(
            CNContactIdentifierKey,
            CNContactNamePrefixKey, CNContactGivenNameKey, CNContactMiddleNameKey, CNContactFamilyNameKey,
            CNContactNameSuffixKey, CNContactNicknameKey,
            CNContactPhoneticGivenNameKey, CNContactPhoneticMiddleNameKey, CNContactPhoneticFamilyNameKey,
            CNContactOrganizationNameKey, CNContactDepartmentNameKey, CNContactJobTitleKey, CNContactNoteKey,
            CNContactPhoneNumbersKey, CNContactEmailAddressesKey, CNContactUrlAddressesKey
        )
    }
}

private fun throwIfError(error: NSError?, context: String) {
    if (error != null) {
        error("Contacts fetch failed in $context: ${error.localizedDescription}")
    }
}

/** طابع زمني (بالميلي ثانية) لوقت إصدار الإشارة الدامجة. */
private fun currentWallClockMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

/**
 * تحويل `CNContact` فعلي إلى [IosContactValues] النقية (public كي يُستدعى من
 * `sharedLogic/src/iosTest` بمستحيلات `CNMutableContact` الفعلية).
 */
public fun CNContact.toIosContactValues(): IosContactValues = IosContactValues(
    identifier = identifier,
    namePrefix = namePrefix.takeIf { it.isNotBlank() },
    givenName = givenName.takeIf { it.isNotBlank() },
    middleName = middleName.takeIf { it.isNotBlank() },
    familyName = familyName.takeIf { it.isNotBlank() },
    nameSuffix = nameSuffix.takeIf { it.isNotBlank() },
    nickname = nickname.takeIf { it.isNotBlank() },
    phoneticFirstName = phoneticGivenName.takeIf { it.isNotBlank() },
    phoneticMiddleName = phoneticMiddleName.takeIf { it.isNotBlank() },
    phoneticFamilyName = phoneticFamilyName.takeIf { it.isNotBlank() },
    organizationName = organizationName.takeIf { it.isNotBlank() },
    departmentName = departmentName.takeIf { it.isNotBlank() },
    jobTitle = jobTitle.takeIf { it.isNotBlank() },
    note = note.takeIf { it.isNotBlank() },
    phones = phoneNumbers
        .filterIsInstance<CNLabeledValue>()
        .mapNotNull { labeled ->
            val raw = (labeled.value as? CNPhoneNumber)?.stringValue ?: return@mapNotNull null
            IosPhoneValue(raw = raw, digits = null, label = normalizeCnLabel(labeled.label))
        },
    emails = emailAddresses
        .filterIsInstance<CNLabeledValue>()
        .mapNotNull { labeled ->
            val address = copyingValueToString(labeled.value) ?: return@mapNotNull null
            IosLabeledValue(label = normalizeCnLabel(labeled.label), value = address)
        },
    urlAddresses = urlAddresses
        .filterIsInstance<CNLabeledValue>()
        .mapNotNull { labeled ->
            val url = copyingValueToString(labeled.value) ?: return@mapNotNull null
            IosLabeledValue(label = normalizeCnLabel(labeled.label), value = url)
        }
)

/** العناوين البريدية والتواريخ/الحقول المخصصة غير منقولة في العقد المجمد (يُؤجل بتوسيع PlatformContact). */
private fun copyingValueToString(value: Any?): String? = (value as? String)?.takeIf { it.isNotBlank() }

/** تطبيع تسميات النظام `CNLabel*` إلى سلسلة lowercase يفهمها الطابق النقي (mapIosLabelType). */
private fun normalizeCnLabel(label: String?): String? = when (label) {
    null -> null
    CNLabelHome -> "home"
    CNLabelWork -> "work"
    CNLabelOther -> "other"
    CNLabelPhoneNumberMobile -> "mobile"
    else -> label.takeIf { it.isNotBlank() }
}

/** NSData → ByteArray (نسخة صريحة — بلا الاعتماد على واجهات API مُرجّحة). */
@OptIn(ExperimentalForeignApi::class)
private fun NSData.iosDataToByteArray(): ByteArray {
    val byteCount = length.toInt()
    if (byteCount <= 0) return ByteArray(0)
    val source = bytes?.reinterpret<ByteVar>() ?: return ByteArray(0)
    return source.readBytes(byteCount)
}

/** ByteArray → NSData عبر memScoped (يبقى كامن الأصل حتى إنهاء النسخ). */
@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.iosByteArrayToData(): NSData = memScoped {
    if (isEmpty()) return@memScoped NSData.data()
    usePinned { pinned ->
        NSData.dataWithBytes(bytes = pinned.addressOf(0), length = size.toULong())
    }
}
