package app.sila.di

import org.koin.core.module.Module

/**
 * وحدة الدمج المنصوي (T7 · Issue #28 · ADR-0013): `expect`/`actual` لكل منصة يربط
 * [app.sila.feature.contacts.domain.ContactsMergeWriter] حصراً — عقد ضيق مستقل عن كاتب
 * حقول T6 المجمد (الدمج عملية على مستوى رسم RawContacts/هوية لا كتابة حقول — خيار A
 * من Lane D). التنفيذات في `:feature:contacts:data` androidMain/iosMain.
 */
internal expect val platformContactsMergeModule: Module
