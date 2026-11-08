package app.sila.feature.contacts.presentation.duplicates

import app.sila.core.model.Category
import app.sila.feature.contacts.domain.DuplicateReasonKind

/**
 * نصوص شاشة مراجعة المكررات (T7 · Issue #28) — عقد بلا Compose: يُموَّل في `sharedUI`
 * من موارد الترجمة بنمط [app.sila.feature.contacts.presentation.ContactsStrings].
 * لا نصوص ثابتة في الكود (AGENTS.md) — بما فيها دليل الكشف الخام ([DuplicateReasonKind]
 * وقيمته) الذي يُعرض عبر قوالب الموارد حصراً.
 */
interface DuplicatesReviewStrings {
    val screenTitle: String
    val backDescription: String
    val loading: String
    val emptyTitle: String
    val emptyMessage: String

    val confidenceHigh: String
    val confidenceMedium: String
    val confidenceLow: String

    /** الدليل المسيطر للمجموعة — [detail] القيمة الخام (رقم/بريد/اسم) كما وردت من الكشف. */
    val reasonLabel: (kind: DuplicateReasonKind, detail: String) -> String

    val categoryTitle: (category: Category) -> String

    val previewTitle: String
    val previewSummary: (phones: Int, emails: Int) -> String
    val previewNotesMerged: String
    val ringtoneConflictNotice: String

    val mergeButton: String
    val notDuplicatesButton: String
    val mergeAllConfirmed: (count: Int) -> String

    val undoMessage: String
    val undo: String

    val callMemberLabel: String

    val errorMerge: String
    val errorUndo: String
}
