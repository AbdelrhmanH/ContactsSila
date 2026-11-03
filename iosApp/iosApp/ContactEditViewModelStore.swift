import Foundation
import Observation
import SharedLogic
import UIKit

/**
 * غلاف حالة شاشة الإضافة/التعديل المشتركة لـ SwiftUI (T6 — Issue #27) بنمط
 * ContactsViewModelStore (T4 — Issue #25).
 *
 * يلتزم بـ:
 * 1. تهيئة الحالة الأولية من `viewModel.state.value` لمنع وميض شاشة فارغة.
 * 2. جمع التحديثات عبر `for await` (SKIE AsyncSequence من `StateFlow<ContactEditUiState>`
 *    و `StateFlow<ByteArray?>` لبايتات الصورة المنتقاة — بثّ جانبي خارج الحالة).
 * 3. العمل على `@MainActor` بالكامل.
 * 4. إعادة توجيه `ContactEditIntent` كما هي — لا حساب حالة محلياً.
 * 5. جمع آثار القناة (جامع واحد): التنقل عبر [onNavigateBack]، ومنتقيات الصور
 *    حالة عرض عابرة (الكاميرا/المعرض) تقدَّم من الواجهة، والخطأ تنبيه بنص مترجم.
 */
@MainActor
@Observable
final class ContactEditViewModelStore {

    private let viewModel: ContactEditViewModel

    private(set) var state: ContactEditUiState

    /** بايتات الصورة المنتقاة (غير المعالَجة) — مرآة pendingPhotoBytes المشتركة. */
    private(set) var pendingPhotoBytes: KotlinByteArray?

    /** Effect عابر: طلب فتح الكاميرا — الواجهة تُطلق UIImagePickerController. */
    private(set) var showCameraPicker = false

    /** Effect عابر: طلب فتح المعرض — الواجهة تُطلق PhotosPicker. */
    private(set) var showGalleryPicker = false

    /** Effect عابر: فشل الحفظ — الواجهة تعرض تنبيهاً بنصها المترجم (لا نص في الكود). */
    private(set) var showSaveError = false

    /** معاير النافذة — يضبطه شريط التنقل (ContentView) لإرجاع المكدس. */
    var onNavigateBack: (() -> Void)?

    /**
     * الراوتر اختياري بافتراض صفرّي ويُبنى داخل جسم الـ init (نمط T4 المجرَّب).
     * [contactId] = `nil` نموذج إنشاء فارغ.
     */
    init(contactId: Int64?) {
        let vm: ContactEditViewModel = IosKoinBootstrap.shared.getContactEditViewModel(
            contactId: contactId.map { KotlinLong(value: $0) }
        )
        self.viewModel = vm
        self.state = vm.state.value
    }

    /// للاختبارات حصراً — حقن VM مباشر (نمط ContactsViewModelStore).
    init(viewModel: ContactEditViewModel) {
        self.viewModel = viewModel
        self.state = viewModel.state.value
    }

    func observeState() async {
        for await newState in viewModel.state {
            self.state = newState
        }
    }

    /// بثّ بايتات الصورة الجانبي — مرآة pendingPhotoBytes (حالة VM غير السكنة في UiState).
    func observePendingPhotoBytes() async {
        for await bytes in viewModel.pendingPhotoBytes {
            self.pendingPhotoBytes = bytes
        }
    }

    /// جامع واحد للقناة حصراً (الشاشات لا تجمعها).
    func observeEffects() async {
        for await effect in viewModel.effects {
            switch effect {
            case is ContactEditEffectNavigateBack:
                onNavigateBack?()
            case is ContactEditEffectShowSaveError:
                showSaveError = true
            case is ContactEditEffectPickFromCamera:
                showCameraPicker = true
            case is ContactEditEffectPickFromGallery:
                showGalleryPicker = true
            default:
                break
            }
        }
    }

    /// إرسال أي [ContactEditIntent] مباشر إلى الـ ViewModel المشترك.
    func send(_ intent: any ContactEditIntent) {
        viewModel.onIntent(intent: intent)
    }

    // مُرسلات مكتوبة النمط (بلا منطق — بناء Intent فقط).

    func setNameField(_ field: ContactEditNameField, _ value: String) {
        send(ContactEditIntentSetNameField(field: field, value: value))
    }

    func setOrganizationField(_ field: ContactEditOrgField, _ value: String) {
        send(ContactEditIntentSetOrganizationField(field: field, value: value))
    }

    func setNotes(_ value: String) {
        send(ContactEditIntentSetNotes(value: value))
    }

    func setCategory(_ category: SharedLogic.Category) {
        send(ContactEditIntentSetCategory(category: category))
    }

    func toggleFavorite() {
        send(ContactEditIntentToggleFavorite.shared)
    }

    func setExpanded(_ expanded: Bool) {
        send(ContactEditIntentSetExpanded(expanded: expanded))
    }

    func addPhone() {
        send(ContactEditIntentAddPhone.shared)
    }

    func updatePhone(_ key: Int64, _ raw: String) {
        send(ContactEditIntentUpdatePhone(key: key, raw: raw))
    }

    func removePhone(_ key: Int64) {
        send(ContactEditIntentRemovePhone(key: key))
    }

    func addEmail() {
        send(ContactEditIntentAddEmail.shared)
    }

    func updateEmail(_ key: Int64, _ address: String) {
        send(ContactEditIntentUpdateEmail(key: key, address: address))
    }

    func removeEmail(_ key: Int64) {
        send(ContactEditIntentRemoveEmail(key: key))
    }

    func addWebsite() {
        send(ContactEditIntentAddWebsite.shared)
    }

    func updateWebsite(_ key: Int64, _ url: String) {
        send(ContactEditIntentUpdateWebsite(key: key, url: url))
    }

    func removeWebsite(_ key: Int64) {
        send(ContactEditIntentRemoveWebsite(key: key))
    }

    // حقول صِلَة-فقط (Room حصراً — ADR-0012): تواريخ/عناوين/حقول مخصصة.

    func addDate() {
        send(ContactEditIntentAddDate.shared)
    }

    /// شهر/يوم إلزاميان للفعل، والسنة اختيارية (عيد ميلاد بلا سنة شائع).
    /// أنواع Swift أصلية (`Int?`) — `.intValue` على KotlinInt (NSNumber) يعيد `Int` هنا.
    /// الحدود الكوتلينية تُعلَّب عند الإرسال، وما لا يتسع لـ Int32 يُعامل كقيمة فارغة —
    /// بمرآة `toIntOrNull` في Compose (تجاوز النطاق = null لا قيمة مبتورة).
    func updateDate(_ key: Int64, type: String, month: Int?, day: Int?, year: Int?) {
        send(ContactEditIntentUpdateDate(
            key: key,
            type: type,
            month: month.flatMap { Int32(exactly: $0) }.map { KotlinInt(value: $0) },
            day: day.flatMap { Int32(exactly: $0) }.map { KotlinInt(value: $0) },
            year: year.flatMap { Int32(exactly: $0) }.map { KotlinInt(value: $0) }
        ))
    }

    func removeDate(_ key: Int64) {
        send(ContactEditIntentRemoveDate(key: key))
    }

    func addAddress() {
        send(ContactEditIntentAddAddress.shared)
    }

    func updateAddressField(_ key: Int64, field: ContactEditAddressField, value: String) {
        send(ContactEditIntentUpdateAddressField(key: key, field: field, value: value))
    }

    func removeAddress(_ key: Int64) {
        send(ContactEditIntentRemoveAddress(key: key))
    }

    func addCustomField() {
        send(ContactEditIntentAddCustomField.shared)
    }

    func updateCustomField(_ key: Int64, slot: ContactEditCustomSlot, value: String) {
        send(ContactEditIntentUpdateCustomField(key: key, slot: slot, value: value))
    }

    func removeCustomField(_ key: Int64) {
        send(ContactEditIntentRemoveCustomField(key: key))
    }

    /// بايتات صورة منتقاة من الكاميرا/المعرض — `nil` إلغاء (لا نية تُرسل — عقد VM).
    func setPhotoPicked(_ bytes: KotlinByteArray?) {
        guard let bytes else { return }
        send(ContactEditIntentSetPhotoPicked(bytes: bytes))
    }

    func removePhoto() {
        send(ContactEditIntentRemovePhoto.shared)
    }

    /// حفظ — الـ VM يفتح Sheet التصنيف عند UNCATEGORIZED قبل التنفيذ (AC).
    func save() {
        send(ContactEditIntentSave.shared)
    }

    /// تأكيد Sheet التصنيف — `nil` = «لاحقاً» (يُحفظ UNCATEGORIZED).
    func confirmCategory(_ category: SharedLogic.Category?) {
        send(ContactEditIntentConfirmCategory(category: category))
    }

    func dismissCategoryPrompt() {
        send(ContactEditIntentDismissCategoryPrompt.shared)
    }

    /// الخروج بلا حفظ — الفعل NavigateBack يعود عبر [onNavigateBack].
    func discardChanges() {
        send(ContactEditIntentDiscardChanges.shared)
    }

    func clearSaveError() {
        showSaveError = false
    }

    func clearCameraPicker() {
        showCameraPicker = false
    }

    func clearGalleryPicker() {
        showGalleryPicker = false
    }

    /// مغادرة نهائية للشاشة (بعد pop) — تحرير الـ VM؛ الحفظ الجارٍ لا يُقطع (سلامة J-1).
    func handleDisappear() {
        if state.isSaving {
            // حفظ جارٍ — تفادي إلغاء منتصف ميثاق J-1؛ هذا الـ VM الوحيد يُترك لاكتماله.
            return
        }
        viewModel.clear()
    }
}
