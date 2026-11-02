package app.sila.feature.contacts.data

import app.sila.core.common.AppResult
import app.sila.core.common.DataError
import app.sila.core.model.Email
import app.sila.core.model.Phone
import app.sila.feature.contacts.domain.ContactsPlatformWriter
import app.sila.feature.contacts.domain.CreatedPlatformContact
import app.sila.feature.contacts.domain.PlatformWritableContact
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Contacts.CNAuthorizationStatusAuthorized
import platform.Contacts.CNAuthorizationStatusLimited
import platform.Contacts.CNAuthorizationStatusNotDetermined
import platform.Contacts.CNContact
import platform.Contacts.CNContactDepartmentNameKey
import platform.Contacts.CNContactEmailAddressesKey
import platform.Contacts.CNContactFamilyNameKey
import platform.Contacts.CNContactGivenNameKey
import platform.Contacts.CNContactIdentifierKey
import platform.Contacts.CNContactImageDataKey
import platform.Contacts.CNContactJobTitleKey
import platform.Contacts.CNContactMiddleNameKey
import platform.Contacts.CNContactNamePrefixKey
import platform.Contacts.CNContactNameSuffixKey
import platform.Contacts.CNContactNicknameKey
import platform.Contacts.CNContactOrganizationNameKey
import platform.Contacts.CNContactPhoneticFamilyNameKey
import platform.Contacts.CNContactPhoneticGivenNameKey
import platform.Contacts.CNContactPhoneticMiddleNameKey
import platform.Contacts.CNContactPhoneNumbersKey
import platform.Contacts.CNContactStore
import platform.Contacts.CNContactUrlAddressesKey
import platform.Contacts.CNEntityType
import platform.Contacts.CNLabelHome
import platform.Contacts.CNLabelOther
import platform.Contacts.CNLabelPhoneNumberMobile
import platform.Contacts.CNLabelWork
import platform.Contacts.CNLabeledValue
import platform.Contacts.CNMutableContact
import platform.Contacts.CNPhoneNumber
import platform.Contacts.CNSaveRequest
import platform.Contacts.predicateForContactsWithIdentifiers
import platform.Foundation.NSCopyingProtocol
import platform.Foundation.NSError
import platform.Foundation.NSString
import platform.Foundation.create
import kotlin.coroutines.resume

/**
 * تنفيذ iOS لعقد الكتابة [ContactsPlatformWriter] (T6 · Issue #27 · ADR-0012):
 *
 * - **الإنشاء:** `CNMutableContact` جديد → `CNSaveRequest.addContact` (الحاوية
 *   الافتراضية — [PlatformWritableContact.account] يُهمَل دائماً على iOS حسب العقد)،
 *   ثم يُقرأ `identifier` من الكائن نفسه بعد نجاح التنفيذ (يُخصَّص بعد الحفظ) —
 *   هوية دائمة مطابقة للقارئ (ADR-0009 §4).
 * - **التحديث — طفير ضيق حصراً:** جلب بمفاتيح [updateKeysToFetch] (المُدار حصراً)، ثم
 *   `mutableCopy` يُضبط حقوله المُدارة ويُحفَظ بـ `updateContact`. أي مفتاح **غير
 *   مُجلَب** (ملاحظات/عناوين بريدية/تواريخ/شبكات اجتماعية/علاقات — ملكية صِلَة-فقط أو
 *   غير مُدار) **ينجو حرفياً** — سلوك CNSaveRequest الموثق لحفظ نسخة مقيدة المفاتيح.
 *   الصورة: `photoBytes = null` لا تُدرج `CNContactImageDataKey` أصلاً (لا مسّ)،
 *   الفارغة = `imageData = nil` (إزالة صريحة).
 * - **الحذف:** جلب بالمعرف (مفتاح `identifier` كحده) ثم `deleteContact` — طلب الحذف
 *   يحتاج نسخة قابلة للطفير كاملة الهوية.
 * - **الصلاحية:** `CNContactStore.authorizationStatusForEntityType` —AUTHORIZED/LIMITED
 *   يمرّان (LIMITED = جهات شاركة صراحة — نفس عقد القراءة §5)، `.notDetermined` يطلب
 *   التفويض عبر `requestAccessForEntityType` (نافذة النظام تظهر مرة واحدة)، غير ذلك
 *   [DataError.Permission] بلا استثناءات.
 *
 * دلالات التسميات — عكس قارئ T1 حرفياً ([normalizeCnLabel] ثم `mapIosLabelType`):
 * الأنواع المعروفة (HOME/WORK/MOBILE/OTHER) → ثوابت `CNLabel*`، و`PERSONAL` (افتراضي
 * البريد بلا تسمية) → `nil`، وما عداه تسمية مخصصة بصيغة Apple `_$!<type>!_$`. حقل
 * `Phone.label`/`Email.label` صِلَة-فقط **لا يُكتب أبداً** — التسميات المخصصة الأصلية
 * في دفتر iOS تُعاد اشتقاقها من النوع عند الحفظ، والنسخ الأصلية تنجو في Room
 * (خصوصية ADR-0012). الصوتي: القارئ يُجزِّئه من ثلاثة حقول ويُكتب موحداً في
 * `phoneticGivenName` مع تصفير الوسط/العائلة — لا انجراف عبر حفظات متكررة.
 *
 * ملاحظة: التحقق من هذه الشيفرة على macOS CI حصراً؛ أسماء cinterop مأخوذة من
 * أنماط cinterop 2.4.20 الفعلية (`IosContactsPlatformSourceMappingTest`).
 */
@OptIn(ExperimentalForeignApi::class)
class IosContactsPlatformWriter(
    private val store: CNContactStore = CNContactStore(),
) : ContactsPlatformWriter {

    override suspend fun createContact(writable: PlatformWritableContact): AppResult<CreatedPlatformContact> {
        if (!ensureWriteAuthorized()) return permissionError()
        return runCatching {
            val contact = CNMutableContact()
            applyWritableFields(contact, writable)
            val request = CNSaveRequest()
            request.addContact(contact, null)
            executeSave(request, context = "createContact")
            // identifier يُخصَّص للكائن بعد نجاح الحفظ (سلوك CNSaveRequest الموثق).
            val identifier = contact.identifier
            if (identifier.isBlank()) error("CNMutableContact.identifier was not assigned after save")
            CreatedPlatformContact(systemContactId = identifier, lookupKey = null)
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { t -> AppResult.Error(DataError.Unknown(causeMessage = t.message)) }
        )
    }

    override suspend fun updateContact(writable: PlatformWritableContact): AppResult<Unit> {
        if (!ensureWriteAuthorized()) return permissionError()
        val systemId = writable.systemContactId
            ?: return AppResult.Error(DataError.Validation(field = "systemContactId", reason = "update requires identity"))
        return runCatching {
            val mutable = fetchMutableContact(systemId, keysToFetch = updateKeysToFetch(includeImage = writable.photoBytes != null))
                ?: error("contact not resolvable for identifier $systemId")
            applyWritableFields(mutable, writable)
            val request = CNSaveRequest()
            // cinterop 2.4.20: updateContact/deleteContact بمعامل واحد — الحاوية تُستنتج
            // من هوية النسخة نفسها (addContact وحده يأخذ toContainerWithIdentifier).
            request.updateContact(mutable)
            executeSave(request, context = "updateContact")
            Unit
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { t -> AppResult.Error(DataError.Unknown(causeMessage = t.message)) }
        )
    }

    override suspend fun deleteContact(systemContactId: String, lookupKey: String?): AppResult<Unit> {
        if (!ensureWriteAuthorized()) return permissionError()
        return runCatching {
            val mutable = fetchMutableContact(systemContactId, keysToFetch = listOf(CNContactIdentifierKey))
                ?: error("contact not resolvable for identifier $systemContactId")
            val request = CNSaveRequest()
            // cinterop 2.4.20: deleteContact بمعامل واحد (الهوية داخل النسخة نفسها).
            request.deleteContact(mutable)
            executeSave(request, context = "deleteContact")
            Unit
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { t -> AppResult.Error(DataError.Unknown(causeMessage = t.message)) }
        )
    }

    // ---------------------------------------------------------------- ضبط الحقول المُدارة

    /**
     * ضبط كل الحقول المُدارة على نسخة قابلة للطفير — المجموعات تُكتب كاملة (عقد الكاتب).
     * cinterop 2.4.20: مواضع CNMutableContact تقبل `String` غير فارغ — الإفراغ يُكتب
     * سلسلة فارغة (القارئ يعيدها null عبر `isNotBlank` — round-trip سليم).
     */
    private fun applyWritableFields(contact: CNMutableContact, writable: PlatformWritableContact) {
        val name = writable.name
        contact.setNamePrefix(name.prefix.orEmpty())
        contact.setGivenName(name.firstName.orEmpty())
        contact.setMiddleName(name.middleName.orEmpty())
        contact.setFamilyName(name.lastName.orEmpty())
        contact.setNameSuffix(name.suffix.orEmpty())
        contact.setNickname(name.nickname.orEmpty())
        // الصوتي مُجزَّأ في القارئ (فوين/وسط/عائلة) ويُكتب موحداً في الأول مع تصفير
        // الباقي — توحيد موضع بلا فقد، بلا انجراف عبر حفظات متكررة (نفس قرار Android).
        contact.setPhoneticGivenName(name.phoneticName.orEmpty())
        contact.setPhoneticMiddleName("")
        contact.setPhoneticFamilyName("")
        val org = writable.organization
        contact.setOrganizationName(org?.company.orEmpty())
        contact.setDepartmentName(org?.department.orEmpty())
        contact.setJobTitle(org?.jobTitles?.firstOrNull().orEmpty())
        contact.setPhoneNumbers(writable.phones.map { it.toCnLabeledValue() })
        contact.setEmailAddresses(writable.emails.map { it.toCnLabeledValue() })
        contact.setUrlAddresses(writable.websites.map { url -> url.toCnLabeledValue() })
        val bytes = writable.photoBytes
        if (bytes != null) {
            // null = لا مسّ (المفتاح غير مُدرج في keysToFetch أصلاً)؛ فارغة = إزالة صريحة.
            contact.setImageData(if (bytes.isEmpty()) null else bytes.iosBytesToData())
        }
    }

    /** عكس قارئ T1 ([mapIosLabelType]) — الأنواع المعروفة → ثوابت النظام، وإلا تسمية مخصصة. */
    private fun iosCnLabel(type: String): String? = when (type.uppercase()) {
        IOS_TYPE_HOME -> CNLabelHome
        IOS_TYPE_WORK -> CNLabelWork
        IOS_TYPE_MOBILE -> CNLabelPhoneNumberMobile
        IOS_TYPE_OTHER -> CNLabelOther
        // افتراضي البريد القادم بلا تسمية — يُعاد بلا تسمية (round-trip الافتراضي).
        IOS_TYPE_PERSONAL -> null
        else -> customCnLabel(type)
    }

    /** صيغة التسميات المخصصة في دفتر iOS — يعبرها `normalizeCnLabel` كما هي فيعود النوع ذاته. */
    private fun customCnLabel(type: String): String = "_$!<$type>!_$"

    private fun Phone.toCnLabeledValue(): CNLabeledValue = CNLabeledValue(
        label = iosCnLabel(type),
        value = CNPhoneNumber(stringValue = raw),
    )

    private fun Email.toCnLabeledValue(): CNLabeledValue = CNLabeledValue(
        label = iosCnLabel(type),
        value = NSString.create(string = address) as NSCopyingProtocol,
    )

    /** المواقع تُقرأ بلا تسميات (القارئ يُسقطها) — تُكتب بتسمية النظام «أخرى». */
    private fun String.toCnLabeledValue(): CNLabeledValue = CNLabeledValue(
        label = CNLabelOther,
        value = NSString.create(string = this) as NSCopyingProtocol,
    )

    // ---------------------------------------------------------------- جلب وطلب حفظ

    /** جلب جهة بالمعرف بمفاتيح محددة ثم نسخها قابلة للطفير — مدخل الطفير الضيق الوحيد. */
    private fun fetchMutableContact(identifier: String, keysToFetch: List<String>): CNMutableContact? = memScoped {
        val predicate = CNContact.Companion.predicateForContactsWithIdentifiers(listOf(identifier))
        val error = alloc<ObjCObjectVar<NSError?>>()
        val contacts = store.unifiedContactsMatchingPredicate(
            predicate = predicate,
            keysToFetch = keysToFetch,
            error = error.ptr,
        ).orEmpty()
        throwIfError(error.value, context = "fetchContact($identifier)")
        contacts.filterIsInstance<CNContact>()
            .firstOrNull()
            ?.mutableCopy() as? CNMutableContact
    }

    private fun executeSave(request: CNSaveRequest, context: String): Unit = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val success = store.executeSaveRequest(request, error.ptr)
        if (!success || error.value != null) {
            error("Contacts save failed in $context: ${error.value?.localizedDescription ?: "unknown error"}")
        }
    }

    /** مفاتيح التحديث — المُدار حصراً؛ غير المُجلَب ينجو حرفياً عند الحفظ (KDoc الصف). */
    private fun updateKeysToFetch(includeImage: Boolean): List<String> = buildList {
        add(CNContactIdentifierKey)
        add(CNContactNamePrefixKey)
        add(CNContactGivenNameKey)
        add(CNContactMiddleNameKey)
        add(CNContactFamilyNameKey)
        add(CNContactNameSuffixKey)
        add(CNContactNicknameKey)
        add(CNContactPhoneticGivenNameKey)
        // مفاتيح الصوتي المُصفَّر لا بد من جلبها كي يُمسّ تصفيرها فعلاً (كتابة صريحة).
        add(CNContactPhoneticMiddleNameKey)
        add(CNContactPhoneticFamilyNameKey)
        add(CNContactOrganizationNameKey)
        add(CNContactDepartmentNameKey)
        add(CNContactJobTitleKey)
        add(CNContactPhoneNumbersKey)
        add(CNContactEmailAddressesKey)
        add(CNContactUrlAddressesKey)
        if (includeImage) add(CNContactImageDataKey)
    }

    // ---------------------------------------------------------------- الصلاحية

    private suspend fun ensureWriteAuthorized(): Boolean {
        val status = CNContactStore.authorizationStatusForEntityType(CNEntityType.CNEntityTypeContacts)
        if (status == CNAuthorizationStatusAuthorized || status == CNAuthorizationStatusLimited) return true
        if (status != CNAuthorizationStatusNotDetermined) return false
        return requestWriteAccess()
    }

    /** طلب تفويض الكتابة (نافذة النظام — مرة واحدة) بتعليق حتى استجابة المستخدم. */
    private suspend fun requestWriteAccess(): Boolean =
        suspendCancellableCoroutine { continuation ->
            store.requestAccessForEntityType(CNEntityType.CNEntityTypeContacts) { granted, _ ->
                continuation.resume(granted)
            }
        }

    private fun permissionError(): AppResult.Error =
        AppResult.Error(DataError.Permission(permissionName = IOS_PERMISSION_NAME))

    private companion object {
        /** مفتاح وصف الاستخدام في Info.plist — اسم الصلاحية على iOS. */
        const val IOS_PERMISSION_NAME = "NSContactsUsageDescription"

        const val IOS_TYPE_HOME = "HOME"
        const val IOS_TYPE_WORK = "WORK"
        const val IOS_TYPE_MOBILE = "MOBILE"
        const val IOS_TYPE_OTHER = "OTHER"
        const val IOS_TYPE_PERSONAL = "PERSONAL"
    }
}

/** فشل مخزن جهات iOS — يُغلَّف خطأ مُصنَّفاً بلا استثناءات عابرة للحد النطاقي. */
private fun throwIfError(error: NSError?, context: String) {
    if (error != null) {
        error("Contacts operation failed in $context: ${error.localizedDescription}")
    }
}
