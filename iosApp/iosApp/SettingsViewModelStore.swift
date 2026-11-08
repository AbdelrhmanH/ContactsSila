import Foundation
import Observation
import SharedLogic
import UIKit

/**
 * جسر آثار الإعدادات المنصوية على iOS (T9 — Issue #30).
 */
@MainActor
protocol SettingsEffectRouting: AnyObject {
    func openIosContactsApp()
    func shareBackupBytes(_ bytes: Data)
    func showMessage(_ message: String)
}

@MainActor
final class DefaultSettingsEffectRouter: SettingsEffectRouting {

    func openIosContactsApp() {
        // فتح تطبيق جهات اتصال النظام عبر URL Scheme الخاص بالنظام أو الإعدادات
        if let url = URL(string: "contacts://"), UIApplication.shared.canOpenURL(url) {
            UIApplication.shared.open(url)
        } else if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }

    func shareBackupBytes(_ bytes: Data) {
        let tempUrl = FileManager.default.temporaryDirectory.appendingPathComponent("sila_backup.sila")
        do {
            try bytes.write(to: tempUrl)
            let activityVc = UIActivityViewController(activityItems: [tempUrl], applicationActivities: nil)
            if let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
               let rootVc = windowScene.windows.first(where: { $0.isKeyWindow })?.rootViewController {
                rootVc.present(activityVc, animated: true)
            }
        } catch {
            print("Failed to share backup file: \(error)")
        }
    }

    func showMessage(_ message: String) {
        print("[Settings] \(message)")
    }
}

/**
 * غلاف حالة شاشة الإعدادات المشتركة لـ SwiftUI (T9 — Issue #30) بنمط
 * ContactsViewModelStore / ArhamViewModelStore.
 */
@MainActor
@Observable
final class SettingsViewModelStore {

    private let viewModel: SettingsViewModel
    private(set) var state: SettingsUiState
    let effectRouter: any SettingsEffectRouting

    init(
        viewModel: SettingsViewModel = IosKoinBootstrap.shared.getSettingsViewModel(),
        effectRouter: (any SettingsEffectRouting)? = nil
    ) {
        self.viewModel = viewModel
        self.effectRouter = effectRouter ?? DefaultSettingsEffectRouter()
        self.state = viewModel.state.value
    }

    func observeState() async {
        for await newState in viewModel.state {
            self.state = newState
        }
    }

    func observeEffects() async {
        for await effect in viewModel.effects {
            switch effect {
            case is SettingsEffectOpenIosContactsApp:
                effectRouter.openIosContactsApp()
            case let backup as SettingsEffectBackupExported:
                let data = Data(backup.bytes.map { UInt8(bitPattern: $0) })
                effectRouter.shareBackupBytes(data)
            case let toast as SettingsEffectShowToast:
                effectRouter.showMessage(toast.message)
            default:
                break
            }
        }
    }

    func send(_ intent: SettingsIntent) {
        viewModel.onIntent(intent: intent)
    }
}