# ---------------------------------------------------------------------------
# Messenger (runtime) R8/ProGuard rules
#
# Full R8 minification + resource shrinking is enabled for release builds
# (androidApp, wear, runtime). :runtime is a plain Android app with no Compose
# and no Kotlinx Serialization; its reflection/JNI-sensitive surface is:
#   * the vendored Termux terminal-emulator/terminal-view classes, one of which
#     is inflated BY FULLY-QUALIFIED NAME from res/layout/activity_terminal.xml,
#   * libtermux.so's JNI symbols (Java_com_termux_terminal_JNI_*), which are
#     resolved by class+method name at runtime — renaming them breaks the
#     terminal,
#   * the AIDL surface (ShellService, IShellService.Stub) and the Parcelable
#     ToolResult whose CREATOR the framework reads reflectively,
#   * the manifest-declared TerminalActivity / ShellService.
# ---------------------------------------------------------------------------

# --- Debuggability: keep line numbers for crash reports --------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes AnnotationDefault
-keepattributes Exceptions,Deprecated
-keepattributes KotlinMetadata

# ===========================================================================
# App manifest-declared components
# ===========================================================================
-keep class cc.ptoe.messenger.runtime.TerminalActivity { *; }
-keep class cc.ptoe.messenger.runtime.ShellService { *; }
-keep class cc.ptoe.messenger.runtime.ShellService$* { *; }

# ===========================================================================
# AIDL surface (cross-process; the client unmarshals these by name)
# ===========================================================================
# ToolResult is a Parcelable whose CREATOR field the framework looks up
# reflectively when the payload crosses the Binder boundary.
-keep class cc.ptoe.messenger.runtime.ToolResult { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
# Stub/Proxy classes generated from the .aidl files, plus the callback
# interfaces the service hands back to the caller.
-keep class cc.ptoe.messenger.runtime.IShellService { *; }
-keep class cc.ptoe.messenger.runtime.IShellService$* { *; }
-keep class cc.ptoe.messenger.runtime.IShellCallback { *; }
-keep class cc.ptoe.messenger.runtime.IShellCallback$* { *; }
-keep class cc.ptoe.messenger.runtime.IMcpCallback { *; }
-keep class cc.ptoe.messenger.runtime.IMcpCallback$* { *; }

# ===========================================================================
# Vendored Termux terminal (inflated by FQN + JNI)
# ===========================================================================
# res/layout/activity_terminal.xml names com.termux.view.TerminalView, so the
# class name and its (Context, AttributeSet) constructor must survive.
-keep class com.termux.view.TerminalView { *; }
-keep class com.termux.view.** { *; }
-keep class com.termux.terminal.** { *; }
-dontwarn com.termux.**

# Native methods are bound to libtermux.so by name (Java_com_termux_terminal_
# JNI_<method>); R8 must not rename the methods or their parameter types.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ===========================================================================
# Kotlin / Coroutines (ShellService dispatches every job on a scope)
# ===========================================================================
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembers class kotlin.coroutines.SafeContinuation {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**
