# ---------------------------------------------------------------------------
# Messenger (shared KMP library) R8/ProGuard rules
# This module contains domain/data/presentation code shared between the
# Android and Desktop app shells. Full app-level rules (manifest components,
# Service/Activity subclasses) live in androidApp/proguard-rules.pro.
# ---------------------------------------------------------------------------

# --- Annotations / signatures / inner-class metadata -----------------------
# Gson needs Signature to resolve generic types; Retrofit needs
# RuntimeVisibleAnnotations to read @POST/@Query etc.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes AnnotationDefault,Exceptions,Deprecated
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ===========================================================================
# Cloud authentication and sync models (serialized reflectively via Gson)
# ===========================================================================
-keep class cc.ptoe.messenger.data.cloud.** { *; }
-keep class cc.ptoe.messenger.data.remote.dto.** { *; }
-keepclassmembers class cc.ptoe.messenger.data.remote.dto.** { *; }

# ===========================================================================

# ===========================================================================
# MobileHttpServer / MobileWearChatHandler (Wear bridge, lives in shared)
# ===========================================================================
-keep class cc.ptoe.messenger.data.wear.MobileHttpServer { *; }
-keep class cc.ptoe.messenger.data.wear.MobileHttpServer$* { *; }
-keep class cc.ptoe.messenger.data.wear.MobileWearChatHandler { *; }
