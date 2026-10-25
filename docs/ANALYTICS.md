# مخطط التحليلات (Analytics Schema)

> جميع الأحداث تُرسل عبر **Firebase Analytics**. لا بيانات شخصية (أسماء/أرقام) في أي حدث.
> Redactor يُطبق على كل `param` من نوع String قبل الإرسال.

## 1. جهات الاتصال
| الحدث | المعاملات | النوع |
|---|---|---|
| `contact_created` | `category`, `has_photo`, `fields_count` | Action |
| `contact_edited` | `fields_changed[]` | Action |
| `contact_deleted` | `category` | Action |
| `contact_category_changed` | `from_category`, `to_category`, `source: smart_filter\|manual\|post_call` | Action |
| `contacts_merged` | `merge_count`, `confidence: high\|medium` | Action |
| `contacts_merge_undone` | — | Action |
| `smart_filter_review` | `suggested_count`, `accepted_count`, `rejected_count` | Action |
| `post_call_categorize_shown` | `contact_category` | Prompt |
| `post_call_categorize_answered` | `chosen_category` | Action |
| `duplicates_scan_completed` | `groups_found`, `high_confidence_count` | System |

## 2. دليل الخدمات
| الحدث | المعاملات | النوع |
|---|---|---|
| `directory_search` | `query_length`, `results_count`, `filters[]` | Action |
| `directory_call` | `service_id`, `visibility: personal\|community\|verified`, `source: search\|saved\|detail` | Action |
| `service_saved` | `service_id`, `visibility` | Action |
| `service_suggested` | `profession_id` | Action |
| `service_impression` | `service_id` | View (Batched) |
| `service_view` | `service_id` | View |
| `service_shared` | `service_id`, `method: qr\|vcard\|link` | Action |
| `rating_prompt_shown` | `service_id` | Prompt |
| `rating_prompt_answered` | `service_id`, `score: 1\|2\|3`, `has_comment` | Action |
| `rating_prompt_dismissed` | `service_id` | Action |
| `report_submitted` | `service_id`, `has_new_phone` | Action |
| `dispute_created` | `service_id`, `images_count` | Action |
| `dispute_resolved` | `service_id`, `days_to_resolve` | Action |
| `login_wall_shown` | `trigger: rating\|suggest\|report\|share` | Prompt |
| `login_wall_converted` | `trigger` | Conversion |

## 3. أرحام
| الحدث | المعاملات | النوع |
|---|---|---|
| `arham_added` | `relation`, `frequency` | Action |
| `arham_removed` | `relation` | Action |
| `arham_paused` | `relation`, `duration_days` | Action |
| `arham_call` | `status: overdue\|soon\|ok`, `source: list\|tree\|widget\|notification` | Action |
| `arham_manual_confirm` | `source: prompt\|button\|other_app` | Action |
| `arham_notification_shown` | `contacts_count` | System |
| `arham_notification_tapped` | `action: call\|snooze` | Action |
| `arham_notification_snoozed` | — | Action |
| `arham_celebration_shown` | `contact_relation` | System |
| `family_tree_opened` | `nodes_count` | Screen |
| `automation_created` | `type: in_app_message\|auto_ring`, `recurrence_type` | Action |
| `automation_toggled` | `enabled: true\|false` | Action |
| `automation_executed` | `type`, `fallback: fcm\|whatsapp\|sms_ui\|none` | System |
| `arham_message_sent` | `has_text` | Action |
| `arham_message_reaction` | `reaction: ❤️\|🤲\|👍\|😊` | Action |
| `emergency_ringing_started` | — | Action |
| `emergency_ringing_result` | `status: answered\|stopped\|max_reached\|blocked`, `attempts` | Action |

## 4. Onboarding والحساب
| الحدث | المعاملات | النوع |
|---|---|---|
| `onboarding_started` | — | Screen |
| `onboarding_step` | `step: welcome\|permissions\|top5\|profession\|complete` | Screen |
| `onboarding_completed` | `is_guest`, `duration_sec` | Conversion |
| `auth_started` | `method: phone` | Action |
| `auth_completed` | `is_new_user` | Conversion |
| `auth_failed` | `error_type` | Error |
| `profile_updated` | `fields_changed[]` | Action |
| `account_deleted` | — | Action |

## 5. النظام والأداء
| الحدث | المعاملات | النوع |
|---|---|---|
| `app_opened` | `is_guest`, `contacts_count_range`, `source: launcher\|notification\|deep_link\|widget` | System |
| `permission_requested` | `permission`, `screen` | System |
| `permission_granted` | `permission` | System |
| `permission_denied` | `permission`, `is_permanent` | System |
| `backup_exported` | `items_count` | Action |
| `backup_imported` | `matched_count`, `unmatched_count` | Action |
| `force_update_shown` | `current_version`, `min_version` | System |
| `in_app_review_shown` | `trigger: call\|arham\|organize` | Prompt |

## 6. قواعد عامة
- **لا PII (Personally Identifiable Information):** ممنوع إرسال أسماء، أرقام هواتف، عناوين بريد، أو أي بيانات شخصية.
- **Batching:** أحداث `service_impression` تُجمع محلياً وتُرسل كل 4 ساعات عبر `trackImpressions` Callable.
- **User Properties:** `is_guest`, `contacts_count_range` (0-100, 100-500, 500-1000, 1000+), `locale`, `theme`.
- **Conversion Events:** `onboarding_completed`, `auth_completed`, `login_wall_converted`, `service_saved` (أول مرة).
- **Debug:** `adb shell setprop debug.firebase.analytics.app app.sila.debug` لاختبار الأحداث في DebugView.
