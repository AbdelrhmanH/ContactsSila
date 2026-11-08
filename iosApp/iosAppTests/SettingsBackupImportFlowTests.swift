import XCTest
@testable import ContactsSila
import SharedLogic

/**
 * اختبارات منطق استيراد النسخة الاحتياطية على iOS (إصلاح Issue #30):
 * قبل الإصلاح كانت واجهة الاستيراد ترسل مصفوفة بايتات فارغة
 * دائماً، ففشل الاستيراد. هذه الاختبارات تضبط:
 * 1. تحويل Data ← KotlinByteArray ← Data يبقى بايتات التشفير حرفياً.
 * 2. الملف الفارغ/غير المقروء لا يصل إلى الـ ViewModel إطلاقاً.
 */
final class SettingsBackupImportFlowTests: XCTestCase {

    // MARK: - تحويل البايتات (Data ↔ KotlinByteArray) — سليم حرفياً

    func testKotlinByteArrayRoundTripPreservesEncryptedBytes() {
        // بايتات نموذجية تشمل القيم الحدية: صفر، 1، 127، 128، 255
        let source: [UInt8] = [0x00, 0x01, 0x7F, 0x80, 0xFF, 0x10, 0xAB]
        let data = Data(source)

        let converted = SettingsViewModelStore.kotlinByteArray(from: data)
        let restored = SettingsViewModelStore.dataFromKotlinByteArray(converted)

        XCTAssertEqual([UInt8](restored), source)
    }

    func testKotlinByteArrayRoundTripOnLargeFixture() {
        // ملف نسخة احتياطية كبيرة: دوران كامل عبر 64 كيلوبايت دون تغيير
        let source = (0..<65_536).map { UInt8(truncatingIfNeeded: $0 &* 31) }
        let data = Data(source)

        let restored = SettingsViewModelStore.dataFromKotlinByteArray(
            SettingsViewModelStore.kotlinByteArray(from: data)
        )

        XCTAssertEqual([UInt8](restored), source)
    }

    // MARK: - لا يصل ملف فارغ لـ ViewModel أبداً (كان خلل Issue #30)

    func testUsableImportedBackupDataRejectsNilAndEmpty() {
        XCTAssertNil(SettingsViewModelStore.usableImportedBackupData(nil))
        XCTAssertNil(SettingsViewModelStore.usableImportedBackupData(Data()))
        XCTAssertNil(SettingsViewModelStore.usableImportedBackupData(Data(count: 0)))
    }

    func testUsableImportedBackupDataAcceptsPickedFileContent() {
        let fileContent = Data("sila-backup-encrypted-content".utf8)
        let usable = SettingsViewModelStore.usableImportedBackupData(fileContent)
        XCTAssertEqual(usable, fileContent)
    }

    // MARK: - التكليف المتبادل مع بايتات التشفير من الجهة المشتركة

    func testKotlinByteArraySizeMatchesFileContent() {
        let data = Data((0..<128).map { _ in UInt8.random(in: 0...255) })
        let converted = SettingsViewModelStore.kotlinByteArray(from: data)
        XCTAssertEqual(Int(converted.size), data.count)
    }
}
