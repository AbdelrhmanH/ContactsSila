# ADR-0014: بوابات نطاق ضيقة لاعتمادات ViewModel الإعدادات — DIP مكتمل بلا مسارات مزدوجة
- **الحالة:** مقترح — يُعتمد بمراجعة PR تذكرة #66
- **التاريخ:** 2026-11-09
- **يستكمل:** [ADR-0007](0007-hybrid-feature-modularization.md) (طبقات داخل الميزات)، [ADR-0003](0003-koin-for-di.md) (Koin كنقطة تركيب)، `docs/CODING_GUIDELINES.md` (نمط `VerbNounUseCase`)

## السياق

المراجعة العكسية لـ T9 (#30) كشفت بالبند S2-11 (تذكرة #66) أن `SettingsViewModel` — رغم KDoc يزعم SRP & DIP:
- **13 نية كان ينفذها المستودع مباشرة** من `onIntent` (بعد إضافة مساري الربط اليدوي في #62)،
- و**10 مسارات حقن مزدوجة** بصيغة `useCase?.invoke(a) ?: repository?.setX(a)` — أي أن ما يُختبَر (الـ UseCase) قد لا يكون المسار الفعلي، والمسار الفعلي (المستودع) يتجاوز طبقة نطاق الميزة تماماً،
- و**22 معامل بناء** — حالة حقن غير قابلة للتراكم (كل استخدام يختار مجموعة جانب أخرى من الـ UseCases، والباقي fallback).

## القرار

### 1. بوابات نطاق ضيقة في النطاق (`:feature:settings:domain`) — الواجهة الوحيدة للـ ViewModel

ثلاث بوابات، كل واحدة تعكس صنف الاعتماد لا التقسيم التقني:

- `SettingsPreferencesPort` — مراقبة التفضيلات وجميع كتاباتها (~24 عملية).
- `SettingsBackupPort` — تصدير/معاينة/استعادة النسخة + بحث مرشّحي الربط وربطه.
- `SettingsRingtonePort` — نغمات التصنيفات (قراءة المنصة + كتابة التفضيل).

الـ ViewModel الآن **بلا عضو مستودع إطلاقاً** ويقبل البوابات الثلاث + `AppDispatchers` (4 معاملات بديل الـ 22).

### 2. التنفيذات تجري فوق حالات استخدام أحادية المسؤولية — المسار الواحد

`SettingsPreferencesPortImpl` / `SettingsBackupPortImpl` / `SettingsRingtonePortImpl` تُجمّع الـ UseCases الفردية (نمط `Set*UseCase`) فوق `SettingsRepository` — أي أن **كل مسار يمر عبر UseCase حقيقية**، لا عبر المستودع مباشرة. عشر UseCases جديدة أُنشئت للمسارات التي كانت تنفذ المستودع مباشرة (`SetDefaultTab`/`SetVisibleAccounts`/`SetDefaultSimSlot`/`SetArhamMaxPerDay`/`SetConfettiEnabled`/`SetDefaultCity`/`SetSearchRadiusKm`/`SetQuickRatingEnabled`/`SetAutoUpdatePhonebookServices`/`SetPostCallCategorizeEnabled`/`SetInterestTagsUseCase`) استكمالاً للنمط القائم، ومسارا #62 صارا UseCase (`SearchLinkCandidatesUseCase`/`LinkUnmatchedEntryUseCase`) — بذلك يكتمل النمط على ثلاثة عشر مساراً.

### 3. قاعدة العمل في [SearchLinkCandidatesUseCase] — لا في الواجهة

قاعدة «أقل من حرفين لا يستهلك بحثاً» هي منطق أعمال وليست تفصيل واجهة، فانتقلت من الـ ViewModel إلى الـ UseCase (اختبارية بشكل منفصل، ولا يمكن تجاوزها من أي مستهلك مستقبلي للبوابة). الـ ViewModel يمرر الاستعلام كما هو — لا اقليماً ولا تقليم سلوك.

### 4. نقطة تركيب واحدة

الـ ViewModel يُحتفظ بمُنشئ تركيب واحد `(repository, cryptoEngine?, platformRingtoneSource?, dispatchers)` يبني البوابات الثلاث — يستخدمه Koin والاختبارات معاً، فلا يوجد توقيعان للحقن. غياب محرك التشفير يجعل التصدير/المعاينة/الاستعادة غير مهيأة بالتنفيذ نفسه (رمي `IllegalStateException` بنفس رسائل السلوك السابق، وتحولها إلى `BackupErrorKind` في الـ ViewModel دون تغيير).

### 5. تقليم أسلاك Koin الميتة

مصانع `Settings*UseCase` في `KoinModule` كانت تُربط حصراً من أجل استخدام الـ ViewModel قبله — بعد البوابات تنشئ الـ UseCases بنفسها، فألغيت تلك الأسلاك (`ObserveCategoryRingtonesUseCase` و`GetEffectiveRingtoneUseCase` كانا أصلاً بلا مستهلك إنتاجي — الصنفان باقيان مع اختباراتهما، وإعادة ربطهما متى ظهر مستهلك فعلي).

## البدائل المرفوضة

- **حقن `SettingsRepository` مباشرة في الـ ViewModel** (الوضع القائم): يخلّ بمبدأ DIP لطبقة الميزة ويجعل كل نية مساراً منفصلاً على المستودع — رُفض.
- **حذف الأ IndustrialCases الفردية والطي داخل البوابات:** يفقد الاختبارات الأحادية لكل سلوك ونمط التسمية في `CODING_GUIDELINES.md` — رُفض.
- **الإبقاء على الـ 22 معاملاً بتحقق إضافي:** لا يعالج انفجار حالة الحقن ولا ازدواج المسار — رُفض.
- **بوابة بحقول دوال (lambdas) بدل واجهات:** بلا توقيع قابل للتوثيق أو KDoc موحد — رُفض.

## العواقب

- **إيجابية:** مسار واحد مُختبر لكل سلوك؛ فحص معماري `checkArchitectureBoundaries` يرن الـ ViewModel بلا أدوار مستودع؛ الاختبارات المضيفة للتأكد من المسار الفعلي كلها تعمل عبر البوابات (تنفيذ UseCase حقيقي فوق مستودع مزيّف).
- **تكلفة:** ثلاث واجهات وثلاثة منفذات تركيب مكتوبة بوضوح (~250 سطراً توثيقية) مقابل إزالة الانفجار الاصطناعي للمعاملات؛ أي سلوك إعدادات مستقبلي يُنمى عبر: UseCase + توسيع بوابة نطاقة بدل معامل بناء إضافي.
- **تأثير iOS:** صفر — `SettingsViewModelStore` والـ SKIE المستعارة لا ترى البوابات (كلها Kotlin-side)، وتركيب iOS نفسه لم يتغير (`ensureStarted().get()`).

- **إيجابية حداً:** البوابات تعيش داخل نطاق الميزة (`:feature:settings:domain`) حصراً — لا شيء خارجها يستوردها، والحزام المعماري `checkArchitectureBoundaries` يبقى حارساً لحدود ADR-0007.
