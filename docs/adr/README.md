# سجل القرارات المعمارية (ADRs)

| # | القرار | الحالة |
|---|---|---|
| [0001](0001-kmp-with-native-ui.md) | KMP لمشاركة المنطق مع واجهات أصلية منفصلة | مقبول |
| [0002](0002-ktor-instead-of-retrofit.md) | Ktor بدلاً من Retrofit | مقبول |
| [0003](0003-koin-for-di.md) | Koin بدلاً من Hilt | مقبول |
| [0004](0004-room-offline-first.md) | Room KMP كمصدر وحيد للحقيقة (Offline-First) | مقبول |
| [0005](0005-navigation.md) | Navigation Compose و NavigationStack | مقبول |
| [0006](0006-call-log-strategy.md) | التتبع العملي متعدد المصادر لمكالمات أرحام (B+ مع تأكيد احتياطي) | مقبول |
| [0007](0007-hybrid-feature-modularization.md) | التقسيم الهجين: طبقات داخل الميزات | مقبول |
| [0008](0008-room-lifecycle-bridge.md) | جسر دورة حياة Room بين المنصتين | مستبدل بـ [0011](0011-room3-single-schema-authority.md) |
| [0009](0009-contacts-platform-boundary.md) | حدود تكامل جهات الاتصال مع المنصات | مقبول |
| [0010](0010-incremental-contacts-sync.md) | المزامنة التزايدية لجهات الاتصال إلى Room | مقبول |
| [0011](0011-room3-single-schema-authority.md) | Room 3 مرجع المخطط الوحيد مع اعتمادٍ آمنٍ لقواعد ما قبل Room | مقبول |
| [0012](0012-contact-write-path-consistency.md) | اتساق مسار كتابة جهات الاتصال: المنصة أولاً ثم معاملة Room واحدة ثم تعويض | مقبول |
| [0013](0013-merge-write-path.md) | مسار كتابة الدمج: تجميع منصوي + معاملة Room واحدة + تراجع بجلسة الشاشة | مقترح |
| [0014](0014-settings-vm-ports-dip.md) | بوابات نطاق ضيقة لاعتمادات ViewModel الإعدادات (DIP كامل) | مقترح |
| [0015](0015-atomic-restore-transaction-passage.md) | استعادة النسخ الاحتياطي ذرّياً: ممرّ معاملة Room واحدة فوق `LocalSilaDatabase` | مقترح |
| [0016](0016-immediate-reminder-rescheduling-port.md) | إعادة جدولة تذكير أرحام من مسار الإعدادات: منفذ منصّي ضيق لا قرار مكرر | مقترح |
| [0017](0017-hand-rolled-backup-crypto-engine.md) | محرك التشفير الاحتياطي اليدوي: توثيق القابلية المشتركة وقفله بمتجهات KAT | مقترح |
| [0018](0018-macrobenchmark-device-gate-harness.md) | منصة قياس Macrobenchmark للعتاد المرجعي — موديول `:benchmark` وبنية `benchmark` | مقبول |
| [0019](0019-local-only-crash-capture.md) | التقاط الأعطال محلياً حصراً — بلا SDK سحابي في المرحلة 1 | مقبول |
| [0020](0020-guarded-release-signing.md) | مسار توقيع الإنتاج المحروس عبر أسرار CI — بلا مفاتيح في المستودع | مقبول |
| [0022](0022-full-reconcile-checkpoint-gate.md) | سلامة بوابة المصالحة الكاملة — لا ختم بلا استيراد يطابق الكتالوج | مقبول |

## القالب
```markdown
# ADR-XXXX: العنوان
- الحالة: مقترح | مقبول | مستبدل بـ ADR-YYYY
- التاريخ: YYYY-MM-DD
## السياق
## القرار
## البدائل المرفوضة
## العواقب
```
