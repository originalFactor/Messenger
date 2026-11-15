/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger

import android.content.Context
import cn.jpush.android.service.JPushMessageReceiver

class PushReceiver : JPushMessageReceiver() {
    override fun onRegister(context: Context, registrationId: String) {
        if (PushSetup.hasConsent(context)) PushSetup.onRegistered(registrationId)
    }
}
