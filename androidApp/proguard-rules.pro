# ---------------------------------------------------------------------------
# Messenger (androidApp) R8/ProGuard rules
#
# Full R8 minification + resource shrinking is enabled for release builds
# (androidApp, wear, runtime). The app shell itself is thin: the reflection-
# sensitive surface comes from
#   * the Rust core bridge (UniFFI-generated Kotlin + JNA), kept by
#     :core-bindings' consumer rules,
#   * the RaTeX renderer (JNI + kotlinx.serialization), kept by
#     :renderer-android's consumer rules,
#   * kotlinx.serialization (20 @Serializable DTOs decoded reified),
#   * OkHttp/Ktor/Coil service-loader entry points,
#   * the manifest-declared components and ucrop's activity,
#   * ViewModels instantiated reflectively by viewmodel-compose.
#
# There is no Room, Retrofit, Gson or CommonMark left in this app — do not
# re-add blanket rules for them.
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
-keepattributes KotlinMetadata

# ===========================================================================
# App manifest-declared components
# ===========================================================================
# Referenced from the manifest with the shorthand ".ClassName" form; be
# explicit so no keep-analysis subtlety can strip them.
-keep class cc.ptoe.messenger.MessengerApplication { *; }
-keep class cc.ptoe.messenger.MainActivity { *; }
-keep class cc.ptoe.messenger.RuntimeShellClient { *; }

# Declared by FQN in androidApp/src/main/AndroidManifest.xml but living in
# :shared's androidMain — R8 sees the class only through the manifest.
-keep class cc.ptoe.messenger.data.wear.MobileHttpServer { *; }
-keep class cc.ptoe.messenger.data.wear.MobileHttpServer$* { *; }
-keep class cc.ptoe.messenger.data.wear.MobileWearChatHandler { *; }

# ucrop's activity is manifest-declared (com.yalantis.ucrop.UCropActivity).
-keep class com.yalantis.ucrop.UCropActivity { *; }
-keep class com.yalantis.ucrop.** { *; }
-dontwarn com.yalantis.ucrop.**

# ===========================================================================
# Kotlinx Serialization (JSON DTOs, decoded reified)
# ===========================================================================
# The compiler plugin generates the serializers; these keep the runtime's
# reflective lookups (named companions, $serializer descriptor field) working
# under obfuscation. Mirrors kotlinx-serialization's own consumer rules, which
# also ship in the artifact's META-INF/proguard/.
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

# ===========================================================================
# Service-loader entry points (kept by name; R8 rewrites the service files)
# ===========================================================================
# Coil 3 resolves network fetchers through ServiceLoader.
-keep class coil3.network.ktor3.internal.KtorNetworkFetcherServiceLoaderTarget { *; }
# Ktor's engine auto-discovery (HttpClient(OkHttp) is used explicitly, but the
# service file still names the container).
-keep class io.ktor.client.engine.okhttp.OkHttpEngineContainer { *; }

# ===========================================================================
# OkHttp / Okio (WebSocket server for the Wear bridge, Ktor engine)
# ===========================================================================
# OkHttp's platform detection reflects over these optional TLS providers.
-keep,allowobfuscation,allowshrinking class org.conscrypt.** { *; }
-keep,allowobfuscation,allowshrinking class org.bouncycastle.** { *; }
-keep,allowobfuscation,allowshrinking class org.openjsse.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.graalvm.**
-dontwarn com.oracle.svm.**

# ===========================================================================
# Java-WebSocket (MobileHttpServer extends WebSocketServer)
# ===========================================================================
# The anonymous WebSocketServer / handshake subclasses are instantiated by the
# library through its own types; keep the server class graph intact.
-keep class org.java_websocket.** { *; }
-keep interface org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# ===========================================================================
# ViewModels (viewmodel-compose instantiates them reflectively)
# ===========================================================================
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    <init>(...);
}

# ===========================================================================
# Kotlin / Coroutines
# ===========================================================================
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keep class kotlin.coroutines.SafeContinuation { *; }
-keepclassmembers class kotlin.coroutines.SafeContinuation {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**
-keepclassmembers class **$Companion {
    *;
}
