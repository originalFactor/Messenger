# BakaSU settings component adaptation

Reference: BakaSU manager, commit `548d1dda060ca198823d8808804d01f5209ef4a9`.
Read `manager/AGENTS.md`, `SettingsBaseWidget.kt`, `SegmentedColumn.kt`,
`LazySegmentedColumn.kt`, `SettingsSwitchWidget.kt`, `SettingsJumpPageWidget.kt`,
and `material3internal/AnimatedShape.kt` before adapting the patterns.

## Applied patterns

- `SettingsItem` measures its leading, text and trailing slots once per layout pass,
  places them relatively for RTL, and uses one animated clip/background rather than
  stacking a Material ListItem inside another clipping surface.
- The Apache-2.0-marked animated-shape source is vendored with its copyright header;
  see `THIRD_PARTY_NOTICES.md`. Corner values are clamped during outline creation.
  Animation state is read in that phase, so animation frames do not recompose text.
- Existing switch, choice and navigation wrappers delegate to this renderer.
  Row-owned controls retain one accessible action and share their interaction source
  with the child switch/radio. Callbacks and Agent-confirmed values remain unchanged.
- `SettingsGroup` represents small static groups. `settingsItems` supplies stable
  keys, content types and first/last positions to lazy runtime collections. Main
  settings use the former; Agents, Providers/models, Projects/recent conversations,
  ACP and MCP inventories use the latter.
- All components remain in Messenger's common KMP component package. No Android-only
  resource/DI/blur dependency is introduced. Labels remain localized Compose resources.

The manager's GPL settings widgets are reference material, not copied source.
Only the individually Apache-marked animation file is imported. This adaptation
keeps Messenger's current version catalog, Core API, DI and release boundaries.
The requested “self-published” component scope was clarified as self-drawn components.

## Verification and limits

Compose tests verify that outline frames do not recompose text, a 1000-item group
composes only viewport entries, keyed reorder/removal retains row state, and 200%
font scaling/RTL keep trailing controls separate. Existing click/long-press,
Cloud and ACP tests exercise the shared wrappers. Android/Desktop compile checks
also cover the new common source.

These checks establish layout/composition behavior, not an FPS or startup speedup.
BakaSU's generated baseline profiles and app-specific benchmarks cannot be copied
as Messenger profiles; real-device macrobenchmarks remain separate work.
