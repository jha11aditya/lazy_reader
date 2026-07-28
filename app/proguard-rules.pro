# Lazy Reader release (R8) keep rules.
#
# R8 reasons about the static call graph. Anything reached only by reflection or
# from native/JNI code looks unreachable to it and gets renamed or deleted —
# producing crashes that appear ONLY in release builds. Everything kept below is
# reached one of those two ways.

# --- Flogger / R8 inlining (DO NOT REMOVE -dontoptimize) -------------------
# MediaPipe's Graph class holds a static FluentLogger built via
# FluentLogger.forEnclosingClass(), which determines the logging class by
# WALKING THE CALL STACK for a FluentLogger frame. R8's optimizer inlines that
# call into Graph.<clinit>, the frame disappears, the lookup throws
# IllegalStateException("no caller found on the stack for: ..."), and throwing
# inside a static initializer surfaces as ExceptionInInitializerError — the app
# hard-crashes the moment voice starts (verified on Nokia C21 Plus, 2026-07-28).
#
# Keep rules CANNOT fix this: -keep prevents removal and renaming, not inlining.
# Disabling the optimization pass is the actual fix. Shrinking (unused-code
# removal) and obfuscation still run, so most of the size win is retained.
-dontoptimize
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**

# --- MediaPipe Tasks Audio -------------------------------------------------
# MediaPipe's native (JNI) layer resolves Java classes and their members by
# string name, and constructs result/options objects from C++. Renaming any of
# it breaks the native<->Java boundary at runtime, not at build time.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# MediaPipe options/results are AutoValue-generated; the generated subclasses
# are instantiated reflectively rather than by a visible constructor call.
-keep class com.google.auto.value.** { *; }
-dontwarn com.google.auto.value.**

# Protobuf backs MediaPipe's graph/options serialization and relies on
# reflective field access over generated message classes.
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# JNI calls back into these; the method names are the contract.
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Room ------------------------------------------------------------------
# Room resolves the KSP-generated implementation by name at runtime
# (AppDatabase -> "AppDatabase_Impl"), so the name must survive obfuscation.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class com.lazyreader.data.AppDatabase_Impl { *; }
# Entities are mapped column->field reflectively by the generated code.
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# --- App model surface -----------------------------------------------------
# VoiceCommand is matched by string literal in decideCommand (not by enum name
# reflection), so it does not strictly need keeping today. Kept defensively
# because enum valueOf/name is the classic thing a later edit reintroduces
# without realising R8 will then break it silently in release only.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
