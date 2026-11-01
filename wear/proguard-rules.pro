# ---------------------------------------------------------------------------
# Messenger (wear) R8/ProGuard rules
# Full R8 minification is enabled for release builds. The wear module has no
# Room / Retrofit / Gson / Java-WebSocket / CommonMark / ucrop — the only
# reflection-heavy surfaces are OkHttp (WebSocket client) and the anonymous
# NsdManager / WebSocketListener inner classes inside WearNetworkBridge.
# ---------------------------------------------------------------------------

# --- Debuggability: keep line numbers for crash reports --------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Annotations / signatures / inner-class metadata -----------------------
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes AnnotationDefault
-keepattributes Exceptions,Deprecated
-keepattributes KotlinModule

# ===========================================================================
# App manifest-declared components
# ===========================================================================
-keep class cc.ptoe.messenger.WearMessengerApplication { *; }
-keep class cc.ptoe.messenger.presentation.MainActivity { *; }

# ===========================================================================
# Wear network bridge (OkHttp WebSocket + NSD mDNS discovery)
# ===========================================================================
# WearNetworkBridge instantiates anonymous NsdManager.DiscoveryListener and
# WebSocketListener subclasses; keep the bridge and all of its inner classes.
-keep class cc.ptoe.messenger.data.WearNetworkBridge { *; }
-keep class cc.ptoe.messenger.data.WearNetworkBridge$* { *; }
-keep class cc.ptoe.messenger.data.WearBridgeClient { *; }
-keep class cc.ptoe.messenger.data.WearBridgeClient$* { *; }

# Connection-state sealed hierarchy + chat-frame sealed hierarchy used across
# StateFlow / SharedFlow boundaries.
-keep class cc.ptoe.messenger.data.WearConnectionState* { *; }
-keep class cc.ptoe.messenger.data.WearChatFrame* { *; }

# Wear data models + JSON codec (manual JSONObject serialization). Keep the
# protocol models stable because they cross StateFlow/SharedFlow and bridge
# boundaries, even though the current codec uses JSONObject rather than Gson.
-keep class cc.ptoe.messenger.data.WearAgent { *; }
-keep class cc.ptoe.messenger.data.WearConversation { *; }
-keep class cc.ptoe.messenger.data.WearMessageRole { *; }
-keep class cc.ptoe.messenger.data.WearChatMessage { *; }
-keep class cc.ptoe.messenger.data.WearSyncSnapshot { *; }
-keep class cc.ptoe.messenger.data.WearNewChatResponse { *; }
-keep class cc.ptoe.messenger.data.WearChatJsonCodec { *; }

# ===========================================================================
# OkHttp / Okio (WebSocket client + logging)
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
# Coil (image loading in chat bubbles)
# ===========================================================================
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
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$Companion { *; }

# ===========================================================================
# AndroidX / Compose (defensive)
# ===========================================================================
-keep class androidx.compose.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.datastore.** { *; }
-dontwarn androidx.compose.**
-dontwarn androidx.lifecycle.**

# ViewModel factories instantiated by reflection in viewmodel-compose.
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    <init>(...);
}
