import SwiftUI
import SharedLogic

@main
struct iOSApp: App {

    @State private var store: MainViewModelStore

    init() {
        _ = IosKoinBootstrap.shared.ensureStarted()
        _store = State(initialValue: MainViewModelStore())
    }

    var body: some Scene {
        WindowGroup {
            ContentView(store: store)
        }
    }
}
