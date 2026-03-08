# ============================================================
# Tping App — ProGuard / R8 Rules
# ============================================================

# ---- Gson (reflection-based JSON) ----
# Gson uses reflection to serialize/deserialize — keep all fields
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Keep generic type info for Gson TypeToken
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Prevent R8 from stripping Gson used members
-keep,allowobfuscation,allowshrinking class com.google.gson.** { *; }

# ---- App data classes used with Gson ----
# Room entities (also used in export JSON)
-keep class com.xjanova.tping.data.entity.** { *; }

# License API
-keep class com.xjanova.tping.data.license.ApiResult { *; }
-keep class com.xjanova.tping.data.license.LicenseState { *; }
-keepclassmembers enum com.xjanova.tping.data.license.LicenseStatus { *; }

# Security — Integrity checker + certificate pinning
-keep class com.xjanova.tping.data.license.IntegrityChecker$IntegrityResult { *; }
-keep class com.xjanova.tping.data.license.CertPinning { *; }

# Cloud API
-keep class com.xjanova.tping.data.cloud.ApiResult { *; }
-keep class com.xjanova.tping.data.cloud.AuthState { *; }
-keep class com.xjanova.tping.data.cloud.SyncState { *; }

# Export/Import (JSON serialization)
-keep class com.xjanova.tping.data.export.TpingExportData { *; }
-keep class com.xjanova.tping.data.export.ExportedWorkflow { *; }
-keep class com.xjanova.tping.data.export.ExportedDataProfile { *; }
-keep class com.xjanova.tping.data.export.ImportResult { *; }
-keepclassmembers enum com.xjanova.tping.data.export.DuplicateStrategy { *; }

# Puzzle CAPTCHA config (serialized as JSON in RecordedAction)
-keep class com.xjanova.tping.puzzle.PuzzleConfig { *; }
-keep class com.xjanova.tping.puzzle.PuzzleRecordingState { *; }
-keepclassmembers enum com.xjanova.tping.puzzle.PuzzleRecordingStep { *; }

# Overlay data classes (used in Gson serialization)
-keep class com.xjanova.tping.overlay.WorkflowItem { *; }
-keep class com.xjanova.tping.overlay.ProfileCategoryItem { *; }
-keep class com.xjanova.tping.overlay.OverlayState { *; }

# Playback engine state
-keep class com.xjanova.tping.recorder.PlaybackEngine$PlaybackState { *; }

# Diagnostic reporter
-keep class com.xjanova.tping.data.diagnostic.DiagnosticReporter$SendResult { *; }

# ---- OkHttp ----
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ---- OpenCV ----
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# ---- ML Kit Barcode ----
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ---- CameraX ----
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ---- EncryptedSharedPreferences / Tink ----
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-keep class androidx.security.crypto.** { *; }

# ---- Room ----
# Room generates code at compile time via KSP; keep DAO interfaces
-keep class com.xjanova.tping.data.dao.** { *; }
-keep class com.xjanova.tping.data.database.TpingDatabase { *; }

# ---- Kotlin ----
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**

# ---- Kotlin Coroutines ----
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ---- Jetpack Compose ----
# Compose bundled rules handle most cases; keep composable lambdas
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ---- General keep rules ----
# Keep all enums (valueOf/values used via reflection)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep Serializable classes
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
