import WidgetKit
import SwiftUI

/// T8-13 (D6 · AC-15): نقطة دخول إضافة الودجت — هدف واحد (ودجت الأرحام).
///
/// مُعرِّف الودجت (`kind`) له نسخةٌ مقابلة في هدف التطبيق
/// (`iosApp/ArhamWidgetKind.swift`) يستخدمها الجسر عند `reloadTimelines` — أبقِ الاثنتين
/// متطابقتين (لا توجد مساحة اسم مشتركة بين الهدفين).
enum ArhamWidgetKind {
    static let value = "ArhamWidget"
}

@main
struct ArhamWidgetBundle: WidgetBundle {
    var body: some Widget {
        ArhamWidget()
    }
}
