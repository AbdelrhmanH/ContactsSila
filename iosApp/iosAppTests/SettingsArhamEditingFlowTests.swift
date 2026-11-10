import XCTest
@testable import ContactsSila
import SharedLogic

/**
 * اختبارات تدفق تحرير الأرحام على iOS (S3-20 — تذكرة #70):
 * 1. عقدُ النوايا المشتركة تصريفاً — اللفاظ والمعاملات كما في Compose
 *    (لو غيّر SKIE شكل اللفّ ترى الاختبارات ذلك فوراً).
 * 2. تنسيق الوقت — مرآة `padStart(2, '0')` للCompose حرفياً.
 * قبل هذه التذكرة كانت شاشة الإعدادات الأصلية تعرض موعد التذكير وساعات
 * الهدوء نصاً للقراءة فقط (تفاوت منصّي غير موثق).
 */
final class SettingsArhamEditingFlowTests: XCTestCase {

    // MARK: - عقد النوايا المشتركة (تصريفاً — نفس اللفاظ على المنصتين)

    /// نية وقت التنبيه ثنائية المعاملين (ساعة + دقيقة) كما في Compose.
    func testArhamNotificationTimeIntentWrapsHourAndMinute() {
        let intent = SettingsIntentSetArhamNotificationTime(hour: 19, minute: 45)
        XCTAssertEqual(intent.hour, Int32(19))
        XCTAssertEqual(intent.minute, Int32(45))
    }

    /// نية ساعات الهدوء (بداية/نهاية) بدلالة الحوار المشترك — أي ساعة بلا قيد تحقق.
    func testQuietHoursIntentWrapsStartAndEndHour() {
        let intent = SettingsIntentSetQuietHours(startHour: 22, endHour: 7)
        XCTAssertEqual(intent.startHour, Int32(22))
        XCTAssertEqual(intent.endHour, Int32(7))
    }

    // MARK: - تنسيق الوقت (مرآة Compose حرفياً)

    func testTwoDigitsPadsSingleDigitLikeComposePadStart() {
        XCTAssertEqual(SettingsTimeFormatting.twoDigits(7), "07")
        XCTAssertEqual(SettingsTimeFormatting.twoDigits(0), "00")
        XCTAssertEqual(SettingsTimeFormatting.twoDigits(23), "23")
    }

    func testHourMinuteFormatMatchesSharedRow() {
        XCTAssertEqual(SettingsTimeFormatting.hourMinute(hour: 19, minute: 45), "19:45")
        XCTAssertEqual(SettingsTimeFormatting.hourMinute(hour: 5, minute: 3), "05:03")
    }

    func testQuietRangeFormatMatchesSharedRow() {
        XCTAssertEqual(SettingsTimeFormatting.quietRange(startHour: 22, endHour: 7), "22:00 - 07:00")
        XCTAssertEqual(SettingsTimeFormatting.quietRange(startHour: 0, endHour: 23), "00:00 - 23:00")
    }
}
