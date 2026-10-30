package app.sila.di

import org.koin.core.module.Module

/**
 * وحدة مصدر جهات الاتصال من المنصة (Issue #22 · ADR-0009 §3):
 * `expect`/`actual` لكل منصة، والربط الفعلي في androidMain/iosMain لـ `:sharedLogic`.
 * توفر تنفيذ `ContactsPlatformSource` حصرياً (بلا شبكة/Firebase — خصوصية ADR-0009 §6)،
 * وتُضمَّن في `createSilaSharedModule` عبر موديول Koin ليقرأها المستورد لأدوات المزامنة
 * التزايدية (ADR-0010 §7).
 */
internal expect val platformContactsSourceModule: Module
