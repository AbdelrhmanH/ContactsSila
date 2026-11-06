import Foundation

/// T8-13 (D6): نموذج فكّ اللقطة القادمة من التطبيق — طابق حقلياً مع
/// `IosArhamWidgetSnapshotDto` في `IosArhamWidgetSnapshotModels.kt`.
/// أي تغيير في حقل الكوتلن يلزم بتغيير مقابله هنا (ولا عكس: الإضافة لا تكتب أبداً).
struct ArhamWidgetSnapshot: Codable {
    struct Entry: Codable {
        let contactId: Int64
        let displayName: String
        let relation: String
        let daysSinceLastContact: Int?
        let status: String
    }

    let overdueCount: Int
    let entries: [Entry]
    let totalFamilyCalls: Int
    let longestCallSec: Int
    let goalCompletionFraction: Double
    let generatedAtMillis: Int64
}

enum ArhamSnapshotReader {
    /// مفتاح Info.plist الذي يحمل مُعرِّف المجموعة (يُوسَّع من `APP_GROUP_ID` في Config.xcconfig).
    private static let appGroupIdKey = "AppGroupId"

    static let snapshotFileName = "arham-widget-snapshot.json"

    /// يقرأ اللقطة من جذر حاوية App Group — هذا هو **حد الوصول الوحيد** للإضافة (D6):
    /// لا Room ولا Application Support ولا أي كتابة. `nil` = بناء غير مُوقّع/الملف غير
    /// موجود بعد ← تعرض الودجت حالة الفراغ بدل الأعطال (fail-safe).
    static func load() -> ArhamWidgetSnapshot? {
        guard
            let groupId = Bundle.main.object(forInfoDictionaryKey: appGroupIdKey) as? String,
            let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: groupId)
        else { return nil }
        let url = container.appendingPathComponent(snapshotFileName)
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(ArhamWidgetSnapshot.self, from: data)
    }
}
