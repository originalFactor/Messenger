# ---------------------------------------------------------------------------
# Messenger (desktopApp) ProGuard rules
#
# The Desktop release build type runs ProGuard (the JVM equivalent of the R8
# step the Android modules run) with shrinking + optimization ENABLED and
# obfuscation DISABLED: Compose Desktop/Skiko/JNA look classes up by name in
# enough places that renaming buys nothing but crash reports nobody can read.
#
# JetBrains' `default-compose-desktop-rules.pro` (added automatically by the
# Compose plugin) already keeps kotlin.**, Skiko, the coroutines dispatchers and
# the Compose runtime; this file covers what it does not know about.
# ---------------------------------------------------------------------------

# ===========================================================================
# Rust core bridge (UniFFI-generated Kotlin + JNA)
# ===========================================================================
# The generated bindings declare JNA `Structure`/`Callback` subclasses and an
# interface loaded through `Native.load`; JNA reflects over their fields and
# method names, so the whole surface must survive verbatim. The cdylib itself
# is a classpath resource under JNA's `<os>-<arch>/` prefix — ProGuard copies
# resources untouched, so nothing to keep for the .dll/.so itself.
-keep class cc.ptoe.messenger.core.** { *; }
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**

# ===========================================================================
# App entry point
# ===========================================================================
# Nothing to keep: the Compose plugin's generated root config already keeps
# `public class cc.ptoe.messenger.MainKt { public static void main(String[]); }`.
# Widening that to `{ *; }` only pins synthetic lambda factories and produces
# "keeps the entry point but not the descriptor class" notes.

# ===========================================================================
# Kotlinx Serialization
# ===========================================================================
# ProGuard does not read the `META-INF/proguard/*.pro` files that ship inside
# kotlinx artifacts, so the serializer lookup rules are repeated here.
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers public class **$$serializer {
    private ** descriptor;
}
-dontnote kotlinx.serialization.**
-dontwarn kotlinx.serialization.internal.ClassValueReferences

# ===========================================================================
# Service-loader entry points
# ===========================================================================
# Coil 3 discovers network fetchers through ServiceLoader; Ktor discovers its
# engine container the same way. Shrinking them out empties the service file.
-keep class coil3.network.ktor3.internal.KtorNetworkFetcherServiceLoaderTarget { *; }
-keep class io.ktor.client.engine.okhttp.OkHttpEngineContainer { *; }

# ===========================================================================
# OkHttp / Okio (Ktor engine + Coil network stack)
# ===========================================================================
-keep,allowobfuscation,allowshrinking class org.conscrypt.** { *; }
-keep,allowobfuscation,allowshrinking class org.bouncycastle.** { *; }
-keep,allowobfuscation,allowshrinking class org.openjsse.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.graalvm.**
-dontwarn com.oracle.svm.**
-dontwarn org.slf4j.**

# ===========================================================================
# Coroutines / Compose resources
# ===========================================================================
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembers class kotlin.coroutines.SafeContinuation {
    volatile <fields>;
}
# Compose Multiplatform resources are read from the classpath by path; the
# generated accessors only need to keep working, not keep their names.
-dontwarn org.jetbrains.compose.resources.**
