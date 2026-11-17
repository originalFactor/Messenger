# Rust Core 退役交接

更新时间：2026-11-17。接手人：请先完整阅读根目录和相关模块的 `AGENTS.md`。本文是工作现场记录，不是发布批准或功能完成证明。

## 目标与约束

用户要求：Android debug 支持 `arm64-v8a`；缩短 debug/CI 编译时间并减少发布体积；移除草稿 TUI；将业务 Core 迁回 Kotlin/Room；将用户维护的 `llm-typewriter` 作为子模块并改用 Compose LaTeX；保留首个 Kotlin 版本的现有功能。用户特别确认要保留 MCP、ACP、远程控制、定时任务等功能，并愿意从旧 Room 数据恢复。

最终要求是在完成 Kotlin 生产切换、基础测试和代码/构建依赖扫描后，**单独一个 commit 删除 Rust Core**。目前尚未满足条件，不要删除 `core/rust`、绑定层或 CI 的 Rust 构建，不要把隔离原型当成已发布功能。不得自动推送。

## 当前状态

- Android `MessengerApplication.kt` 和 Desktop `Main.kt` 仍启动 Rust；`KotlinRoomBackendSlice` 是隔离组合，`asAppBackend()` 仍拒绝 ACP owner 和远程设备 host。生产切换未完成。
- 草稿 TUI 已移除（`eca2939`）；Android debug 默认 ABI 为 `arm64-v8a` 和 `x86_64`（`824f429`）。renderer 子模块已嵌入并使用 Compose LaTeX；子模块当前提交 `cb42531`。
- Kotlin/Room 已有大量隔离实现和测试：聊天、Cloud、Learning、图片、生成应用、定时任务、ACP、MCP、远控及迁移。部分能力仍有限制，具体以 `shared/AGENTS.md` 和各实现文档为准。
- 最近工作检查点：`32c01a6`（隔离候选工作区归档导入）、`ac0f9c5`（持久 workspace reservation）、`d92f7d2`（ACP 设置撤销远控）、`3c563b9`（ACP 设置、工作区恢复原型）、`31efca7`/`29a980b`（ACP Agent/project/provider/model 写入保护）、`892720a`（Runtime ACP 会话协议与设备测试变体）、`f21459b`（旧库审查记录与 Cloud 派发）、`b7f9ad6`（隔离远程文字及 BYOK 接管）、`cb852df`（恢复及 ACP 接管原型）。`git log` 包含更早的阶段提交。
- 估计曾向用户说明约 **2–6 周全职工程量**，不保证交付日期；这是粗估，平台隔离、数据迁移及跨平台测试可能改变它。

## 现场恢复与验证（2026-11-17 续）

上一轮交接的三组改动已按功能复审、定向测试并分别提交（`d92f7d2`、`ac0f9c5`、`32c01a6`），生产仍保持 Rust 启动且所有原型均未接入：

1. `KotlinControlSettingsBridge.kt` 和 `KotlinRoomAcpSliceTest.kt`（`d92f7d2`）：ACP profile 保存先撤销远控，再等待 ACP worker 并检查原 session/epoch；即使新 profile 或名称无效也先撤销。`KotlinRoomBackendSlice.kt` 的 `configurationWrite` 接线和 `PartialChatBridge` 设置调用 shutdown 生命周期已在 `3c563b9` 提交。相关 `shared/AGENTS.md` 已同步。定向 shared 测试及 Android Kotlin 编译通过；这不是全量回归或生产 worker 证明。
2. `RoomWorkspaceExecutionFence.kt`、`RoomWorkspaceInvocationLedger.kt` 和对应测试（`ac0f9c5`）：持久 workspace invocation ledger 与隔离执行 fence 组合，旧仅内存执行入口禁用，完成结果保留 reservation 直到明确确认；取消或异常保留 reservation 直到 stop barrier 支持的显式确认。5 项 fence 测试、4 项 ledger 测试及关联的 ACP/控制设置/授权测试均通过。仍缺 Cloud 401 同步失效、所有写入者围栏、可信 sender 身份和真实 worker 进程树停止证明，不能接入生产。
3. `workspace-transfer-shared/WorkspaceArchive.kt`、`WorkspaceTransfer.kt`、`AndroidWorkspaceCandidateStager.kt`、设备测试及两个 Gradle/AGENTS 文件（`32c01a6`）：候选跨 UID 工作区导入，源归档由调用方提供、导入到新建 app-private 目录，保留原件。共用源码注册到两个 Kotlin source set；Runtime 单元测试、Runtime/Android debug APK 和 instrumentation APK 构建通过。隔离 `emulator-5562` 上跨 UID 候选设备测试 1/1 通过，Runtime 工作区设备测试 5/5 通过（含 adbd root 创建后交给普通 Runtime UID 的真实硬链接夹具，已清理）。这不是可用的 Runtime 新 UID 迁移，不证明旧任务结束、来源快照一致或全局元数据无损。

续作检查点：`1f1ba9b` 修复 `:shared:allTests` 的 5 项既有失败（委派 ACP 未知结果、Core sidecar 别名、scheduler proof 计数、image recovery 顺序及 Compose 测试污染），901 项测试通过、1 skipped。`c491be6` 让 `ServerHandle.stop()` join 随服务器启动的 remote-control 与 scheduler 循环，并有 1 项 Rust 回归测试；`cargo test --workspace` 通过（其中 `messenger-http` 143 项通过、2 ignored）。这仍不证明所有请求级任务、ACP/Runtime 后代或旧进程停止；Android 生产切换仍不可执行。Wine 11.21 的简单 Windows 文件创建/读取/删除检查通过，`icacls`、硬链接和 file-ID 命令在该 Wine 环境中不可用；这些结果仅作为有限环境证据，不证明 NTFS ACL、持久化、链接、句柄身份或目录 fsync。

`shared/docs/RUST_CORE_DEPENDENCY_AUDIT.md` 与 `.github/scripts/audit-rust-core-dependencies.sh` 提供最终删除前的源码、Gradle、CI、AIDL、ProGuard、文档、版本脚本及 native 产物扫描。当前扫描仍列出 Android/Desktop 生产启动、绑定模块、Cargo/UniFFI CI 与 native 库，确认不能执行“单独删除 Rust Core”的最终提交。

隔离 workspace relay 原型新增精确 Cloud session 失效入口：`RoomWorkspaceExecutionFence.invalidateSession` 在等待 fence 前记录失效、取消已注册的 in-flight 调用，并阻止后续 admission/mutation；新增 1 项定向测试，`:shared:allTests` 全量再次通过（901 项，1 skipped）。该入口仍未由真实 Cloud 401 回调调用，不认证 sender，也没有所有写入者围栏或平台 worker-stop 证明；生产 Relay 仍保持 Core。

`RoomClearAll` 的隔离原型现在支持迁移后 store 的 proof-gated reset：显式 `RoomCoreSourceRetirement` 必须验证精确 Core 源/发布/内容身份；原子重置保存退休回执、移除迁移身份，中断恢复重做同一证明；未知或伪造状态仍 fail-closed，且没有生产平台实现。新增 `RoomClearAllTest` 覆盖无证明拒绝、中断恢复和回执保留。它不证明 Core 源文件、sidecar、备份、凭据或 flash 已被安全擦除，也不构成生产 clear-all。

`AppContainer` 新增 `shutdownCompleted` 状态：失败关闭仍可重试，成功关闭后拒绝 clear/reinit；新增 `AppBackendTest` 覆盖失败/成功状态转换。`shared:allTests` 再次通过（901 项，1 skipped）。这只是注入 backend 的生命周期合同，不切换任何 app shell，也不提供平台 worker-stop 证明。

`core/rust/crates/messenger-http/docs/BACKGROUND_TASKS.md` 盘点 `ServerHandle.stop()` 已跟踪与仍未跟踪的请求级任务，并给出外部进程/付费请求 stop barrier 和测试顺序。`ApiState.tasks` 已实现 owner-scoped 本地任务跟踪并接入 `ServerHandle.stop()`：turn worker、background replay/event、control dispatch/queue_sync、remote client lane 和单个 scheduled execution 等本地 `tokio::spawn` 会登记、在 stop 时 abort/join；回归测试包含 pending tracked task。`cargo test --workspace` 全绿（`messenger-http` 143 项通过、2 ignored）。image job、agent relay、ACP/Runtime 子进程、付费 provider 请求和远程 tool 副作用仍明确列为未证明停止，不能据此切换生产。

`AgentRelay` 的 loopback listener 现在由 `DELETE /control/agent-relay` 与 `ServerHandle.stop()` 显式 cancel/join，stop 测试会先启动 relay 再验证关闭；`cargo test --workspace` 再次全绿。其 upstream SSE conversion worker、image job、ACP/Runtime 子进程、付费 provider 请求和远程 tool 副作用仍未证明停止，生产切换仍禁止。

`ImageGenerationService.stop_and_join()` 现在由 `ServerHandle.stop()` 和 `/local-data` 清理调用：先取消 active job token，再 join 本地 worker，并保留 `Cancelled`/`Unknown` 的“远程可能已生成/计费”可见记录；新增 `stop_joins_worker_and_keeps_charge_uncertainty` 回归测试，`cargo test --workspace` 全绿。它不证明 provider 端付费/生成副作用停止，也不允许自动重放；upstream SSE conversion、ACP/Runtime 子进程与远程 tool 副作用仍列为未证明停止。

Rust 生命周期本轮提交：`b362dbc`（`ApiState.tasks` 跟踪并 join 本地请求任务）、`35a32e7`（AgentRelay listener join）、`3e9fd2b`（image worker cancel/join 与收费不确定记录）。每次均通过 `cargo test --workspace`；`BACKGROUND_TASKS.md`、`core/rust/AGENTS.md` 和 Android cutover 文档同步。最终硬阻塞仍在：Android/Desktop 生产仍启动 Rust，`asAppBackend()` 仍拒绝 ACP/device host 且 clear-all 抛错，依赖审计仍有 326 行命中，`core/rust` 未删除。外部 ACP/Runtime 后代、upstream/provider 流、付费或远程副作用没有平台 stop 证明；旧数据完整恢复、功能对等、Windows/真实设备验证、生产切换和最终单独删除 commit 仍未完成。

`ExternalSessions::stop_and_join()` 已把 Rust 进程内的 external-ACP worker task 取消并 join 到 `ServerHandle.stop()`，并有 `stop_joins_local_acp_worker_tasks` 回归测试；`cargo test --workspace` 通过。它仍不证明 OS 子进程树、Android Runtime 独立 UID 后代、per-worker MCP listener、upstream/provider 流或远程副作用停止。

`acp_mcp` 的 per-worker listener/RPC 与 `android_acp` 的本地 duplex 转发任务现已登记到 `ApiState.tasks` 并由 `ServerHandle.stop()` abort/join；`cargo test --workspace` 再次通过。仍未证明 OS 子进程树、Android Runtime 独立 UID 后代、upstream/provider 流或远程副作用停止，生产切换仍禁止。

审计脚本已修好 clean-tree 计数并增强：`--strict` 在仍存在生产/构建引用时返回非零，`--summary FILE` 输出 `blocking_references`，`test-audit-rust-core-dependencies.sh` 覆盖当前阻断树与 clean tree，并在 CI selector job 中运行。当前真实树严格审计结果仍为 `blocking_references=29`、`rust_workspace=present`、`native_bindings=present`，明确不能删除 Core。`server` 子模块另有 `13261bb`，为禁用 workspace relay v2 增加可注入的 durable replay ledger 合同；无 ledger 时 503，reserve 重复/失败 fail-closed，测试覆盖跨 relay 实例 replay 拒绝；仍无生产设备 verifier 或持久账本实现，父仓库 gitlink 未提交。

旧 Room 恢复现在支持 v20 的只读差异预览：`projectsAvailable=false`，不伪造 v21 才有的项目/关联恢复选项，只比较 provider 名称和标题，v20 provider `apiFormat` 保持未知；v19/v23 与 malformed schema 仍明确拒绝。新增 `v20PreviewKeepsProviderAndTitleComparisonWithoutProjectRecovery`，并修复 `McpManager.start()` 在 owner shutdown 后异步初始化产生的未捕获 `MCP manager is shut down` 异常；`:shared:allTests` 全量通过（905 项，1 skipped）。这不等于完整 v20 数据迁移或生产恢复 UI。

`KotlinRoomBackendSlice.asAppBackend().clearLocalData` 现可注入显式 `IsolatedRoomClearAllConfig` proof；默认仍抛错 fail-closed。`KotlinRoomAppBackendTest` 验证 `AppContainer.clearAllDataAndReinit()` 在提供 proof 时只调用该证明，未接入生产平台清理实现；`:shared:allTests` 再次通过。它不替代真实独占 owner、外部文件清理、Core 源退休或凭据擦除证明。

`RoomCloudCoordinator.rejectSession` 现把精确 authenticated 401 接到 `RoomWorkspaceExecutionFence.invalidateSession(accountId, generation)`：同 Room owner 的 workspace fence 会取消已登记 in-flight 调用并阻止后续 admission/mutation，直到独立 proof-backed recovery；新 generation 需要新 fence 实例。新增 coordinator 级回归测试，`:shared:allTests` 通过（905 项，1 skipped）。sender 身份认证、全写入者围栏和平台 worker-stop 证明仍缺，Relay 仍未接入生产。

本轮后续检查点：`46f2bf9`（迁移后 Room clear-all 的精确 Core 源退休证明与持久回执）、`4d5a0de`（`AppContainer.shutdownCompleted` 与关闭后 clear 拒绝）、`95ff79d`（Rust 请求级后台任务 stop 清单）均在本地仓库提交；`server` 子模块另有 `9b6a41b`，为硬禁用 workspace relay v2 增加 durable device enrollment verifier 接口，缺少真实 verifier 时关闭连接，父仓库 gitlink 未提交。`:shared:allTests` 在本轮每项主要改动后通过（901 项，1 skipped），`cargo test --workspace` 通过，server `pnpm lint` / `pnpm typecheck` / workspace+control relay 测试（5 项）通过，CI selector fixture 与 Wine 简单 FS 检查仍见前述结果。Rust 依赖审计脚本当前仍列出 326 行生产/构建/文档引用；Android/Desktop 仍启动 Rust，`asAppBackend()` 仍拒绝 ACP/device host 且 clear-all 抛错，`core/rust` 未删除。生产切换、真实平台 worker 证明、功能对等、Windows/真实设备验证及最终单独删除 commit 仍未完成。

本轮首次定向 shared 测试 14/14 通过，清理两处编译警告后 12 项相关测试重跑通过；`:runtime:testDebugUnitTest`、Runtime/Android debug 及 instrumentation APK 构建通过。验证日志：`/tmp/messenger-resume-shared-tests.log`、`/tmp/messenger-resume-runtime-android-build.log`、`/tmp/messenger-resume-candidate-device.log`、`/tmp/messenger-resume-runtime-device.log`、`/tmp/messenger-resume-hardlink-device.log`、`/tmp/messenger-resume-final-shared-tests.log` 和 `/tmp/messenger-resume-final-runtime-tests.log`。临时日志不是长期证据；以仓库中的测试和文档为准。

原有用户工作区：`.idea/*` 修改、未跟踪的 `google-services.json` 和 `jpush-android-6.3.1-release.zip`、`server` gitlink 的修改。不要覆盖、删除或纳入无关提交。`server` 子模块当前 `fd15f7b`，其中本会话有本地提交 `c6ec1c8` 和 `fd15f7b`；父仓库 gitlink 未提交。renderer 子模块自己有 `cb42531`。没有请求 push。

提交时不要混入 `.idea`、凭据或 server gitlink；保持无法证明的原型未连接及 fail-closed。

## 已完成的可验证成果

- Kotlin renderer 使用 `llm-typewriter` 子模块和 Compose LaTeX；Android 调试变体包含两个 ABI。`shared` Android device-test runtime 明确选择 debug `:core-bindings`，避免设备测试另外选择 release 变体；Cargo 对 debug native 仍使用优化 release profile。x86_64 模拟器的真实 UniFFI `CoreHandle.open` 验证过 native 加载；这不是 arm64 设备验证。
- CI PR 路径筛选及 fixture 测试已提交 `c5d1461`，main push/release 仍构建所有目标；没有声称 GitHub Actions 已实际运行。
- 旧 Room 恢复的隔离 UI 支持比较、显式选择及保留现状审查记录，记录与事务绑定，保留源数据；Android API 37 x86_64 模拟器上协调器设备测试 6/6 通过，worker 证明使用测试桩。历史 Rust 导入会遗漏 v21/v22 项目及会话关联，v22 provider `apiFormat` 也可能丢失。审查仅覆盖部分字段，不能视作全部数据一致或生产启动凭据。详见 `shared/docs/LEGACY_ROOM_RECOVERY.md`。
- Runtime ACP 协议 2 使用 callback Binder 绑定输入/停止，旧 companion 需升级；真实跨 UID Android Binder 测试通过。同步 Binder 可能阻塞；直接子进程停止不能证明已脱离的后代停止。
- Runtime 工作区候选传输原型（`3c563b9`）通过 5 项 Android 设备测试，包括真实硬链接拒绝、符号链接、父目录替换和深度限制；硬链接夹具仅在隔离 emulator 由 adbd root 创建后交给普通 Runtime UID，已清理。它不是完整迁移：拒绝链接/特殊文件，不保留所有元数据，不认证来源或提供跨文件一致快照。
- 最近已提交的隔离功能包括可恢复的定时任务、ACP steering 未知结果处理、BYOK 模型接管原型、远程文字 prompt、MCP 未知结果处理和受限 Cloud raw HTTP gate；具体能力和关闭状态见 `shared/AGENTS.md`。BYOK 默认模型不可用时不静默切换计费来源；Cloud takeover 尚未启用。
- 验证证据是分阶段的，不应加总成一次完整套件：曾有 738 Desktop 测试通过（1 skipped）、9 Runtime 和 35 Wear 单元测试通过；后续多轮定向测试分别通过 88、102、47 等项和 Android 编译；最近 ACP protocol 相关联合单元测试 84 项通过。Android 模拟器验证仅 API 37 x86_64，无 Wear OS 系统镜像，未做真实手表、Windows/macOS 设备验证。不要把旧结果套到未提交代码上。
- debug APK 曾测得 x86_64 `libmessenger_ffi.so` 18,180,600 bytes、`libsqliteJni.so` 1,189,688 bytes（存储大小）；没有完整发布体积或 clean-build 前后对照。`shared/docs/ANDROID_SYSTEM_SQLITE.md` 解释 bundled SQLite 本身仍包含 native 库，系统 SQLite 替换尚未证明安全。

## 关键未完成事项与建议顺序

1. **继续隔离原型的安全边界。** 三组交接改动已恢复检查点；workspace ledger 的 stop barrier 只是接口，服务端不认证声称的 sender，workspace 文件候选不提供可信来源、完整元数据或全局一致快照。
2. **明确并实现 Android 生产生命周期。** `androidApp/docs/CORE_CUTOVER_OWNERSHIP.md` 记录了实际障碍：Rust `ServerHandle.stop()` 现在 join axum、remote-control、scheduler 循环以及 `ApiState.tasks` 登记的本地请求任务，但仍未证明 image job、agent relay、ACP/Runtime 子进程、远程/付费副作用全部停止；Runtime 独立 UID 的旧后代可能脱离直接子进程；旧 Runtime 不服从新协议。SQLite 租约、Binder death、包升级、force-stop 一次观察或用户确认都不能证明整个所有权期间无旧写入。探索可验证的新 UID/受限工作进程和旧工作区隔离迁移；必须保留旧数据并处理安装工具、外部工作区及无法确认的远程副作用。不能用测试桩冒充平台证明。
3. **完成旧数据和 clear-all。** 审查记录绑定来源，只说明用户对所展示字段作过决定，不代表所有记录已经一致。给 v20/v21/v22、WAL、冲突、删除、过程终止、备份恢复和多设备提供可靠路径。旧 Core 源与备份的可验证退休、clear-all、资源清理和凭据保护仍缺；不得自动复活已删除记录。
4. **完成 Kotlin 功能对等并接入实际 AppBackend。** ACP 仍缺完整配置写入 fence、可信 worker 退出、Cloud takeover 会话 gate、OpenCode 版本探测、Core 工具/委派等；MCP 未知结果跨进程恢复未完成；远控附件、ACP 控制、工作区 relay 和受保护副作用未完成；定时任务工作区/工具范围不完整。复查 Learning、图片、生成应用、Wear、通知、Cloud、错误可见性和任务后台生命周期。`asAppBackend()` 限制不能简单删除来假装对等。
5. **Desktop 与存储。** Windows 存储的 ACL、链接/路径和持久化及旧 Core/资源独占问题未解决；参见 `shared/docs/WINDOWS_ROOM_STORAGE.md`。macOS/Linux/Windows 都需真实设备或 CI 验证。Android 系统 SQLite 的事务、游标和 WAL 语义尚待真实设备验证，不能仅为减小包而替换 driver。
6. **切换及删除。** 先建立真实 owner 和恢复机制，在保留功能及用户数据下接入 Android/Desktop；运行基础测试及受影响设备测试；运行 `bash .github/scripts/audit-rust-core-dependencies.sh --strict` 扫描源码、Gradle、CI、AIDL、ProGuard、文档、版本脚本及 native 产物的 Rust Core 依赖。审计脚本现在输出 `[summary]` 并可用 `--summary FILE` 写计数；`test-audit-rust-core-dependencies.sh` 在 CI selector job 中验证阻断树与 clean tree。确认 `blocking_references=0`、无 Rust workspace/bindings、无 native 产物并验证构建后，按用户要求单独提交删除 Rust Core；更新相关 `AGENTS.md`。release/R8 按仓库规则仅在确有必要时运行。

## 构建与验证

所有 Gradle 命令使用 `./gradlew-cli`，不要用裸 `./gradlew` 或加 `--no-daemon`。本机成功环境：

```bash
export JAVA_HOME=/opt/android-studio/jbr
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew-cli :shared:desktopTest --tests '*RoomWorkspaceExecutionFenceTest*' --tests '*RoomWorkspaceInvocationLedgerTest*' --tests '*RoomWorkspaceRelayAuthorityTest*' --tests '*KotlinRoomAcpSliceTest*' --tests '*KotlinControlSettingsBridgeTest*' --tests '*RoomDeviceControlGrantStoreTest*' :androidApp:compileDebugKotlin -Pmessenger.debugAbis=x86_64 -Pmessenger.abis=x86_64 -Pmessenger.buildRuntimeNative=false
./gradlew-cli :runtime:testDebugUnitTest :runtime:assembleDebug :runtime:assembleDebugAndroidTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest -Pmessenger.debugAbis=x86_64 -Pmessenger.abis=x86_64 -Pmessenger.buildRuntimeNative=false
```

本轮已补跑候选工作区的跨 UID instrumentation 及 Runtime 工作区设备测试；不要把分阶段结果套到后续改动或视作全量回归。设备测试仅使用临时 `emulator-5562`，明确 `adb -s emulator-5562`，结束后 `adb -s emulator-5562 emu kill`；不要操作物理设备。AVD 位于 `/tmp/messenger-avd-validation/avd`。运行状态和失败详情可先查看 `/tmp/messenger-resume-*.log`，但临时文件不是长期证据。Gradle 可能因 Kotlin 增量编译异常自动回退全量编译，需区分警告与失败。

自动命令审查此前拒绝了清理临时 checkout `/tmp/messenger-mcp-staged-AYYoEsYN` 的 `rm -rf` 命令形式；该目录被保留。不要绕过审查或将其误认为仓库内容。
