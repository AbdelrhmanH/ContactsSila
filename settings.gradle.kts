rootProject.name = "ContactsSila"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

// ============================================================================
// 1. Core Modules (:core:*)
// ============================================================================
include(":core:common")
include(":core:model")
include(":core:database")
include(":core:datastore")
include(":core:designsystem")

// ============================================================================
// 2. Feature Modules — Hybrid Architecture (Layers as Sub-Modules inside Features)
// ============================================================================
// Feature: Contacts (محلي 100%)
include(":feature:contacts:domain")
include(":feature:contacts:data")
include(":feature:contacts:presentation")

// Feature: Arham (محلي 100%)
include(":feature:arham:domain")
include(":feature:arham:data")
include(":feature:arham:presentation")

// Feature: Services Directory (الدليل السحابي الحي)
include(":feature:services:domain")
include(":feature:services:data")
include(":feature:services:presentation")

// Feature: Settings & Preferences (الإعدادات والتفضيلات)
include(":feature:settings:domain")
include(":feature:settings:data")
include(":feature:settings:presentation")

// ============================================================================
// 3. Umbrella & Application Modules
// ============================================================================
include(":sharedLogic")
include(":sharedUI")
include(":androidApp")
