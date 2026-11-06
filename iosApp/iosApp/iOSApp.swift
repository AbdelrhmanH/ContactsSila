import SwiftUI
import WidgetKit
import SharedLogic

@main
struct iOSApp: App {

    @State private var store: MainViewModelStore

    /// مخزن شاشة جهات الاتصال المشترك (T4 — Issue #25) — يُنشأ بعد ضمان تهيئة
    /// Koin حصراً، وبلا مالك ثانٍ للحالة (نمط MainViewModelStore نفسه).
    @State private var contactsStore: ContactsViewModelStore

    init() {
        _ = IosKoinBootstrap.shared.ensureStarted()
        // T8-13 (D6): جسر إعادة تحميل الودجت — Kotlin يكتب اللقطة في حاوية App Group،
        // واستدعاء WidgetCenter لا يكون إلا من Swift (لا klib لـ WidgetKit في K/N).
        IosKoinBootstrap.shared.attachArhamWidgetReloadTrigger {
            WidgetCenter.shared.reloadTimelines(ofKind: ArhamWidgetKind.value)
        }
        _store = State(initialValue: MainViewModelStore())
        _contactsStore = State(initialValue: ContactsViewModelStore())
    }

    var body: some Scene {
        WindowGroup {
            ContentView(store: store, contactsStore: contactsStore)
        }
    }
}
