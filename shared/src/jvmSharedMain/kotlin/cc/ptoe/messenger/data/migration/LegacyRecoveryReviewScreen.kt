/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Isolated review only: a platform must retain exclusive store ownership until either callback returns. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LegacyRecoveryReviewScreen(
    review: LegacyRecoveryReview,
    onCancel: () -> Unit,
    onApply: (LegacyRecoverySelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val choices = remember(review) { LegacyRecoveryChoices(review) }
    var projects by remember(review) { mutableStateOf(emptySet<String>()) }
    var conversations by remember(review) { mutableStateOf(emptySet<String>()) }
    var providers by remember(review) { mutableStateOf(emptySet<String>()) }
    var confirming by remember(review) { mutableStateOf(false) }
    var finished by remember(review) { mutableStateOf(false) }
    val selection = choices.selection(projects, conversations, providers)

    Scaffold(modifier = modifier.fillMaxSize(), topBar = {
        TopAppBar(title = { Text(stringResource(Res.string.legacy_review_title)) }, navigationIcon = {
            IconButton(enabled = !finished, onClick = { finished = true; onCancel() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.action_cancel)) }
        })
    }, bottomBar = {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(enabled = !finished, onClick = { finished = true; onCancel() }) { Text(stringResource(Res.string.action_cancel)) }
            Spacer(Modifier.width(8.dp))
            Button(enabled = !finished && selection != null, onClick = { confirming = true }) { Text(stringResource(Res.string.legacy_review_restore)) }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("legacy-recovery-list"), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(Res.string.legacy_review_intro), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(Res.string.legacy_review_partial), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(Res.string.legacy_review_sync_detail), style = MaterialTheme.typography.bodyMedium)
                if (projects.size + conversations.size + providers.size > 1_000) {
                    Text(stringResource(Res.string.legacy_review_selection_limit), color = MaterialTheme.colorScheme.error)
                }
            }
            item { Text(stringResource(Res.string.legacy_review_projects), style = MaterialTheme.typography.titleLarge) }
            item { Text(stringResource(Res.string.legacy_review_projects_detail), style = MaterialTheme.typography.bodyMedium) }
            items(review.source.projects, key = { "project:${it.id}" }) { pair ->
                val enabled = choices.canRestoreProject(pair.id)
                RecoveryRow(pair.id, enabled, pair.id in projects, { selected ->
                    projects = if (selected) projects + pair.id else projects - pair.id
                    conversations = conversations.filterTo(mutableSetOf()) { choices.canRestoreAssociation(it, projects) }
                }, projectText(pair.legacy), projectText(pair.core),
                    if (review.roomProjects.containsKey(pair.id)) projectText(review.roomProjects[pair.id]) else stringResource(Res.string.legacy_review_unknown))
            }
            item { Text(stringResource(Res.string.legacy_review_conversations), style = MaterialTheme.typography.titleLarge) }
            item { Text(stringResource(Res.string.legacy_review_conversations_detail), style = MaterialTheme.typography.bodyMedium) }
            items(review.source.conversations, key = { "conversation:${it.id}" }) { pair ->
                RecoveryRow(pair.id, choices.canRestoreAssociation(pair.id, projects), pair.id in conversations,
                    { selected -> conversations = if (selected) conversations + pair.id else conversations - pair.id },
                    conversationText(pair.legacy), conversationText(pair.core),
                    if (review.roomConversations.containsKey(pair.id)) conversationText(review.roomConversations[pair.id]) else stringResource(Res.string.legacy_review_unknown))
            }
            item { Text(stringResource(Res.string.legacy_review_providers), style = MaterialTheme.typography.titleLarge) }
            item { Text(stringResource(Res.string.legacy_review_providers_detail), style = MaterialTheme.typography.bodyMedium) }
            items(review.source.providers, key = { "provider:${it.id}" }) { pair ->
                RecoveryRow(pair.id, choices.canRestoreProviderFormat(pair.id), pair.id in providers,
                    { selected -> providers = if (selected) providers + pair.id else providers - pair.id },
                    providerText(pair.legacy), providerText(pair.core),
                    if (review.roomProviders.containsKey(pair.id)) providerText(review.roomProviders[pair.id]) else stringResource(Res.string.legacy_review_unknown))
            }
        }
    }

    if (confirming && selection != null) {
        AlertDialog(onDismissRequest = { confirming = false }, title = { Text(stringResource(Res.string.legacy_review_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.legacy_review_confirm_body, selection.projects.size,
                        selection.associations.size, selection.providerFormats.size))
                    selection.newProjectDeviceId?.let { Text(stringResource(Res.string.legacy_review_new_project_device, it)) }
                }
            },
            confirmButton = { TextButton(enabled = !finished, onClick = { finished = true; confirming = false; onApply(selection) }) { Text(stringResource(Res.string.action_confirm)) } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(Res.string.action_cancel)) } })
    }
}

@Composable
private fun RecoveryRow(id: String, enabled: Boolean, selected: Boolean, onSelect: (Boolean) -> Unit,
    old: String, core: String, room: String) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Checkbox(checked = selected, onCheckedChange = onSelect, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = id })
                Text(stringResource(Res.string.legacy_review_id, id), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
            }
            if (!enabled) Text(stringResource(Res.string.legacy_review_unavailable), style = MaterialTheme.typography.bodySmall)
            RecoveryValue(stringResource(Res.string.legacy_review_old), old)
            RecoveryValue(stringResource(Res.string.legacy_review_core), core)
            RecoveryValue(stringResource(Res.string.legacy_review_room), room)
        }
    }
}

@Composable
private fun RecoveryValue(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Text(value, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun projectText(project: LegacyRecoveryProject?): String {
    if (project == null) return stringResource(Res.string.legacy_review_absent)
    val device = if (!project.deviceIdKnown) stringResource(Res.string.legacy_review_unknown)
        else project.deviceId ?: stringResource(Res.string.legacy_review_none)
    return stringResource(Res.string.legacy_review_project_fields, project.name, project.workspace, device,
        project.createdAt.toString(), project.updatedAt.toString())
}

@Composable
private fun conversationText(conversation: LegacyRecoveryConversation?): String = conversation?.let {
    stringResource(Res.string.legacy_review_conversation_fields, it.title, it.projectId ?: stringResource(Res.string.legacy_review_none))
} ?: stringResource(Res.string.legacy_review_absent)

@Composable
private fun providerText(provider: LegacyRecoveryProvider?): String = provider?.let {
    stringResource(Res.string.legacy_review_provider_fields, it.name, it.apiFormat ?: stringResource(Res.string.legacy_review_unknown))
} ?: stringResource(Res.string.legacy_review_absent)
