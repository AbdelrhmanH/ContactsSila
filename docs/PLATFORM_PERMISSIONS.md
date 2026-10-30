# الصلاحيات وفروقات المنصات (Platform & Permissions)

## 1. أندرويد — `AndroidManifest.xml`

```xml
<uses-permission android:name="android.permission.READ_CONTACTS" />
<uses-permission android:name="android.permission.WRITE_CONTACTS" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.CALL_PHONE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_PHONE_CALL" /> <!-- رنين الطوارئ -->
<!-- لا تُطلب في المرحلة 1 — قرار ADR-0006 غير محسوم (أُعيد فتحه 2026-10-30) -->
<!-- <uses-permission android:name="android.permission.READ_CALL_LOG" /> -->

<queries>
    <package android:name="com.whatsapp" />
    <package android:name="com.whatsapp.w4b" />
    <package android:name="org.telegram.messenger" />
    <package android:name="org.thoughtcrime.securesms" />
    <intent><action android:name="android.intent.action.DIAL" /></intent>
</queries>
```

## 2. جدول الطلب في السياق

| الصلاحية | لحظة الطلب | عند الرفض |
|---|---|---|
| Contacts | Onboarding (بعد شاشة شرح) | التطبيق يعرض الدليل وأرحام فارغة + شاشة "امنح الصلاحية" |
| Notifications | عند تفعيل أرحام أول مرة | أرحام تعمل داخل التطبيق فقط |
| Phone State | عند أول تفعيل لأرحام أو أول مكالمة خدمية (بعد شاشة شرح لكشف حالة المكالمة) | لا كشف للمكالمات الخارجية ولا تقييم تلقائي بعد مكالمة خدمية؛ يبقى `IN_APP` + تأكيد يدوي |
| Call Phone | أول نقرة اتصال | استخدام `ACTION_DIAL` (يفتح لوحة الاتصال) |
| Call Log | **لا تُطلب في المرحلة 1** — القرار غير محسوم ([ADR-0006](adr/0006-call-log-strategy.md)) | الأثر: التتبع عبر مكالمات صِلَة + تأكيد يدوي؛ المكالمات الخارجية غير آلية |
| Location | أول فتح للدليل | اختيار المدينة يدوياً |
| Camera | مسح QR / صورة | خيار المعرض |
| Foreground Service | رنين الطوارئ المتواصل + أتمتة الرنة | — |

**رفض دائم:** شاشة شرح + زر "فتح الإعدادات" (`ACTION_APPLICATION_DETAILS_SETTINGS`).

## 3. iOS — `Info.plist`

| المفتاح | النص المقترح |
|---|---|
| `NSContactsUsageDescription` | "لتنظيم جهات اتصالك وتصنيفها. لا تُرفع أسماؤك إلى أي خادم." |
| `NSCameraUsageDescription` | "لمسح بطاقات QR والتقاط صور جهات الاتصال." |
| `NSLocationWhenInUseUsageDescription` | "لعرض الخدمات القريبة منك." |
| `LSApplicationQueriesSchemes` | `whatsapp`, `tg`, `sgnl`, `tel`, `sms`, `facetime` |
| `BGTaskSchedulerPermittedIdentifiers` | `app.sila.arham.refresh`, `app.sila.services.sync`, `app.sila.duplicates.scan`, `app.sila.arham.automation` |
| `UIBackgroundModes` | `fetch`, `processing`, `remote-notification` |

> [!IMPORTANT]
> **Critical Alerts Entitlement (iOS):** مطلوب لميزة رنين الطوارئ المتواصل — يجب التقدم لـ Apple بطلب خاص يوضح الاستخدام (طوارئ فقط). يسمح بتجاوز "عدم الإزعاج".
> قم بإضافة `com.apple.developer.usernotifications.critical-alerts` في الـ Entitlements file.

## 4. مصفوفة القدرات

| القدرة | أندرويد | iOS | سلوك iOS البديل |
|---|---|---|---|
| قراءة/كتابة جهات الاتصال | ✅ Contacts Provider | ✅ CNContactStore | — |
| سجل المكالمات | ⚠️ مقيّد بسياسة Play + `hard-restricted` (تتطلب معالج هاتف افتراضي أو استثناءً) — لا تُطلب في المرحلة 1 | ❌ لا API عام؛ iOS 26: سجل خلوي فقط كـ default dialer | مكالمات من التطبيق + CXCallObserver + تأكيد |
| رقم/اتجاه مكالمة هاتفية خارجية | ⚠️ `ROLE_CALL_SCREENING` بموافقة المستخدم — عند الإعداد فقط، بلا نهاية/مدة | ❌ بلا هوية (CXCall) | تأكيد عام أو يدوي |
| كشف نهاية المكالمة | ✅ TelephonyCallback — حالة فقط بلا رقم/اتجاه | ⚠️ أثناء عمل التطبيق فقط، وبلا هوية | سؤال التقييم عند العودة للتطبيق |
| نغمة لكل جهة اتصال | ✅ CUSTOM_RINGTONE | ❌ | إرشاد لفتح بطاقة Contacts |
| البريد الصوتي | ✅ SEND_TO_VOICEMAIL | ❌ | إخفاء الخيار |
| الحظر | ⚠️ `BlockedNumberContract` يتطلب Dialer افتراضي، وإلا Intent لإعدادات النظام | ✅ Call Directory Extension | — |
| اختصار الشاشة الرئيسية | ✅ Pinned Shortcut | ⚠️ | Widget / App Shortcut |
| نقل بين الحسابات | ✅ | ⚠️ Containers محدودة | إخفاء/تقييد |
| دمج المكرر | ✅ AggregationExceptions | ⚠️ يدوي | حذف + إعادة كتابة مع نسخة احتياطية |
| الشرائح | ⚠️ SubscriptionManager | ❌ | إدخال يدوي |
| التطبيقات المرتبطة | ✅ RawContacts MIME | ⚠️ URL schemes | — |
| الرنين المتواصل (طوارئ) | ✅ CALL_PHONE + Foreground Service | ⚠️ Critical Alert + `tel:` URL | يحتاج تأكيد يدوي لكل محاولة |
| أتمتة الرسائل الداخلية | ✅ WorkManager + FCM | ✅ BGTask + FCM | — |
| Smart Fallback (واتساب) | ✅ Intent `whatsapp://send` | ✅ URL scheme `whatsapp://` | — |

## 5. سياسة Google Play
- `READ_CALL_LOG` و `PROCESS_OUTGOING_CALLS`: صلاحيات **hard-restricted**، ومسموحة في Play فقط حين يكون التطبيق **default Phone أو Assistant handler**، أو ضمن **استثناء مؤهل مؤقت** (مثل التعريف/الحظر، الأتمتة، CRM المؤسسي؛ واستثناء التحقق من الحساب ينتهي في 2027-01-27) بشرط أن تكون الوظيفة أساسية ولا يوجد بديل لها. "Contact prioritization" و"SMS and contacts management" استخدامات غير صالحة لغير المعالج الافتراضي أو معالج جهات الاتصال على مستوى النظام. **المرحلة 1: لا تُطلب** — صِلَة ليس معالج هاتف افتراضي، والقرار غير محسوم في [ADR-0006](adr/0006-call-log-strategy.md).
- **لا التفاف:** تمنع Play استنتاج بيانات سجل المكالمات عبر صلاحيات/APIs/مصادر بديلة (مثل مستمع الإشعارات).
- **Data Safety Form:** تصريح دقيق: Contacts (على الجهاز فقط)، Phone number (للحساب)، Approximate location، App interactions.
- **حذف الحساب** متاح من داخل التطبيق ومن رابط ويب.

## 6. App Store
- شرح واضح لكل صلاحية، ورفض متوقع إن طلبنا Contacts بدون قيمة فورية — لذا يجب أن يظهر التنظيم مباشرة بعد المنح.
- Privacy Nutrition Labels مطابقة لـ Data Safety.
- Sign in with Apple غير مطلوب لأن التسجيل برقم الهاتف وليس عبر طرف ثالث.

## 7. حالة عدم وجود SIM / أجهزة Wi-Fi فقط (iPad/Tablet)

| السيناريو | السلوك |
|---|---|
| **بدون SIM — أندرويد** | التطبيق يعمل بالكامل كضيف (تصنيف + أرحام + دليل قراءة فقط). الاتصال عبر `ACTION_DIAL` يفتح لوحة الاتصال — النظام يتعامل مع عدم وجود SIM. التسجيل يتطلب SIM (OTP). |
| **بدون SIM — iOS** | نفس الأعلى + `canOpenURL("tel:")` يُرجع `false` ← أزرار الاتصال تُخفى ويُعرض "جهازك لا يدعم الاتصال" مع خيار نسخ الرقم. |
| **iPad / Tablet** | يعمل كاملاً باستثناء: لا اتصال مباشر، لا نغمات، لا سجل مكالمات ولا مراقبة مكالمات. أرحام تعتمد على التأكيد اليدوي فقط. |
| **Wi-Fi Calling** | يُكتشف عبر `TelephonyManager.isVoiceCapable()` (Android) / `CTTelephonyNetworkInfo` → إن متاح تُعرض أزرار الاتصال. |
| **رقم eSIM أو SIM مزدوج** | `SubscriptionManager` (Android) لعرض خيار الشريحة قبل الاتصال الخدمي؛ `preferredSimSlot` في `contact_phones`. iOS: لا API — يختار النظام. |
