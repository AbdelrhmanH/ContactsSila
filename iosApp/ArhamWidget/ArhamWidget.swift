import WidgetKit
import SwiftUI
import UIKit

/// T8-13 (D6 · AC-15): ودجت الأرحام — يعرض اللقطة المنشورة من التطبيق حصراً (App Group)،
/// بلا Room ولا شبكة. الأحجام الثلاثة (small/medium/large)، واتجاه RTL والوضع الليلي
/// وأحجام الخطوط الكبيرة تسير عبر نظام SwiftUI الافتراضي (ألوان النظام + الخطوط الديناميكية).
struct ArhamWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: ArhamWidgetKind.value, provider: ArhamProvider()) { entry in
            ArhamWidgetEntryView(entry: entry)
        }
        .configurationDisplayName("Arham")
        .description(NSLocalizedString("arham_widget_description", comment: ""))
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

struct ArhamEntry: TimelineEntry {
    let date: Date
    let snapshot: ArhamWidgetSnapshot?
}

struct ArhamProvider: TimelineProvider {
    func placeholder(in context: Context) -> ArhamEntry {
        ArhamEntry(date: Date(), snapshot: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (ArhamEntry) -> Void) {
        completion(ArhamEntry(date: Date(), snapshot: ArhamSnapshotReader.load()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<ArhamEntry>) -> Void) {
        let entry = ArhamEntry(date: Date(), snapshot: ArhamSnapshotReader.load())
        // §M/D6: إعادة التحميل الحية تأتي من التطبيق عند كل تغيير بيانات (reloadTimelines)؛
        // هذه الجدولة صيانةٌ احتياطية بإيقاع متباعد — لا وعد بإيقاع 30 دقيقة على iOS.
        let next = Calendar.current.date(byAdding: .hour, value: 6, to: entry.date) ?? entry.date
        completion(Timeline(entries: [entry], policy: .after(next)))
    }
}

struct ArhamWidgetEntryView: View {
    @Environment(\.widgetFamily) private var family
    let entry: ArhamEntry

    var body: some View {
        Group {
            if let snapshot = entry.snapshot {
                content(for: snapshot)
            } else {
                emptyView
            }
        }
        .containerBackground(for: .widget) { Color(uiColor: .systemBackground) }
    }

    @ViewBuilder
    private func content(for snapshot: ArhamWidgetSnapshot) -> some View {
        switch family {
        case .systemSmall: smallView(snapshot)
        case .systemMedium: mediumView(snapshot)
        default: largeView(snapshot)
        }
    }

    /// small: المؤشر الجامع + سطر الإحصاءات.
    private func smallView(_ snapshot: ArhamWidgetSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(overdueLabel(for: snapshot))
                .font(.headline)
                .minimumScaleFactor(0.7)
            Spacer(minLength: 0)
            goalRow(for: snapshot)
            Text(statsLine(for: snapshot))
                .font(.caption)
                .foregroundStyle(.secondary)
                .minimumScaleFactor(0.7)
        }
    }

    /// medium: المؤشر + حتى ثلاثة مُدخلين.
    private func mediumView(_ snapshot: ArhamWidgetSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(overdueLabel(for: snapshot))
                .font(.headline)
                .minimumScaleFactor(0.7)
            ForEach(snapshot.entries.prefix(3), id: \.contactId) { entry in
                entryRow(entry)
            }
            Spacer(minLength: 0)
            Text(statsLine(for: snapshot))
                .font(.caption)
                .foregroundStyle(.secondary)
                .minimumScaleFactor(0.7)
        }
    }

    /// large: المؤشر + المُدخلون الثلاثة + شريط الهدف + الإحصاءات.
    private func largeView(_ snapshot: ArhamWidgetSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(overdueLabel(for: snapshot))
                .font(.headline)
                .minimumScaleFactor(0.7)
            ForEach(snapshot.entries, id: \.contactId) { entry in
                entryRow(entry)
            }
            Spacer(minLength: 0)
            goalRow(for: snapshot)
            Text(statsLine(for: snapshot))
                .font(.caption)
                .foregroundStyle(.secondary)
                .minimumScaleFactor(0.7)
        }
    }

    private func entryRow(_ entry: ArhamWidgetSnapshot.Entry) -> some View {
        HStack(spacing: 8) {
            Circle()
                .fill(statusColor(for: entry.status))
                .frame(width: 8, height: 8)
            Text(entry.displayName)
                .font(.subheadline)
                .lineLimit(1)
            Spacer(minLength: 0)
            Text(daysText(for: entry.daysSinceLastContact))
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
    }

    private func goalRow(for snapshot: ArhamWidgetSnapshot) -> some View {
        let clamped = min(max(snapshot.goalCompletionFraction, 0), 1)
        return ProgressView(value: clamped)
            .progressViewStyle(.linear)
            .accessibilityLabel(Text(overdueLabel(for: snapshot)))
    }

    private var emptyView: some View {
        VStack(alignment: .center, spacing: 8) {
            Spacer(minLength: 0)
            Text(NSLocalizedString("arham_widget_empty", comment: ""))
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity)
    }

    private func overdueLabel(for snapshot: ArhamWidgetSnapshot) -> String {
        String(
            format: NSLocalizedString("arham_widget_overdue_label", comment: ""),
            snapshot.overdueCount
        )
    }

    private func statsLine(for snapshot: ArhamWidgetSnapshot) -> String {
        String(
            format: NSLocalizedString("arham_widget_stats_line", comment: ""),
            snapshot.totalFamilyCalls,
            snapshot.longestCallSec
        )
    }

    private func daysText(for days: Int?) -> String {
        guard let days else {
            return NSLocalizedString("arham_widget_no_contact_recorded", comment: "")
        }
        return String(
            format: NSLocalizedString("arham_widget_days_since", comment: ""),
            days
        )
    }

    /// مرآة ألوان `ArhamStatus` من المجال (OVERDUE/SOON/OK) — أي شيء آخر = رمادي محايد.
    private func statusColor(for status: String) -> Color {
        switch status {
        case "OVERDUE": return Color(uiColor: .systemRed)
        case "SOON": return Color(uiColor: .systemOrange)
        case "OK": return Color(uiColor: .systemGreen)
        default: return Color(uiColor: .systemGray)
        }
    }
}
