package app.sila.di

import org.koin.core.module.Module

/**
 * وحدة كتابة جهات الاتصال إلى المنصة (T6 · Issue #27 · ADR-0012):
 * `expect`/`actual` لكل منصة، والربط الفعلي في androidMain/iosMain لـ `:sharedLogic`.
 * تربط ثلاثية الكتابة النطاقية حصراً: [app.sila.feature.contacts.domain.ContactsPlatformWriter]
 * (الكاتب الضيق)، [app.sila.feature.contacts.domain.ContactImageProcessor] (معالج الصورة)،
 * و[app.sila.feature.contacts.domain.ConnectedAppsDetector] (كاشف التطبيقات المتصلة) —
 * بلا شبكة/Firebase (خصوصية AGENTS.md)، وتُضمَّن في `createSilaSharedModule` كي تستهلكها
 * حالات استخدام الحفظ/الحذف/التفاصيل.
 */
internal expect val platformContactsWriterModule: Module
