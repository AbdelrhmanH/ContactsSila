import PhotosUI
import SharedLogic
import SwiftUI

/**
 * شاشة الإضافة/التعديل على SwiftUI (T6 — Issue #27).
 *
 * Stateful رقيقة: تعرض [ContactEditUiState] المشترك وتُرسل [ContactEditIntent]
 * حصراً — لا منطق أعمال (نمط ContactsView من T4). منتقيات الصور (الكاميرا/المعرض)
 * آثار تُجامع في [ContactEditViewModelStore] حصرياً (قناة أحادية المستهلك) وتعود
 * بالبايتات عبر [ContactEditIntentSetPhotoPicked]. الحد الأدنى للحفظ: اسم **أو**
 * رقم (AC) — Sheet «صنِّف قبل الحفظ» عند UNCATEGORIZED يُدير الـ VM.
 *
 * التنقل (J-2): الرجوع عبر `NavigationStack` الأصلي — [onBack] معايرة تضبطها
 * حاوية النافذة؛ تأكيد التجاهل من الشاشة حسب `state.isDirty` (نفس أندرويد).
 */
struct ContactEditView: View {

    var store: ContactEditViewModelStore
    let language: AppLanguage
    let onBack: () -> Void

    @State private var showDiscardDialog = false
    @State private var pickedPhoto: PhotosPickerItem?

    var body: some View {
        let strings = ContactEditLocalizedStrings(language: language)

        Group {
            if store.state.isNotFound {
                VStack(spacing: 12) {
                    Spacer()
                    Image(systemName: "person.slash").font(.system(size: 40)).foregroundColor(.secondary)
                    Text(strings.notFound).multilineTextAlignment(.center).padding(.horizontal, 24)
                    Button(strings.cancel) { onBack() }
                    Spacer()
                }
            } else {
                editContent(strings)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbarItems(for: strings) }
        // تأكيد التجاهل — قرار الشاشة حسب isDirty ثم DiscardChanges (عقد VM).
        .confirmationDialog(
            strings.discardTitle,
            isPresented: $showDiscardDialog,
            titleVisibility: .visible
        ) {
            Button(strings.discard, role: .destructive) { store.discardChanges() }
            Button(strings.keepEditing, role: .cancel) {}
        } message: {
            Text(strings.discardMessage)
        }
        // Sheet «صنِّف قبل الحفظ» — حالة الـ VM (showCategoryPrompt) حصراً.
        .confirmationDialog(
            strings.categoryPromptTitle,
            isPresented: Binding(
                get: { store.state.showCategoryPrompt },
                set: { if !$0 { store.dismissCategoryPrompt() } }
            ),
            titleVisibility: .visible
        ) {
            ForEach(categoryOptions, id: \.self) { category in
                Button(strings.categoryTitle(category)) { store.confirmCategory(category) }
            }
            Button(strings.categoryPromptLater) { store.confirmCategory(nil) }
            Button(strings.cancel, role: .cancel) { store.dismissCategoryPrompt() }
        }
        // فشل الحفظ (Effect عابر) — نص مترجم من الموارد (لا نص ثابت).
        .alert(
            strings.saveError,
            isPresented: Binding(
                get: { store.showSaveError },
                set: { if !$0 { store.clearSaveError() } }
            )
        ) {
            Button(strings.cancel, role: .cancel) {}
        }
        // منتقي الكاميرا (آثار عابرة) — fullScreenCover هو العرض الصحيح لـ
        // UIImagePickerController (ملء الشاشة، مثل أندرويد TakePicturePreview).
        .fullScreenCover(
            isPresented: Binding(
                get: { store.showCameraPicker },
                set: { if !$0 { store.clearCameraPicker() } }
            )
        ) {
            CameraPicker { data in
                store.setPhotoPicked(data.map(kotlinByteArray(from:)))
            }
            .ignoresSafeArea()
        }
        .photosPicker(
            isPresented: Binding(
                get: { store.showGalleryPicker },
                set: { if !$0 { store.clearGalleryPicker() } }
            ),
            selection: $pickedPhoto,
            matching: .images
        )
        .onChange(of: pickedPhoto) { newItem in
            guard let newItem else { return }
            pickedPhoto = nil
            Task {
                if let data = try? await newItem.loadTransferable(type: Data.self) {
                    store.setPhotoPicked(kotlinByteArray(from: data))
                } else {
                    store.setPhotoPicked(nil) // إلغاء/فشل قراءة — بلا نية (عقد VM).
                }
            }
        }
        // معاير النافذة قبل بدء الجمع (نمط T4).
        .onAppear {
            store.onNavigateBack = onBack
        }
        // مهمتان منفصلتان لا متتاليتان + بثّ بايتات الصورة الجانبي.
        .task { await store.observeState() }
        .task { await store.observeEffects() }
        .task { await store.observePendingPhotoBytes() }
        .onDisappear { store.handleDisappear() }
    }

    // MARK: - المحتوى

    @ViewBuilder
    private func editContent(_ strings: ContactEditLocalizedStrings) -> some View {
        List {
            photoSection(strings)
            nameSection(strings)
            phonesSection(strings)
            emailsSection(strings)
            websitesSection(strings)
            organizationSection(strings)
            notesSection(strings)
            datesSection(strings)
            addressesSection(strings)
            customFieldsSection(strings)
            categorySection(strings)

            if let error = store.state.validationError {
                Section {
                    Text(strings.validationNameOrPhone)
                        .font(.callout)
                        .foregroundColor(.red)
                }
            }
        }
        .listStyle(.insetGrouped)
        .disabled(store.state.isSaving)
        .overlay {
            if store.state.isSaving {
                ProgressView(strings.saving)
            }
        }
    }

    private func photoSection(_ strings: ContactEditLocalizedStrings) -> some View {
        Section {
            HStack {
                Spacer()
                VStack(spacing: 8) {
                    ZStack {
                        Circle().fill(Color.emeraldSila.opacity(0.15))
                        Text(initialsPreview)
                            .font(.title2.weight(.semibold))
                            .foregroundColor(Color.emeraldSila)
                    }
                    .frame(width: 72, height: 72)
                    HStack(spacing: 16) {
                        Button(strings.photoCamera) { store.send(ContactEditIntentRequestPhotoCamera.shared) }
                        Button(strings.photoGallery) { store.send(ContactEditIntentRequestPhotoGallery.shared) }
                        if store.state.photoUri != nil && !store.state.isPhotoRemoved {
                            Button(strings.removePhoto, role: .destructive) { store.removePhoto() }
                        }
                    }
                    .font(.caption)
                }
                Spacer()
            }
        }
    }

    private func nameSection(_ strings: ContactEditLocalizedStrings) -> some View {
        Section {
            TextField(strings.firstNameLabel, text: fieldBinding(ContactEditNameField.firstName, store.state.firstName))
            TextField(strings.lastNameLabel, text: fieldBinding(ContactEditNameField.lastName, store.state.lastName))

            DisclosureGroup(
                store.state.isExpanded ? strings.showLess : strings.showMore,
                isExpanded: Binding(
                    get: { store.state.isExpanded },
                    set: { store.setExpanded($0) }
                )
            ) {
                TextField(strings.prefixLabel, text: fieldBinding(ContactEditNameField.prefix, store.state.prefix))
                TextField(strings.middleNameLabel, text: fieldBinding(ContactEditNameField.middleName, store.state.middleName))
                TextField(strings.suffixLabel, text: fieldBinding(ContactEditNameField.suffix, store.state.suffix))
                TextField(strings.nicknameLabel, text: fieldBinding(ContactEditNameField.nickname, store.state.nickname))
                TextField(strings.fileAsLabel, text: fieldBinding(ContactEditNameField.fileAs, store.state.fileAs))
                TextField(strings.phoneticLabel, text: fieldBinding(ContactEditNameField.phonetic, store.state.phoneticName))
            }
        } header: {
            Text(store.state.displayNamePreview)
        }
    }

    private func phonesSection(_ strings: ContactEditLocalizedStrings) -> some View {
        Section(strings.phonesLabel) {
            ForEach(store.state.phones, id: \.key) { row in
                VStack(alignment: .leading, spacing: 4) {
                    TextField(strings.addPhone, text: phoneBinding(row.key, row.raw))
                        .keyboardType(.phonePad)
                        .environment(\.layoutDirection, .leftToRight)
                    HStack {
                        Menu(strings.phoneTypeName(row.type)) {
                            ForEach(phoneTypes, id: \.self) { type in
                                Button(strings.phoneTypeName(type)) {
                                    store.send(ContactEditIntentUpdatePhoneType(key: row.key, type: type))
                                }
                            }
                        }
                        .font(.caption)
                        Spacer()
                        Button(role: .destructive) {
                            store.removePhone(row.key)
                        } label: {
                            Image(systemName: "minus.circle")
                        }
                        .buttonStyle(.borderless)
                    }
                    if let warning = store.state.duplicateWarning[KotlinLong(value: row.key)] {
                        // تحذير التكرار غير المانع (J-9) — بجانب صف الهاتف (AC).
                        Text(strings.duplicateWarning(warning))
                            .font(.caption2)
                            .foregroundColor(.orange)
                    }
                }
            }
            Button {
                store.addPhone()
            } label: {
                Label(strings.addPhone, systemImage: "plus")
            }
        }
    }

    private func emailsSection(_ strings: ContactEditLocalizedStrings) -> some View {
        Section {
            ForEach(store.state.emails, id: \.key) { row in
                HStack {
                    TextField(strings.addEmail, text: emailBinding(row.key, row.address))
                        .keyboardType(.emailAddress)
                        .environment(\.layoutDirection, .leftToRight)
                    Button(role: .destructive) {
                        store.removeEmail(row.key)
                    } label: {
                        Image(systemName: "minus.circle")
                    }
                    .buttonStyle(.borderless)
                }
            }
            Button {
                store.addEmail()
            } label: {
                Label(strings.addEmail, systemImage: "plus")
            }
        }
    }

    private func websitesSection(_ strings: ContactEditLocalizedStrings) -> some View {
        Section {
            ForEach(store.state.websites, id: \.key) { row in
                HStack {
                    TextField(strings.addWebsite, text: websiteBinding(row.key, row.url))
                        .keyboardType(.URL)
                        .environment(\.layoutDirection, .leftToRight)
                    Button(role: .destructive) {
                        store.removeWebsite(row.key)
                    } label: {
                        Image(systemName: "minus.circle")
                    }
                    .buttonStyle(.borderless)
                }
            }
            Button {
                store.addWebsite()
            } label: {
                Label(strings.addWebsite, systemImage: "plus")
            }
        }
    }

    private func organizationSection(_ strings: ContactEditLocalizedStrings) -> some View {
        Section {
            TextField(strings.companyLabel, text: orgBinding(ContactEditOrgField.company, store.state.company))
            TextField(strings.departmentLabel, text: orgBinding(ContactEditOrgField.department, store.state.department))
            TextField(strings.jobTitleLabel, text: orgBinding(ContactEditOrgField.jobTitle, store.state.jobTitle))
        }
    }

    private func notesSection(_ strings: ContactEditLocalizedStrings) -> some View {
        Section(strings.notesLabel) {
            TextEditor(text: Binding(
                get: { store.state.notes },
                set: { store.setNotes($0) }
            ))
            .frame(minHeight: 80)
        }
    }

    /// التواريخ — شهر/يوم إلزاميان للفعل والسنة اختيارية (عيد ميلاد بلا سنة شائع).
    /// عيد ميلاد لجهة عائلة بلا رابط أرحام يُسجَّل تلقائياً بعد الحفظ (J-4 — AC).
    private func datesSection(_ strings: ContactEditLocalizedStrings) -> some View {
        Section {
            ForEach(store.state.dates, id: \.key) { row in
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Menu(strings.dateTypeName(row.type)) {
                            ForEach(dateTypes, id: \.self) { type in
                                Button(strings.dateTypeName(type)) {
                                    // فكّ التغليف في متغيرات محلية (KotlinInt? : NSNumber) — تفجير تدقيق الأنواع
                                    // في الشكل متعدد الوسائط سبّب «unable to type-check in reasonable time».
                                    let month = row.month?.intValue
                                    let day = row.day?.intValue
                                    let year = row.year?.intValue
                                    store.updateDate(row.key, type: type, month: month, day: day, year: year)
                                }
                            }
                        }
                        Spacer()
                        Button(role: .destructive) {
                            store.removeDate(row.key)
                        } label: {
                            Image(systemName: "minus.circle")
                        }
                        .buttonStyle(.borderless)
                    }
                    HStack(spacing: 12) {
                        numberField(strings.monthLabel, value: row.month?.intValue, width: 48) { month in
                            store.updateDate(row.key, type: row.type, month: month, day: row.day?.intValue, year: row.year?.intValue)
                        }
                        numberField(strings.dayLabel, value: row.day?.intValue, width: 48) { day in
                            store.updateDate(row.key, type: row.type, month: row.month?.intValue, day: day, year: row.year?.intValue)
                        }
                        numberField(strings.yearLabel, value: row.year?.intValue, width: 72) { year in
                            store.updateDate(row.key, type: row.type, month: row.month?.intValue, day: row.day?.intValue, year: year)
                        }
                    }
                    if row.type == "BIRTHDAY", store.state.category == SharedLogic.Category.family {
                        Text(strings.arhamBirthdayHint)
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                }
            }
            Button {
                store.addDate()
            } label: {
                Label(strings.addDate, systemImage: "plus")
            }
        } header: {
            Text(strings.datesLabel)
        }
    }

    private func addressesSection(_ strings: ContactEditLocalizedStrings) -> some View {
        Section {
            ForEach(store.state.addresses, id: \.key) { row in
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(strings.addressTypeName(row.type)).font(.caption).foregroundColor(.secondary)
                        Spacer()
                        Button(role: .destructive) {
                            store.removeAddress(row.key)
                        } label: {
                            Image(systemName: "minus.circle")
                        }
                        .buttonStyle(.borderless)
                    }
                    TextField(strings.streetLabel, text: addressBinding(row.key, ContactEditAddressField.street, row.street))
                    TextField(strings.cityLabel, text: addressBinding(row.key, ContactEditAddressField.city, row.city))
                    TextField(strings.regionLabel, text: addressBinding(row.key, ContactEditAddressField.region, row.region))
                    TextField(strings.postalCodeLabel, text: addressBinding(row.key, ContactEditAddressField.postalCode, row.postalCode))
                    TextField(strings.countryLabel, text: addressBinding(row.key, ContactEditAddressField.country, row.country))
                }
            }
            Button {
                store.addAddress()
            } label: {
                Label(strings.addAddress, systemImage: "plus")
            }
        } header: {
            Text(strings.addressesLabel)
        }
    }

    private func customFieldsSection(_ strings: ContactEditLocalizedStrings) -> some View {
        Section {
            ForEach(store.state.customFields, id: \.key) { row in
                HStack(spacing: 8) {
                    TextField(strings.customFieldLabelLabel, text: customFieldBinding(row.key, ContactEditCustomSlot.label, row.label))
                    TextField(strings.customFieldValueLabel, text: customFieldBinding(row.key, ContactEditCustomSlot.value, row.value))
                    Button(role: .destructive) {
                        store.removeCustomField(row.key)
                    } label: {
                        Image(systemName: "minus.circle")
                    }
                    .buttonStyle(.borderless)
                }
            }
            Button {
                store.addCustomField()
            } label: {
                Label(strings.addCustomField, systemImage: "plus")
            }
        } header: {
            Text(strings.customFieldsLabel)
        }
    }

    /// حقل رقمي اختياري (شهر/يوم/سنة) — `nil` = فارغ؛ القيم غير الصالحة تُهمل (عرض فقط).
    /// `Int?` (وليس Int32?): `.intValue` على KotlinInt المعلَّبة يعيد `Int` في Swift —
    /// والحد النهائي Int32 يُفرَض عند تعليب الحدّ في الـ store.
    private func numberField(
        _ placeholder: String,
        value: Int?,
        width: CGFloat,
        onChange: @escaping (Int?) -> Void
    ) -> some View {
        TextField(
            placeholder,
            text: Binding(
                get: { value.map(String.init) ?? "" },
                set: { text in
                    let trimmed = text.trimmingCharacters(in: .whitespaces)
                    onChange(trimmed.isEmpty ? nil : Int(trimmed))
                }
            )
        )
        .keyboardType(.numberPad)
        .multilineTextAlignment(.center)
        .frame(width: width)
        .textFieldStyle(.roundedBorder)
    }

    private func addressBinding(_ key: Int64, _ field: ContactEditAddressField, _ value: String) -> Binding<String> {
        Binding(
            get: { value },
            set: { store.updateAddressField(key, field: field, value: $0) }
        )
    }

    private func customFieldBinding(_ key: Int64, _ slot: ContactEditCustomSlot, _ value: String) -> Binding<String> {
        Binding(
            get: { value },
            set: { store.updateCustomField(key, slot: slot, value: $0) }
        )
    }

    private func categorySection(_ strings: ContactEditLocalizedStrings) -> some View {
        Section(strings.categoryLabel) {
            Picker(strings.categoryLabel, selection: Binding(
                get: { store.state.category },
                set: { store.setCategory($0) }
            )) {
                ForEach(categoryOptions, id: \.self) { category in
                    Text(strings.categoryTitle(category)).tag(category)
                }
            }
            Toggle(store.state.isFavorite ? strings.removeFavorite : strings.addFavorite, isOn: Binding(
                get: { store.state.isFavorite },
                set: { _ in store.toggleFavorite() }
            ))
        }
    }

    // MARK: - الشريط العلوي

    @ToolbarContentBuilder
    private func toolbarItems(for strings: ContactEditLocalizedStrings) -> some ToolbarContent {
        ToolbarItem(placement: .navigationBarLeading) {
            Button {
                // قرار الشاشة حسب isDirty — الفعل DiscardChanges يحمل NavigateBack للمعاير (AC).
                if store.state.isDirty {
                    showDiscardDialog = true
                } else {
                    store.discardChanges()
                }
            } label: {
                Image(systemName: "xmark")
            }
            .accessibilityLabel(strings.cancel)
        }
        ToolbarItem(placement: .navigationBarTrailing) {
            if store.state.isSaving {
                ProgressView()
            } else {
                Button(strings.save) { store.save() }
            }
        }
    }

    // MARK: - روابط الحقول (بلا منطق — تمرير قيم حصراً إلى Intents)

    private func fieldBinding(_ field: ContactEditNameField, _ value: String) -> Binding<String> {
        Binding(
            get: { value },
            set: { store.setNameField(field, $0) }
        )
    }

    private func orgBinding(_ field: ContactEditOrgField, _ value: String) -> Binding<String> {
        Binding(
            get: { value },
            set: { store.setOrganizationField(field, $0) }
        )
    }

    private func phoneBinding(_ key: Int64, _ value: String) -> Binding<String> {
        Binding(
            get: { value },
            set: { store.updatePhone(key, $0) }
        )
    }

    private func emailBinding(_ key: Int64, _ value: String) -> Binding<String> {
        Binding(
            get: { value },
            set: { store.updateEmail(key, $0) }
        )
    }

    private func websiteBinding(_ key: Int64, _ value: String) -> Binding<String> {
        Binding(
            get: { value },
            set: { store.updateWebsite(key, $0) }
        )
    }

    // MARK: - أدوات عرض

    private var categoryOptions: [SharedLogic.Category] {
        [
            SharedLogic.Category.family,
            SharedLogic.Category.friends,
            SharedLogic.Category.work,
            SharedLogic.Category.services,
            SharedLogic.Category.emergency,
            SharedLogic.Category.uncategorized
        ]
    }

    private var phoneTypes: [String] { ["MOBILE", "HOME", "WORK", "OTHER"] }

    private var dateTypes: [String] { ["BIRTHDAY", "ANNIVERSARY", "CUSTOM"] }

    private var initialsPreview: String {
        let source = store.state.displayNamePreview
        guard let first = source.first else { return "#" }
        return String(first)
    }
}

/**
 * حاوية شاشة الإضافة/التعديل — تبني [ContactEditViewModelStore] مرة واحدة لهوية
 * الدفعة (`@State` initialValue — نمط ContactDetailsScreen).
 */
struct ContactEditScreen: View {

    @State private var store: ContactEditViewModelStore
    private let language: AppLanguage
    private let onBack: () -> Void

    init(contactId: Int64?, language: AppLanguage, onBack: @escaping () -> Void) {
        _store = State(initialValue: ContactEditViewModelStore(contactId: contactId))
        self.language = language
        self.onBack = onBack
    }

    var body: some View {
        ContactEditView(store: store, language: language, onBack: onBack)
    }
}

// MARK: - منتقي الكاميرا (UIImagePickerController — التغطية المرآتية لـ TakePicturePreview)

private struct CameraPicker: UIViewControllerRepresentable {

    let onPicked: (Data?) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let controller = UIImagePickerController()
        controller.sourceType = .camera
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onPicked: onPicked) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {

        let onPicked: (Data?) -> Void

        init(onPicked: @escaping (Data?) -> Void) {
            self.onPicked = onPicked
        }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            let data = (info[.originalImage] as? UIImage)?.jpegData(compressionQuality: 0.9)
            onPicked(data)
            picker.dismiss(animated: true)
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            // الإلغاء — `nil` بلا نية (عقد SetPhotoPicked: null = إلغاء).
            onPicked(nil)
            picker.dismiss(animated: true)
        }
    }
}

/// Data → KotlinByteArray — جسر البايتات لنية SetPhotoPicked (نمط IosDataInterop المشترك).
private func kotlinByteArray(from data: Data) -> KotlinByteArray {
    let result = KotlinByteArray(size: Int32(data.count))
    for (offset, byte) in data.enumerated() {
        result.set(index: Int32(offset), value: Int8(bitPattern: byte))
    }
    return result
}
