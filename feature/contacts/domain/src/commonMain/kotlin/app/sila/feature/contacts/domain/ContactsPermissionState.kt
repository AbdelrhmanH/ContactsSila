package app.sila.feature.contacts.domain

/**
 * حالة صلاحية القراءة من دفتر الهاتف كما تظهرها المنصة (ADR-0009 §5).
 *
 * - [GRANTED]: منح كامل (Android `READ_CONTACTS`؛ iOS `.contacts` كامل).
 * - [LIMITED]: جزئي — iOS 18+ `.limited` حصراً. **ممنوع معاملته رفضاً أو حذفاً**:
 *   نقص ظهور جهة بسبب النطاق الجزئي لا ينتج حذفاً بصفوف Room (ADR-0010 §5)،
 *   ويتطلب واجهة توسيع صلاحية (`ContactAccessButton`).
 * - [DENIED]: رفض أو غير محدد بعد (`notDetermined` يُعامل رفضاً في هذا المستوى
 *   — طلب المنح من تدفق UI/الصلاحيات وليس من المصدر).
 * - [PERMANENTLY_DENIED]: لا طريقة لطلب المنح من التطبيق
 *   (iOS `.restricted`؛ Android بعد "لا تسأل مرة أخرى" حيث تكشفه المنصة).
 */
enum class ContactsPermissionState {
    GRANTED,
    LIMITED,
    DENIED,
    PERMANENTLY_DENIED,
}
