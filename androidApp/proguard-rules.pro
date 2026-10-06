# ---------------------------------------------------------------------------
# Messenger (mobile) R8/ProGuard rules
# Full R8 minification is enabled for release builds. These rules keep every
# class that is loaded reflectively (Room, Retrofit/Gson, Java-WebSocket,
# CommonMark, ucrop) and the manifest-declared application/service classes.
# ---------------------------------------------------------------------------

# --- Debuggability: keep line numbers for crash reports --------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Annotations / signatures / inner-class metadata -----------------------
# Gson needs Signature to resolve generic types; Retrofit & Room need
# RuntimeVisibleAnnotations to read @POST/@Query/@Entity etc.; inner classes
# are needed for the anonymous WebSocketServer / WebSocketListener subclasses.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes AnnotationDefault
-keepattributes Exceptions,Deprecated

# ===========================================================================
# App manifest-declared components
# ===========================================================================
# R8 normally keeps manifest-referenced classes, but we reference them via the
# shorthand ".ClassName" form; be explicit to avoid any chance of stripping.
-keep class cc.ptoe.messenger.MessengerApplication { *; }
-keep class cc.ptoe.messenger.MainActivity { *; }
-keep class cc.ptoe.messenger.data.wear.MobileHttpServer { *; }

# Wear sync handler invoked by MobileHttpServer
-keep class cc.ptoe.messenger.data.wear.MobileWearChatHandler { *; }

# ===========================================================================
# Room
# ===========================================================================
# @Database subclass is instantiated by reflection inside RoomDatabase
-keep class cc.ptoe.messenger.data.local.MessengerDatabase { *; }
-keep class cc.ptoe.messenger.data.local.MessengerDatabase_* { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

# @Dao interfaces: Room generates the impl at compile time (KSP), but the
# interface must survive so the generated class can implement it.
-keep interface cc.ptoe.messenger.data.local.dao.* { *; }

# @Entity classes: Room reflects on field names for column mapping.
-keep @androidx.room.Entity class * { *; }

# Room internals we touch directly
-keep class androidx.room.RoomSQLiteQuery { *; }
-keep class * extends androidx.room.util.ShortcutUtil { *; }
-dontwarn androidx.room.**
-dontwarn androidx.room.paging.**

# ===========================================================================
# Retrofit + Gson
# ===========================================================================
# Retrofit reflects over the API interface (method annotations, parameter types).
-keep,allowobfuscation interface cc.ptoe.messenger.data.remote.api.OpenAiApi
-keepclassmembers interface cc.ptoe.messenger.data.remote.api.OpenAiApi { *; }
-keep class retrofit2.** { *; }
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keepclassmembers,allowshrinking,allowobfuscation class * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**

# Gson: reflects over field names + reads @SerializedName annotations.
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class com.google.gson.JsonElement { *; }
-keepclasseswithmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# DTOs are serialized via Gson both through Retrofit (request/response bodies)
# and directly via Gson().fromJson() in ChatStreamParser (SSE chunks).
-keep class cc.ptoe.messenger.data.remote.dto.** { *; }
-keepclassmembers class cc.ptoe.messenger.data.remote.dto.** { *; }

# Cloud authentication and sync models are also serialized reflectively through
# Retrofit/Gson. Keep the whole small cloud package so R8 cannot rename or
# remove request fields such as CredentialsRequest.email/password, the private
# CloudApi method annotations, or the response models.
-keep class cc.ptoe.messenger.data.cloud.** { *; }

# Kotlin metadata so Gson can still see Kotlin data-class field info.
-keep class kotlin.Metadata { *; }
-keepattributes KotlinModule

# ===========================================================================
# OkHttp / Okio
# ===========================================================================
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }
-keep interface okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# OkHttp platform detection uses reflection on platform-specific classes.
-keep,allowobfuscation,allowshrinking class org.conscrypt.** { *; }
-keep,allowobfuscation,allowshrinking class org.bouncycastle.** { *; }
-keep,allowobfuscation,allowshrinking class org.openjsse.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ===========================================================================
# Java-WebSocket (MobileHttpServer extends WebSocketServer)
# ===========================================================================
-keep class org.java_websocket.** { *; }
-keep interface org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# Keep the anonymous WebSocketServer subclass inside MobileHttpServer.
-keep class cc.ptoe.messenger.data.wear.MobileHttpServer$* { *; }

# ===========================================================================
# CommonMark (markdown rendering)
# ===========================================================================
# CommonMark loads node/visitor/extension classes reflectively.
-keep class org.commonmark.** { *; }
-keep interface org.commonmark.** { *; }
-dontwarn org.commonmark.**

# ===========================================================================
# RaTeX (LaTeX rendering)
# ===========================================================================
# LaTeX rendering is backed by RaTeX-CMP (Rust core). Keep the renderer stack
# intact for release builds in case RaTeX-CMP does not ship its own consumer rules.
-keep class io.ratex.** { *; }
-keep class io.ratex.**$* { *; }
-dontwarn io.ratex.**

# ===========================================================================
# ucrop (image cropping) — UCropActivity is declared in the manifest
# ===========================================================================
-keep class com.yalantis.ucrop.** { *; }
-keep interface com.yalantis.ucrop.** { *; }
-keepclassmembers class com.yalantis.ucrop.** { *; }
-dontwarn com.yalantis.ucrop.**

# ===========================================================================
# Coil (image loading)
# ===========================================================================
# Coil ships consumer rules, but be explicit to survive API churn.
-keep class coil.** { *; }
-dontwarn coil.**

# ===========================================================================
# Kotlin / Coroutines
# ===========================================================================
-keep class kotlin.coroutines.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# Keep Kotlin Companion object instances so reflective callers (Coil, etc.)
# can still find them.
-keepclassmembers class **$Companion {
    *;
}

# ===========================================================================
# AndroidX / Compose (defensive — most ship their own consumer rules)
# ===========================================================================
-keep class androidx.compose.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.navigation.** { *; }
-keep class androidx.datastore.** { *; }
-keep class androidx.security.** { *; }
-dontwarn androidx.compose.**
-dontwarn androidx.lifecycle.**
-dontwarn androidx.navigation.**

# ViewModel factories instantiated by reflection in viewmodel-compose.
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    <init>(...);
}
