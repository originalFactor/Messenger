# Codex ACP GUI feature audit

Checked 2026-11-10 against the locally resolved `@agentclientprotocol/codex-acp` **2.6.0**, its packaged README, and an isolated real ACP session. This is an audit of that adapter version, not a promise that every ACP Agent offers these features. No prompt or model generation was needed for the settings checks.

## Session settings

The real `initialize` / `session/new` exchange returned protocol 1 and five config options:

| ID | Meaning | Conversation GUI |
| --- | --- | --- |
| `model` | Model | Composer selector, ordered first |
| `reasoning_effort` | Reasoning effort (`thought_level`) | Composer selector, ordered second |
| `mode` | Approval/sandbox preset, including permission profiles when offered | Composer selector with Agent descriptions |
| `collaboration_mode` | Default / Plan collaboration | Composer selector |
| `fast-mode` | Fast mode | Composer toggle |

Previously these were only reachable through a generic settings dialog, and rendering every model as a radio row buried the later effort setting below a large catalog. Settings now load when opening the conversation. Both the composer and settings dialog use compact selectors. Controls scroll horizontally on narrow windows without wrapping their labels. All IDs, labels, values and choices come from the Agent; neither model names nor effort levels are hardcoded. The UI replaces the entire snapshot after successful changes, including model-dependent effort options, and keeps the confirmed values on errors. Changes are disabled during a prompt or another setting request.

## Other features and gaps

| Adapter feature | Current integration | Appropriate next GUI work |
| --- | --- | --- |
| Text, reasoning, tool calls, tool results | Normal chat bubbles, thought sections and tool cards; persisted/replayed | Keep normal chat styling |
| Shell/file/MCP/search/review tool activity | Generic tool cards and text/diff output | Richer typed presentations can be added; not all payloads have specialized rendering |
| Permission requests | Agent-provided choices in the conversation | Preserve explicit choices and descriptions |
| Cancel, session restore, local history | Implemented through Core | No extra conversation toggle needed |
| Slash commands and configured skills | Text can be submitted; `available_commands_update` is discarded | Add an Agent-driven command picker/autocomplete; do not hardcode the README command list |
| Plan updates and token usage | Dedicated events are discarded | Add compact plan/progress and usage displays |
| Images, embedded context, resource links | Core currently sends only text content; composer has no attachment action | Add negotiated attachment/context support through Core and normal composer UI |
| Generated/viewed images | No dedicated ACP image projection | Map supported image content into existing image rendering |
| Additional workspace directories | Not exposed | Project-scoped directory management, preserving the Project-only ACP rule |
| Session title/fork/delete/close and imported session list | No dedicated remote-session lifecycle GUI | Separate Agent-session operations from local conversation operations |
| Mid-turn steering | Not exposed; current composer stops rather than sends while generating | Requires explicit extension support and admission semantics |
| Async questions/forms | Not exposed | Negotiate and implement a form UI before advertising support |
| Native subagent sessions | Not negotiated; adapter documents a tool-call fallback | Optional expandable child-session navigation |
| Background tasks, long-running goals, AIR failure/diff/file-report extensions | Not negotiated | Implement the relevant extension contract before enabling controls |
| Authentication/providers/system-prompt configuration | No conversation GUI for these advertised capabilities | ACP Agent/account configuration is the appropriate home; avoid confusing it with Messenger Cloud or BYOK providers |

The existing Core notification handler explicitly handles text, thought, tool and configuration updates only. Consequently, a successful session handshake does **not** demonstrate support for plans, usage, commands, images or adapter extensions.

## Validation

- Real adapter: all five setting requests accepted, with an actual reasoning-effort change confirmed by the returned snapshot. Model/permission/fast/collaboration checks reapply the current value to the isolated session.
- Desktop Compose tests: grouped options preserve opaque values; pending requests disable selectors; model selection retains the confirmed value until the snapshot changes; updated effort choices replace the previous list.
- Android shared compilation and Desktop settings interaction tests pass.
- No end-to-end generated conversation or visual screenshot validation was performed in this audit.

Implementation: `AcpConversationScreen.kt`, `AcpSessionSettingsDialog.kt`, `AcpChatMessages.kt` and Core `external_acp.rs`. Adapter reference: [Codex ACP](https://github.com/agentclientprotocol/codex-acp).

## Subsequent composer integration

Settings now use a separate page and the plus panel. Image/file pickers and clipboard attachments send ACP content blocks; command suggestions consume Agent announcements; context usage displays Agent used/size values. During generation, sends queue in FIFO order; long-press Send and queued-message Steer use the extension with host-owned idle fallback. Queue state is local to the open screen. The dedicated plan display and the other extension gaps listed above remain outstanding.
