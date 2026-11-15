/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger

import android.content.Context
import cn.jiguang.api.utils.JCollectionAuth
import cn.jpush.android.api.JPushInterface
import com.google.firebase.messaging.FirebaseMessaging

/** JPush must not be initialized until the user explicitly opts in. */
object PushSetup {
    private const val PREFS = "push_setup"
    private const val CONSENT = "consented"

    fun hasConsent(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CONSENT, false)

    fun initializeIfConsented(context: Context) {
        if (!hasConsent(context)) return
        JCollectionAuth.setAuth(context, true)
        JPushInterface.init(context)
        FirebaseMessaging.getInstance().isAutoInitEnabled = true
    }

    fun enable(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(CONSENT, true).apply()
        initializeIfConsented(context)
    }
}
