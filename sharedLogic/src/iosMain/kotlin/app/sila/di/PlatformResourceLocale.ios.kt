package app.sila.di

import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleLanguageCode
import platform.Foundation.currentLocale

/**
 * تنفيذ iOS (إصلاح عيب DEF-02 — تذكرة #97): لا per-app locale عندها (منفذ
 * `AppLocalePort` كائن سلبي) — لغة الموارد الفعلية هي لغة النظام.
 */
actual fun platformResourceLanguageCode(): String? =
    NSLocale.currentLocale.objectForKey(NSLocaleLanguageCode) as? String
