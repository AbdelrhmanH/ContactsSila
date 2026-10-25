# === Sila ProGuard / R8 Rules (CODING_GUIDELINES.md §13) ===

# Koin DI
-keep class org.koin.** { *; }
-keepclassmembers class * { @org.koin.core.annotation.* <methods>; }

# Room KMP
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class * { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * { @kotlinx.serialization.Serializable *; }

# Crashlytics / Stacktrace line numbers
-keepattributes SourceFile, LineNumberTable
-keep public class * extends java.lang.Exception
