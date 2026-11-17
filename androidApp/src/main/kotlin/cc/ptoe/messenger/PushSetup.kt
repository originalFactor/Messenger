/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger

import android.content.Context
import cn.jiguang.api.utils.JCollectionAuth
import cn.jpush.android.api.JPushInterface
import com.google.firebase.messaging.FirebaseMessaging
import cc.ptoe.messenger.domain.repository.CloudFacade
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** JPush must not be initialized until the user explicitly opts in. */
object PushSetup {
    private const val PREFS = "push_setup"
    private const val CONSENT = "consented"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registrationId = MutableStateFlow("")

    fun startRegistration(context: Context, cloud: CloudFacade) {
        if (hasConsent(context)) JPushInterface.getRegistrationID(context)?.takeIf { it.isNotBlank() }?.let { registrationId.value = it }
        scope.launch {
            combine(cloud.user, cloud.serverUrl, registrationId) { user, _, id -> user to id }
                .collect { (user, id) ->
                    if (user != null && id.isNotBlank()) {
                        try { cloud.registerPushDevice(id) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { Log.w("PushSetup", "Unable to register push device", e) }
                    }
                }
        }
    }

    fun onRegistered(id: String) { registrationId.value = id }

    fun hasConsent(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CONSENT, false)

    fun initializeIfConsented(context: Context) {
        if (!hasConsent(context)) return
        JCollectionAuth.setAuth(context, true)
        JPushInterface.init(context)
        FirebaseMessaging.getInstance().isAutoInitEnabled = true
        JPushInterface.getRegistrationID(context)?.takeIf { it.isNotBlank() }?.let { registrationId.value = it }
    }

    fun enable(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(CONSENT, true).apply()
        initializeIfConsented(context)
    }
}
