# UniFFI-generated Kotlin bindings drive the native library through JNA
# reflection; keep the generated surface intact when a consumer re-enables
# shrinking (:wear runs R8 today).
-keep class cc.ptoe.messenger.core.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
