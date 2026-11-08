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
    private(set) var toastMessage: String?
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
            if let error = newState.backupErrorMessage, error != lastBackupError {
                // خطأ النسخ الاحتياطي يظهر للمستخدم كرسالة مرئية لا كطباعة سجل
                toastMessage = error
            }
            lastBackupError = newState.backupErrorMessage
            self.state = newState
        }
    }

    func observeEffects() async {
        for await effect in viewModel.effects {
            switch effect {
            case is SettingsEffectOpenIosContactsApp:
                effectRouter.openIosContactsApp()
            case let backup as SettingsEffectBackupExported:
                let data = SettingsViewModelStore.dataFromKotlinByteArray(backup.bytes)
                effectRouter.shareBackupBytes(data)
            case let restored as SettingsEffectBackupRestored:
                let message = restoreCompletionMessage(for: restored.result)
                effectRouter.showMessage(message)
                toastMessage = message
            case let toast as SettingsEffectShowToast:
                effectRouter.showMessage(toast.message)
                toastMessage = toast.message
            default:
                break
            }
        }
    }

    func dismissToast() {
        toastMessage = nil
    }

    /// رسالة من طرق العرض نفسها (مثل فشل قراءة ملف النسخة) تُعرض مرئياً.
    func presentToastMessage(_ message: String) {
        toastMessage = message
    }

    private var lastBackupError: String?

    /// رسالة نجاح الاستعادة بصيغة مترجمة من أرقام النتيجة.
    private func restoreCompletionMessage(for result: RestoreResult) -> String {
        let strings = SettingsLocalizedStrings(language: state.preferences.language)
        var message = String(
            format: strings.restoreDoneFormat,
            Int(result.matchedCategories),
            Int(result.unmatchedCategories),
            Int(result.matchedArham),
            Int(result.unmatchedArham)
        )
        if result.restoredPreferences {
            message.append("\n" + strings.restorePreferences)
        }
        return message
    }

    // MARK: - تحويل البايتات

    nonisolated static func dataFromKotlinByteArray(_ kba: KotlinByteArray) -> Data {
        let size = Int(kba.size)
        var data = Data(count: size)
        for i in 0..<size {
            data[i] = UInt8(bitPattern: kba.get(index: Int32(i)))
        }
        return data
    }

    /// التحويل العكسي: محتوى الملف الحقيقي الذي اختاره المستخدم ← مصفوفة Kotlin
    /// (كان الإصلاح الضروري: كانت الواجهة ترسل مصفوفة فارغة دائماً — Issue #30).
    nonisolated static func kotlinByteArray(from data: Data) -> KotlinByteArray {
        let kba = KotlinByteArray(size: Int32(data.count))
        for (offset, byte) in data.enumerated() {
            kba.set(index: Int32(offset), value: Int8(bitPattern: byte))
        }
        return kba
    }

    // MARK: - منطق الاستيراد القابل للاختبار

    /// بيانات الاستيراد مقبولة فقط إذا قُرئ الملف فعلياً ولم يكن فارغاً؛
    /// لا نرسل أعداد بايتات صفرية للـ ViewModel إطلاقاً.
    nonisolated static func usableImportedBackupData(_ data: Data?) -> Data? {
        guard let data, !data.isEmpty else { return nil }
        return data
    }

    func send(_ intent: SettingsIntent) {
        viewModel.onIntent(intent: intent)
    }
}