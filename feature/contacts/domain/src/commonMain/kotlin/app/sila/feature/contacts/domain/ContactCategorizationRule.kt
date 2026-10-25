package app.sila.feature.contacts.domain

import app.sila.core.model.Category

/**
 * عقد قاعدة التصنيف الذكي — يطبق مبدأ الفتح للإضافة والإغلاق للتعديل (Open/Closed Principle — OCP).
 * يمكن إضافة قواعد تصنيف جديدة دون تعديل SmartCategorizeContactUseCase.
 */
fun interface ContactCategorizationRule {
    fun evaluate(normalizedName: String, rawPhone: String, e164Phone: String, hasCompany: Boolean): Category?
}
