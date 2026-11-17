/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.presentation.ui.acp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.generated.resources.*
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.stringResource

internal fun acpSteeringBlocksInput(steering: Boolean, recoverySupported: Boolean, checking: Boolean,
    failed: Boolean, pending: JsonObject?): Boolean = steering || recoverySupported && (checking || failed || pending != null)

/** Reject malformed responses; treating an unknown state as clear could resubmit input. */
internal fun isValidAcpSteeringStatus(status: JsonObject): Boolean {
    if ((status["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull != "SteeringStatus") return false
    val pending = status["pending"] ?: return true
    val record = pending as? JsonObject ?: return false
    return listOf("requestId", "turnId").all { field ->
        (record[field] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.isNotBlank() == true
    } && (record["canAcknowledge"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull != null } == true
}

@Composable
internal fun AcpSteeringRecovery(pending: JsonObject?, checking: Boolean, failed: Boolean, active: Boolean,
    onCheck: () -> Unit, onAcknowledge: (String, String) -> Unit, modifier: Modifier = Modifier) {
    var confirming by remember(pending) { mutableStateOf(false) }
    Column(modifier) {
        if (failed) {
            Text(stringResource(Res.string.acp_steering_status_error), Modifier.padding(horizontal = 12.dp))
            TextButton(enabled = !checking, onClick = onCheck) { Text(stringResource(Res.string.acp_steering_check)) }
        }
        if (pending != null) Card(Modifier.fillMaxWidth().padding(12.dp)) { Column(Modifier.padding(12.dp)) {
            Text(stringResource(Res.string.acp_steering_unknown))
            val canAcknowledge = (pending["canAcknowledge"] as? JsonPrimitive)?.booleanOrNull == true
            if (canAcknowledge) TextButton(enabled = !checking, onClick = { confirming = true }) { Text(stringResource(Res.string.acp_steering_ack)) }
            else Text(stringResource(if (active) Res.string.acp_steering_unknown_wait else Res.string.acp_steering_unknown_unverified))
            TextButton(enabled = !checking, onClick = onCheck) { Text(stringResource(Res.string.acp_steering_check)) }
        } }
    }
    if (confirming && pending != null) AlertDialog(
        onDismissRequest = { confirming = false }, title = { Text(stringResource(Res.string.acp_steering_ack_title)) },
        text = { Text(stringResource(Res.string.acp_steering_ack_detail)) },
        confirmButton = { TextButton(enabled = !checking, onClick = {
            confirming = false
            onAcknowledge(pending.getValue("requestId").jsonPrimitive.content, pending.getValue("turnId").jsonPrimitive.content)
        }) { Text(stringResource(Res.string.acp_steering_ack)) } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(Res.string.action_cancel)) } },
    )
}
