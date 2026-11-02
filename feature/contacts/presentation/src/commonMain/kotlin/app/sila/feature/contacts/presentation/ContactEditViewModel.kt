package app.sila.feature.contacts.presentation

import androidx.lifecycle.viewModelScope
import app.sila.core.common.AppResult
import app.sila.core.common.BaseMviViewModel
import app.sila.core.common.PhoneNormalizer
import app.sila.core.model.Address
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.CustomField
import app.sila.core.model.Email
import app.sila.core.model.ImportantDate
import app.sila.core.model.Organization
import app.sila.core.model.Phone
import app.sila.feature.contacts.domain.CreateArhamOccasionUseCase
import app.sila.feature.contacts.domain.FindDuplicateContactUseCase
import app.sila.feature.contacts.domain.ObserveContactDetailsUseCase
import app.sila.feature.contacts.domain.SaveContactUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** صف هاتف قابل للتحرير — [key] مستقر لعمليات إضافة/حذف/إعادة ترتيب القائمة (AC). */
data class EditablePhoneRow(val key: Long, val raw: String = "", val type: String = "MOBILE")

/** صف بريد قابل للتحرير. */
data class EditableEmailRow(val key: Long, val address: String = "", val type: String = "PERSONAL")

/** صف موقع إلكتروني قابل للتحرير. */
data class EditableWebsiteRow(val key: Long, val url: String = "")

/** صف تاريخ مهم قابل للتحرير — شهر/يوم إلزاميان للفعل، والسنة اختيارية (عيد ميلاد بلا سنة شائع). */
data class EditableDateRow(
    val key: Long,
    // قيم ImportantDate.type الموثقة في core:model (BIRTHDAY/ANNIVERSARY/CUSTOM).
    val type: String = "BIRTHDAY",
    val month: Int? = null,
    val day: Int? = null,
    val year: Int? = null,
)

/** صف عنوان بريدي قابل للتحرير. */
data class EditableAddressRow(
    val key: Long,
    val street: String = "",
    val city: String = "",
    val region: String = "",
    val postalCode: String = "",
    val country: String = "",
    val type: String = "HOME",
)

/** صف حقل مخصص قابل للتحرير. */
data class EditableCustomFieldRow(val key: Long, val label: String = "", val value: String = "")

/** حقول الاسم المُحرَّرة — تتضمن «حفظ باسم» والنطقي (المتقدمة خلف «إظهار المزيد»). */
enum class ContactEditNameField { PREFIX, FIRST_NAME, MIDDLE_NAME, LAST_NAME, SUFFIX, NICKNAME, FILE_AS, PHONETIC }

/** حقول المنظمة المُحرَّرة. */
enum class ContactEditOrgField { COMPANY, DEPARTMENT, JOB_TITLE }

/** حقول العنوان البريدي المُحرَّرة. */
enum class ContactEditAddressField { STREET, CITY, REGION, POSTAL_CODE, COUNTRY }

/** مواضع الحقل المخصص. */
enum class ContactEditCustomSlot { LABEL, VALUE }

/** أخطاء تحقق الحفظ — تُترجَم في العرض (لا نصوص ثابتة في الكود). */
enum class ContactEditValidationError { NAME_OR_PHONE_REQUIRED }

/**
 * حالة شاشة الإضافة/التعديل (T6 · Issue #27 · docs/features/03 §ب).
 *
 * **عقد مشترك (Shared contract):** يستهلكه T4 (ربط iOS) والشاشة المشتركة — أي تغيير
 * في حقوله تغيير عقد مشترك يتطلب مراجعة منسّقة.
 *
 * بايتات الصورة المنتقاة **لا تسكن هذه الحالة** (تُبَث عبر [ContactEditViewModel.pendingPhotoBytes])
 * — تكافؤ بنيوي مع دلالات [SaveContactUseCase]: `null` = لا تغيير، وقيمتها = استبدال،
 * و[ContactEditUiState.isPhotoRemoved] = إزالة صريحة.
 */
data class ContactEditUiState(
    val isLoading: Boolean = true,
    /** الجهة المطلوب تحريرها غير موجودة (قبر/محذوفة) — العرض يعود للقائمة. */
    val isNotFound: Boolean = false,
    val isNewContact: Boolean = true,
    // ---- الاسم (الأساسي) ----
    val firstName: String = "",
    val lastName: String = "",
    // ---- الاسم (المتقدم) ----
    val prefix: String = "",
    val middleName: String = "",
    val suffix: String = "",
    val nickname: String = "",
    val fileAs: String = "",
    val phoneticName: String = "",
    // ---- القوائم المتعددة ----
    val phones: List<EditablePhoneRow> = emptyList(),
    val emails: List<EditableEmailRow> = emptyList(),
    val websites: List<EditableWebsiteRow> = emptyList(),
    // ---- المنظمة ----
    val company: String = "",
    val department: String = "",
    val jobTitle: String = "",
    // ---- الحقول صِلَة-فقط (Room حصراً — ADR-0012) ----
    val notes: String = "",
    val dates: List<EditableDateRow> = emptyList(),
    val addresses: List<EditableAddressRow> = emptyList(),
    val customFields: List<EditableCustomFieldRow> = emptyList(),
    // ---- التصنيف والمفضلة ----
    val category: Category = Category.UNCATEGORIZED,
    val isFavorite: Boolean = false,
    // ---- الصورة ----
    /** مرآة الصورة الحالية (من الأصل) — العرض بنمط القائمة (أحرف أولى عند الغياب). */
    val photoUri: String? = null,
    /** طلب إزالة صريح للصورة (يُكتب فارغاً للمنصة عند الحفظ). */
    val isPhotoRemoved: Boolean = false,
    // ---- الحساب (قراءة فقط في هذه الدورة — منتقي الحسابات مؤجل) ----
    /** `null` = حساب الجهاز (إنشاء جديد) — الحمل من الأصل عند التعديل (ADR-0012). */
    val account: app.sila.core.model.ContactAccount? = null,
    // ---- الميتا ----
    /** «إظهار المزيد» — يتذكر حالته؛ وتُفتح تلقائياً عند التعديل إن وُجدت بيانات متقدمة (AC). */
    val isExpanded: Boolean = false,
    val isSaving: Boolean = false,
    val isDirty: Boolean = false,
    /** Sheet «صنّف قبل الحفظ» — يظهر عند الحفظ والتصنيف لم يُختر (AC). */
    val showCategoryPrompt: Boolean = false,
    val validationError: ContactEditValidationError? = null,
    /** تحذير التكرار غير المانع (J-9) — مفتاح صف الهاتف ← اسم جهة قائمة بنفس الرقم. */
    val duplicateWarning: Map<Long, String> = emptyMap(),
) {
    /** الاسم المركّب كما سيُحفظ — للمعاينة في الرأس (رقم أول بلا اسم). */
    val displayNamePreview: String
        get() = composedDisplayName(this).ifBlank {
            phones.firstNotNullOfOrNull { it.raw.trim().takeIf(String::isNotBlank) } ?: ""
        }

    /** قيم المتقدمة وُجدت؟ (لفتح «إظهار المزيد» تلقائياً عند التعديل — AC). */
    val hasAdvancedData: Boolean
        get() = prefix.isNotBlank() || middleName.isNotBlank() || suffix.isNotBlank() ||
            nickname.isNotBlank() || fileAs.isNotBlank() || phoneticName.isNotBlank() ||
            emails.isNotEmpty() || websites.isNotEmpty() || company.isNotBlank() ||
            department.isNotBlank() || jobTitle.isNotBlank() || dates.isNotEmpty() ||
            addresses.isNotEmpty() || customFields.isNotEmpty() || notes.isNotBlank()
}

/** نوايا شاشة الإضافة/التعديل (T6) — العرض يُرسل ولا يحمل منطق أعمال. */
sealed interface ContactEditIntent {
    data class SetNameField(val field: ContactEditNameField, val value: String) : ContactEditIntent
    data class SetOrganizationField(val field: ContactEditOrgField, val value: String) : ContactEditIntent
    data class SetNotes(val value: String) : ContactEditIntent
    data class SetCategory(val category: Category) : ContactEditIntent
    data object ToggleFavorite : ContactEditIntent
    data class SetExpanded(val expanded: Boolean) : ContactEditIntent

    data object AddPhone : ContactEditIntent
    data class UpdatePhone(val key: Long, val raw: String) : ContactEditIntent
    data class UpdatePhoneType(val key: Long, val type: String) : ContactEditIntent
    data class RemovePhone(val key: Long) : ContactEditIntent

    data object AddEmail : ContactEditIntent
    data class UpdateEmail(val key: Long, val address: String) : ContactEditIntent
    data class UpdateEmailType(val key: Long, val type: String) : ContactEditIntent
    data class RemoveEmail(val key: Long) : ContactEditIntent

    data object AddWebsite : ContactEditIntent
    data class UpdateWebsite(val key: Long, val url: String) : ContactEditIntent
    data class RemoveWebsite(val key: Long) : ContactEditIntent

    data object AddDate : ContactEditIntent
    data class UpdateDate(val key: Long, val type: String, val month: Int?, val day: Int?, val year: Int?) : ContactEditIntent
    data class RemoveDate(val key: Long) : ContactEditIntent

    data object AddAddress : ContactEditIntent
    data class UpdateAddressField(val key: Long, val field: ContactEditAddressField, val value: String) : ContactEditIntent
    data class RemoveAddress(val key: Long) : ContactEditIntent

    data object AddCustomField : ContactEditIntent
    data class UpdateCustomField(val key: Long, val slot: ContactEditCustomSlot, val value: String) : ContactEditIntent
    data class RemoveCustomField(val key: Long) : ContactEditIntent

    /** بايتات صورة منتقاة من الكاميرا/المعرض — غير معالَجة؛ [SaveContactUseCase] يعالجها. */
    class SetPhotoPicked(val bytes: ByteArray) : ContactEditIntent
    data object RemovePhoto : ContactEditIntent

    /** طلب فتح كاميرا الجهاز — الغلاف يلتقط ويعيد البايتات عبر [SetPhotoPicked]. */
    data object RequestPhotoCamera : ContactEditIntent

    /** طلب فتح معرض الصور — الغلاف ينتقي ويعيد البايتات عبر [SetPhotoPicked]. */
    data object RequestPhotoGallery : ContactEditIntent

    data object Save : ContactEditIntent

    /** تأكيد Sheet التصنيف: `null` = «لاحقاً» (يُحفظ UNCATEGORIZED)، وإلا يُختر التصنيف ثم يُحفظ. */
    data class ConfirmCategory(val category: Category?) : ContactEditIntent
    data object DismissCategoryPrompt : ContactEditIntent

    /** الخروج بلا حفظ — النموذج يفحص [ContactEditUiState.isDirty] لعرض التأكيد (AC) ثم يُرسل هذا. */
    data object DiscardChanges : ContactEditIntent
}

/** آثار جانبية لغلاف شاشة التحرير (تُجمَع في androidApp/T4). */
sealed interface ContactEditEffect {
    data object NavigateBack : ContactEditEffect

    /** فشل الحفظ — رسالة مُترجمة ويبقى النموذج مفتوحاً بحالته. */
    data object ShowSaveError : ContactEditEffect

    /** التقاط صورة بالكاميرا — البايتات تعود عبر [ContactEditIntent.SetPhotoPicked]. */
    data object PickFromCamera : ContactEditEffect

    /** اختيار صورة من المعرض — البايتات تعود عبر [ContactEditIntent.SetPhotoPicked]. */
    data object PickFromGallery : ContactEditEffect
}

/**
 * ViewModel شاشة الإضافة/التعديل (T6 · Issue #27) — MVI/UDF.
 *
 * - **[contactId] = `null`** نموذج إنشاء فارغ؛ غيرها نموذج تحرير يُملأ مرة من لقطة
 *   [ObserveContactDetailsUseCase.getOnce] — النموذج لا يُحدَّث حياً وسط التحرير
 *   (تتبع الاتساخ مقابل اللقطة المُحمَّلة).
 * - **تتبع الاتساخ:** لقطة أولية تُقارن بالحالة الحالية بعد كل تعديل — الصورة تُحسب
 *   متسخة فقط عند اقتران/إزالة فعلية (صورة أصل موجودة).
 * - **فحص التكرار (J-9):** محلي Room حصراً عبر [FindDuplicateContactUseCase]،
 *   debounce 400ms، تحذير غير مانع — يُعرض بجانب صف الهاتف.
 * - **الحفظ:** Sheet تصنيف عند UNCATEGORIZED (AC) ثم [SaveContactUseCase] — ميثاق
 *   الاتساق J-1 كله بداخله. بعد النجاح: جهة **عائلة** بعيد ميلاد وبلا رابط أرحام قائم
 *   ← [CreateArhamOccasionUseCase] (J-4 — جانبي، لا يُفشل الحفظ).
 * - الحفظ يكتب دفتر الهاتف + Room بعملية واحدة منطقية والفشل يتراجع (AC — J-1).
 */
class ContactEditViewModel(
    private val contactId: Long?,
    private val observeDetails: ObserveContactDetailsUseCase,
    private val saveContact: SaveContactUseCase,
    private val createArhamOccasion: CreateArhamOccasionUseCase,
    private val findDuplicate: FindDuplicateContactUseCase,
) : BaseMviViewModel<ContactEditUiState, ContactEditIntent, ContactEditEffect>(
    ContactEditUiState(isLoading = contactId != null, isNewContact = contactId == null)
) {

    /** الأصل المحمَّل (تعديل) — منه تُبنى الصياغة وتُحمل حقول صِلَة-فقط غير المعروضة. */
    private var original: Contact? = null

    /** اللقطة المرجعية لتتبع الاتساخ — تُلتقط بعد اكتمال التحميل. */
    private var initialSnapshot: FormSnapshot? = null

    private var nextRowKey = 1L
    private var dupCheckJob: Job? = null

    /** بايتات الصورة المنتقاة (غير المعالَجة) — بثّ جانبي للمعاينة، خارج [ContactEditUiState]. */
    private val pendingPhotoBytesFlow = MutableStateFlow<ByteArray?>(null)
    val pendingPhotoBytes: StateFlow<ByteArray?> = pendingPhotoBytesFlow.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = contactId?.let { observeDetails.getOnce(it) }
            if (contactId != null && loaded == null) {
                updateState { copy(isLoading = false, isNotFound = true) }
                sendEffect(ContactEditEffect.NavigateBack)
                return@launch
            }
            original = loaded
            val filled = loaded?.toFormState() ?: ContactEditUiState(
                // نموذج إنشاء: صف هاتف فارغ جاهز (US-03.1 — اسم برقم وتصنيف فقط بسرعة).
                phones = listOf(EditablePhoneRow(key = nextRowKey())),
            )
            updateState {
                filled.copy(
                    isLoading = false,
                    isNotFound = false,
                    isNewContact = contactId == null,
                    // «إظهار المزيد» يُفتح تلقائياً عند التعديل إن وُجدت بيانات متقدمة (AC).
                    isExpanded = filled.hasAdvancedData,
                    isDirty = false,
                )
            }
            initialSnapshot = currentState.snapshot()
            pendingPhotoBytesFlow.value = null
        }
    }

    override fun onIntent(intent: ContactEditIntent) {
        when (intent) {
            is ContactEditIntent.SetNameField -> mutateForm {
                copy(
                    prefix = setField(prefix, intent.field, ContactEditNameField.PREFIX, intent),
                    firstName = setField(firstName, intent.field, ContactEditNameField.FIRST_NAME, intent),
                    middleName = setField(middleName, intent.field, ContactEditNameField.MIDDLE_NAME, intent),
                    lastName = setField(lastName, intent.field, ContactEditNameField.LAST_NAME, intent),
                    suffix = setField(suffix, intent.field, ContactEditNameField.SUFFIX, intent),
                    nickname = setField(nickname, intent.field, ContactEditNameField.NICKNAME, intent),
                    fileAs = setField(fileAs, intent.field, ContactEditNameField.FILE_AS, intent),
                    phoneticName = setField(phoneticName, intent.field, ContactEditNameField.PHONETIC, intent),
                )
            }
            is ContactEditIntent.SetOrganizationField -> mutateForm {
                copy(
                    company = if (intent.field == ContactEditOrgField.COMPANY) intent.value else company,
                    department = if (intent.field == ContactEditOrgField.DEPARTMENT) intent.value else department,
                    jobTitle = if (intent.field == ContactEditOrgField.JOB_TITLE) intent.value else jobTitle,
                )
            }
            is ContactEditIntent.SetNotes -> mutateForm { copy(notes = intent.value) }
            is ContactEditIntent.SetCategory -> mutateForm { copy(category = intent.category) }
            ContactEditIntent.ToggleFavorite -> mutateForm { copy(isFavorite = !isFavorite) }
            is ContactEditIntent.SetExpanded -> mutateForm { copy(isExpanded = intent.expanded) }

            ContactEditIntent.AddPhone -> {
                mutateForm { copy(phones = phones + EditablePhoneRow(key = nextRowKey())) }
                scheduleDuplicateCheck()
            }
            is ContactEditIntent.UpdatePhone -> {
                mutateForm { copy(phones = phones.map { if (it.key == intent.key) it.copy(raw = intent.raw) else it }) }
                scheduleDuplicateCheck()
            }
            is ContactEditIntent.UpdatePhoneType -> mutateForm {
                copy(phones = phones.map { if (it.key == intent.key) it.copy(type = intent.type) else it })
            }
            is ContactEditIntent.RemovePhone -> {
                mutateForm {
                    copy(
                        phones = phones.filterNot { it.key == intent.key },
                        duplicateWarning = duplicateWarning - intent.key,
                    )
                }
                scheduleDuplicateCheck()
            }

            ContactEditIntent.AddEmail -> mutateForm { copy(emails = emails + EditableEmailRow(key = nextRowKey())) }
            is ContactEditIntent.UpdateEmail -> mutateForm {
                copy(emails = emails.map { if (it.key == intent.key) it.copy(address = intent.address) else it })
            }
            is ContactEditIntent.UpdateEmailType -> mutateForm {
                copy(emails = emails.map { if (it.key == intent.key) it.copy(type = intent.type) else it })
            }
            is ContactEditIntent.RemoveEmail -> mutateForm { copy(emails = emails.filterNot { it.key == intent.key }) }

            ContactEditIntent.AddWebsite -> mutateForm { copy(websites = websites + EditableWebsiteRow(key = nextRowKey())) }
            is ContactEditIntent.UpdateWebsite -> mutateForm {
                copy(websites = websites.map { if (it.key == intent.key) it.copy(url = intent.url) else it })
            }
            is ContactEditIntent.RemoveWebsite -> mutateForm { copy(websites = websites.filterNot { it.key == intent.key }) }

            ContactEditIntent.AddDate -> mutateForm { copy(dates = dates + EditableDateRow(key = nextRowKey())) }
            is ContactEditIntent.UpdateDate -> mutateForm {
                copy(dates = dates.map {
                    if (it.key == intent.key) {
                        it.copy(type = intent.type, month = intent.month, day = intent.day, year = intent.year)
                    } else {
                        it
                    }
                })
            }
            is ContactEditIntent.RemoveDate -> mutateForm { copy(dates = dates.filterNot { it.key == intent.key }) }

            ContactEditIntent.AddAddress -> mutateForm { copy(addresses = addresses + EditableAddressRow(key = nextRowKey())) }
            is ContactEditIntent.UpdateAddressField -> mutateForm {
                copy(addresses = addresses.map {
                    if (it.key == intent.key) {
                        when (intent.field) {
                            ContactEditAddressField.STREET -> it.copy(street = intent.value)
                            ContactEditAddressField.CITY -> it.copy(city = intent.value)
                            ContactEditAddressField.REGION -> it.copy(region = intent.value)
                            ContactEditAddressField.POSTAL_CODE -> it.copy(postalCode = intent.value)
                            ContactEditAddressField.COUNTRY -> it.copy(country = intent.value)
                        }
                    } else {
                        it
                    }
                })
            }
            is ContactEditIntent.RemoveAddress -> mutateForm { copy(addresses = addresses.filterNot { it.key == intent.key }) }

            ContactEditIntent.AddCustomField ->
                mutateForm { copy(customFields = customFields + EditableCustomFieldRow(key = nextRowKey())) }
            is ContactEditIntent.UpdateCustomField -> mutateForm {
                copy(customFields = customFields.map {
                    if (it.key == intent.key) {
                        when (intent.slot) {
                            ContactEditCustomSlot.LABEL -> it.copy(label = intent.value)
                            ContactEditCustomSlot.VALUE -> it.copy(value = intent.value)
                        }
                    } else {
                        it
                    }
                })
            }
            is ContactEditIntent.RemoveCustomField ->
                mutateForm { copy(customFields = customFields.filterNot { it.key == intent.key }) }

            is ContactEditIntent.SetPhotoPicked -> {
                pendingPhotoBytesFlow.value = intent.bytes
                mutateForm { copy(isPhotoRemoved = false) }
            }
            ContactEditIntent.RemovePhoto -> {
                pendingPhotoBytesFlow.value = null
                mutateForm { copy(isPhotoRemoved = true) }
            }
            // منتقي الصور منصوي — الغلاف يجمَع الأثر ويعيد البايتات عبر [SetPhotoPicked].
            ContactEditIntent.RequestPhotoCamera -> sendEffect(ContactEditEffect.PickFromCamera)
            ContactEditIntent.RequestPhotoGallery -> sendEffect(ContactEditEffect.PickFromGallery)

            ContactEditIntent.Save -> onSaveRequested()
            is ContactEditIntent.ConfirmCategory -> {
                updateState { copy(showCategoryPrompt = false, category = intent.category ?: category) }
                performSave()
            }
            ContactEditIntent.DismissCategoryPrompt -> updateState { copy(showCategoryPrompt = false) }

            ContactEditIntent.DiscardChanges -> sendEffect(ContactEditEffect.NavigateBack)
        }
    }

    // ------------------------------------------------------------------ الحفظ

    /** الحد الأدنى للحفظ: اسم **أو** رقم (AC) — وإلا خطأ تحقق غير مانع. */
    private fun onSaveRequested() {
        val s = currentState
        if (s.isSaving || s.showCategoryPrompt || s.isLoading) return
        val hasName = composedDisplayName(s).isNotBlank()
        val hasPhone = s.phones.any { it.raw.trim().isNotBlank() }
        if (!hasName && !hasPhone) {
            updateState { copy(validationError = ContactEditValidationError.NAME_OR_PHONE_REQUIRED) }
            return
        }
        if (s.category == Category.UNCATEGORIZED) {
            // التصنيف لم يُختر — Sheet «صنّف قبل الحفظ» مع خيار «لاحقاً» (AC).
            updateState { copy(showCategoryPrompt = true, validationError = null) }
            return
        }
        performSave()
    }

    /** تنفيذ الحفظ عبر [SaveContactUseCase] — ميثاق J-1 كله داخل حالة الاستخدام. */
    private fun performSave() = viewModelScope.launch {
        val s = currentState
        val orig = original
        // دفاع: تحرير جهة لم تُحمَّل فعلاً — لا يحتمل (التحميل يملأ النموذج أو يغلق الشاشة).
        if (contactId != null && orig == null) return@launch
        updateState { copy(isSaving = true, validationError = null) }
        val draft = buildDraft(s)
        val result = saveContact(
            original = orig,
            draft = draft,
            newPhotoBytes = pendingPhotoBytesFlow.value,
            removePhoto = s.isPhotoRemoved && orig?.photoUri != null,
        )
        when (result) {
            is AppResult.Success -> {
                maybeRecordArhamOccasion(state = s, original = orig, saved = result.data)
                sendEffect(ContactEditEffect.NavigateBack)
            }
            is AppResult.Error -> {
                updateState { copy(isSaving = false) }
                sendEffect(ContactEditEffect.ShowSaveError)
            }
        }
    }

    /**
     * J-4: جهة **عائلة** بعيد ميلاد محفوظ وبلا رابط أرحام سابق ← تسجيل المناسبة
     * عبر [CreateArhamOccasionUseCase] — جانبي بنيوياً: فشله لا يُفشل الحفظ
     * (الحفظ نجح فعلاً)، والجسر نفسه idempotent.
     */
    private fun maybeRecordArhamOccasion(state: ContactEditUiState, original: Contact?, saved: Contact) {
        if (saved.category != Category.FAMILY) return
        if (original?.arham != null) return
        val birthday = saved.dates.firstOrNull { it.type == IMPORTANT_DATE_BIRTHDAY } ?: return
        viewModelScope.launch {
            createArhamOccasion(contactId = saved.id, month = birthday.month, day = birthday.day)
        }
    }

    /**
     * بناء [Contact] من النموذج — الحقول المعروضة من الحالة، وحقول صِلَة-فقط غير
     * المعروضة (أرحام/رنة/صد صوتي/حجب/وسوم/خدمة مرتبطة/الحساب) تُحمَل من الأصل حرفياً
     * (عقد [SaveContactUseCase]: الصياغة تحمل كل الحقول).
     */
    private fun buildDraft(s: ContactEditUiState): Contact {
        val orig = original
        val displayName = composedDisplayName(s).ifBlank {
            s.phones.firstNotNullOfOrNull { it.raw.trim().takeIf(String::isNotBlank) } ?: ""
        }

        // الهواتف: تطبيع E.164 (تحقق غير مانع — AC)، وحمل label/isPrimary (صِلَة-فقط)
        // من الأصل بمطابقة النص، وضمان هوية أولى واحدة (عقد الاستدعاء في التفاصيل).
        val phones = s.phones.mapNotNull { row ->
            val raw = row.raw.trim()
            if (raw.isBlank()) return@mapNotNull null
            val e164 = PhoneNormalizer.normalizeToE164(raw)
            val match = orig?.phones?.firstOrNull { it.raw == raw }
            Phone(
                raw = raw,
                e164 = e164.ifBlank { raw },
                type = row.type,
                label = match?.label,
                isPrimary = match?.isPrimary ?: false,
            )
        }.let { list ->
            if (list.isNotEmpty() && list.none { it.isPrimary }) {
                list.mapIndexed { index, phone -> if (index == 0) phone.copy(isPrimary = true) else phone }
            } else {
                list
            }
        }

        val emails = s.emails.mapNotNull { row ->
            val address = row.address.trim()
            if (address.isBlank()) {
                null
            } else {
                Email(address = address, type = row.type, label = orig?.emails?.firstOrNull { it.address == address }?.label)
            }
        }

        val websites = s.websites.map { it.url.trim() }.filter(String::isNotBlank)

        val organization = if (s.company.isBlank() && s.department.isBlank() && s.jobTitle.isBlank()) {
            null
        } else {
            Organization(
                company = s.company.trim().takeIf(String::isNotBlank),
                department = s.department.trim().takeIf(String::isNotBlank),
                jobTitles = listOfNotNull(s.jobTitle.trim().takeIf(String::isNotBlank)),
            )
        }

        val dates = s.dates.mapNotNull { row ->
            val month = row.month ?: return@mapNotNull null
            val day = row.day ?: return@mapNotNull null
            ImportantDate(type = row.type, month = month, day = day, year = row.year)
        }

        val addresses = s.addresses
            .filter { row ->
                !row.street.isBlank() || !row.city.isBlank() || !row.region.isBlank() ||
                    !row.postalCode.isBlank() || !row.country.isBlank()
            }
            .map { row ->
                Address(
                    street = row.street.trim().takeIf(String::isNotBlank),
                    city = row.city.trim().takeIf(String::isNotBlank),
                    region = row.region.trim().takeIf(String::isNotBlank),
                    postalCode = row.postalCode.trim().takeIf(String::isNotBlank),
                    country = row.country.trim().takeIf(String::isNotBlank),
                    type = row.type,
                )
            }

        val customFields = s.customFields
            .filter { it.label.isNotBlank() || it.value.isNotBlank() }
            .map { CustomField(label = it.label.trim(), value = it.value.trim()) }

        return Contact(
            id = orig?.id ?: 0L,
            systemContactId = orig?.systemContactId ?: "",
            lookupKey = orig?.lookupKey,
            name = ContactName(
                displayName = displayName,
                fileAs = s.fileAs.trim().ifBlank { displayName },
                prefix = s.prefix.trim().takeIf(String::isNotBlank),
                firstName = s.firstName.trim().takeIf(String::isNotBlank),
                middleName = s.middleName.trim().takeIf(String::isNotBlank),
                lastName = s.lastName.trim().takeIf(String::isNotBlank),
                suffix = s.suffix.trim().takeIf(String::isNotBlank),
                nickname = s.nickname.trim().takeIf(String::isNotBlank),
                phoneticName = s.phoneticName.trim().takeIf(String::isNotBlank),
            ),
            photoUri = if (s.isPhotoRemoved) null else orig?.photoUri,
            phones = phones,
            emails = emails,
            addresses = addresses,
            organization = organization,
            dates = dates,
            customFields = customFields,
            websites = websites,
            tags = orig?.tags ?: emptyList(),
            category = s.category,
            isFavorite = s.isFavorite,
            ringtoneUri = orig?.ringtoneUri,
            sendToVoicemail = orig?.sendToVoicemail ?: false,
            isBlocked = orig?.isBlocked ?: false,
            notes = s.notes.trim().takeIf(String::isNotBlank),
            account = orig?.account,
            arham = orig?.arham,
            linkedService = orig?.linkedService,
            normalizedName = displayName,
            createdAt = orig?.createdAt ?: 0L,
            updatedAt = 0L, // حالة الاستخدام تختُم الوقت الفعلي للحفظ.
        )
    }

    // ------------------------------------------------------------------ فحص التكرار (J-9)

    /**
     * فحص محلي غير مانع — debounce 400ms بعد آخر تعديل هاتف (J-9). التطبيع عبر
     * [PhoneNormalizer]، والاستبعاد يمنع اعتبار الجهة المحرَّرة نفسها تكراراً.
     */
    private fun scheduleDuplicateCheck() {
        dupCheckJob?.cancel()
        val phones = currentState.phones
        if (phones.none { it.raw.trim().isNotBlank() }) {
            updateState { copy(duplicateWarning = emptyMap()) }
            return
        }
        dupCheckJob = viewModelScope.launch {
            delay(DUPLICATE_CHECK_DEBOUNCE_MILLIS)
            val excludeId = original?.id
            val warnings = mutableMapOf<Long, String>()
            for (row in currentState.phones) {
                val raw = row.raw.trim()
                if (raw.isBlank() || !PhoneNormalizer.isValidPhone(raw)) continue
                val e164 = PhoneNormalizer.normalizeToE164(raw)
                val existing = findDuplicate(e164 = e164, excludeContactId = excludeId).firstOrNull() ?: continue
                warnings[row.key] = existing.name.displayName
            }
            updateState { copy(duplicateWarning = warnings) }
        }
    }

    // ------------------------------------------------------------------ أدوات النموذج

    /** مسار كل تعديلات النموذج: تطبيق ← إعادة حساب الاتساخ مقابل اللقطة. */
    private fun mutateForm(reduce: ContactEditUiState.() -> ContactEditUiState) {
        val next = currentState.reduce()
        updateState {
            next.copy(isDirty = computeDirty(next), validationError = null)
        }
    }

    /** الاتساخ = اختلاف النموذج عن اللقطة، أو صورة منتقاة، أو إزالة صورة قائمة فعلاً. */
    private fun computeDirty(state: ContactEditUiState): Boolean {
        val photoDirty = pendingPhotoBytesFlow.value != null ||
            (state.isPhotoRemoved && original?.photoUri != null)
        return state.snapshot() != initialSnapshot || photoDirty
    }

    private fun nextRowKey(): Long = nextRowKey++

    /** تحميل جهة قائمة إلى نموذج — حقول صِلَة-فقط تُعرض (ملاحظات/تواريخ/عناوين/حقول مخصصة). */
    private fun Contact.toFormState(): ContactEditUiState = ContactEditUiState(
        isLoading = true,
        isNewContact = false,
        prefix = name.prefix.orEmpty(),
        firstName = name.firstName.orEmpty(),
        middleName = name.middleName.orEmpty(),
        lastName = name.lastName.orEmpty(),
        suffix = name.suffix.orEmpty(),
        nickname = name.nickname.orEmpty(),
        fileAs = name.fileAs,
        phoneticName = name.phoneticName.orEmpty(),
        phones = phones.map { EditablePhoneRow(key = nextRowKey(), raw = it.raw, type = it.type) },
        emails = emails.map { EditableEmailRow(key = nextRowKey(), address = it.address, type = it.type) },
        websites = websites.map { EditableWebsiteRow(key = nextRowKey(), url = it) },
        company = organization?.company.orEmpty(),
        department = organization?.department.orEmpty(),
        jobTitle = organization?.jobTitles?.firstOrNull().orEmpty(),
        notes = notes.orEmpty(),
        dates = dates.map {
            EditableDateRow(key = nextRowKey(), type = it.type, month = it.month, day = it.day, year = it.year)
        },
        addresses = addresses.map {
            EditableAddressRow(
                key = nextRowKey(),
                street = it.street.orEmpty(),
                city = it.city.orEmpty(),
                region = it.region.orEmpty(),
                postalCode = it.postalCode.orEmpty(),
                country = it.country.orEmpty(),
                type = it.type,
            )
        },
        customFields = customFields.map { EditableCustomFieldRow(key = nextRowKey(), label = it.label, value = it.value) },
        category = category,
        isFavorite = isFavorite,
        photoUri = photoUri,
        account = account,
    )

    private companion object {
        /** J-9 — فحص محلي بعد سكون الإدخال. */
        const val DUPLICATE_CHECK_DEBOUNCE_MILLIS = 400L

        /** نوع التاريخ: عيد ميلاد (قيم ImportantDate الموثقة في core:model). */
        const val IMPORTANT_DATE_BIRTHDAY = "BIRTHDAY"
    }
}

/** الاسم المركّب من المكوّنات — «حفظ باسم» ليس جزءاً منه (fileAs ترتيب عرض). */
private fun composedDisplayName(state: ContactEditUiState): String =
    listOf(state.prefix, state.firstName, state.middleName, state.lastName, state.suffix)
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString(" ")

/** قيمة حقل نصي مُحدَّد من تعداد حقول الاسم. */
private fun setField(current: String, target: ContactEditNameField, expected: ContactEditNameField, intent: ContactEditIntent.SetNameField): String =
    if (target == expected) intent.value else current

/**
 * اللقطة المرجعية لتتبع الاتساخ — حقول أولية حصراً (بلا بايتات/خرائط مشتقة) —
 * مقارنة المساواة البيانية كافية ومحددة.
 */
private data class FormSnapshot(
    val prefix: String,
    val firstName: String,
    val middleName: String,
    val lastName: String,
    val suffix: String,
    val nickname: String,
    val fileAs: String,
    val phoneticName: String,
    val phones: List<EditablePhoneRow>,
    val emails: List<EditableEmailRow>,
    val websites: List<EditableWebsiteRow>,
    val company: String,
    val department: String,
    val jobTitle: String,
    val notes: String,
    val dates: List<EditableDateRow>,
    val addresses: List<EditableAddressRow>,
    val customFields: List<EditableCustomFieldRow>,
    val category: Category,
    val isFavorite: Boolean,
)

private fun ContactEditUiState.snapshot(): FormSnapshot = FormSnapshot(
    prefix = prefix,
    firstName = firstName,
    middleName = middleName,
    lastName = lastName,
    suffix = suffix,
    nickname = nickname,
    fileAs = fileAs,
    phoneticName = phoneticName,
    phones = phones,
    emails = emails,
    websites = websites,
    company = company,
    department = department,
    jobTitle = jobTitle,
    notes = notes,
    dates = dates,
    addresses = addresses,
    customFields = customFields,
    category = category,
    isFavorite = isFavorite,
)
