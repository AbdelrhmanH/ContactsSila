import SwiftUI
import SharedLogic

@main
struct iOSApp: App {

    @State private var store: MainViewModelStore

    /// مخزن شاشة جهات الاتصال المشترك (T4 — Issue #25) — يُنشأ بعد ضمان تهيئة
    /// Koin حصراً، وبلا مالك ثانٍ للحالة (نمط MainViewModelStore نفسه).
    @State private var contactsStore: ContactsViewModelStore

    init() {
        _ = IosKoinBootstrap.shared.ensureStarted()
        _store = State(initialValue: MainViewModelStore())
        _contactsStore = State(initialValue: ContactsViewModelStore())
    }

    var body: some Scene {
        WindowGroup {
            ContentView(store: store, contactsStore: contactsStore)
        }
    }
}
