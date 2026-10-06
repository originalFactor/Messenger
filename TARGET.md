# 跨平台 AI Agent App 技术架构与实施计划

## 1. 项目目标

构建一个跨平台 AI Agent 应用，目标平台：

* Android Phone
* Android Tablet
* Wear OS
* iOS
* macOS
* Windows
* Linux
* Web
* TUI

核心要求：

1. Agent 能力在各平台保持一致。
2. 支持 LLM Streaming。
3. 支持 Markdown。
4. 支持 LaTeX / 数学公式。
5. 支持代码块、Tool Call、MCP 等 Agent 能力。
6. 长对话和高速 token streaming 下保持流畅。
7. Wear OS 优先考虑 CPU、内存、功耗。
8. 不强制所有平台使用同一个 UI Framework。
9. 重型逻辑与 UI 解耦，使平台 UI 可以独立演进。

---

# 2. 总体架构

采用：

> **Rust Agent Core + KMP/平台桥接 + Platform-native Renderer**

而不是以 Compose Multiplatform 作为整个项目的基础。

```text
                         ┌─────────────────────────┐
                         │       Rust Core         │
                         │                         │
                         │ Agent Runtime            │
                         │ LLM Providers            │
                         │ Streaming                │
                         │ Tool / MCP               │
                         │ Session                  │
                         │ Markdown Parser          │
                         │ LaTeX Parser             │
                         │ Document Model            │
                         │ Incremental Diff          │
                         │ Cache                    │
                         └────────────┬────────────┘
                                      │
                              Platform API / FFI
                                      │
          ┌───────────────┬───────────┼──────────────┬──────────────┐
          │               │           │              │              │
          ▼               ▼           ▼              ▼              ▼
       Android           iOS       Desktop          Web            TUI
          │               │           │              │              │
      Native View      SwiftUI/    Native/       TS / Web      Rust TUI
      / Canvas         UIKit       Compose MP      Renderer      Renderer
          │
       ┌──┴───┐
       ▼      ▼
    Phone   Wear OS
```

核心原则：

> **共享 Agent，不共享所有 UI。**

---

# 3. Rust Core

Rust 是整个应用的核心运行时。

## 3.1 主要职责

Rust Core 负责：

* Agent Runtime
* LLM API
* Streaming
* Conversation
* Tool Calling
* MCP
* Markdown Parsing
* LaTeX Parsing
* Document Model
* Incremental Document Update
* Cache
* 数据序列化
* 高性能文本处理
* 必要的 CPU 密集型任务

核心 API 应尽量平台无关。

---

## 3.2 Agent Runtime

Agent 应设计成事件驱动模型，而不是让 UI 直接控制内部状态。

例如：

```rust
enum AgentEvent {
    TextDelta(String),
    ToolStarted(ToolCall),
    ToolOutput(ToolOutput),
    ToolFinished(ToolResult),
    Thinking,
    Finished,
    Error(AgentError),
}
```

平台 UI 只消费事件：

```text
User Input
    │
    ▼
Agent.dispatch(...)
    │
    ▼
Rust Agent Runtime
    │
    ├── LLM
    ├── Tools
    ├── MCP
    └── ...
    │
    ▼
AgentEvent Stream
    │
    ├── Android
    ├── iOS
    ├── Desktop
    ├── Web
    └── TUI
```

这样不同 UI 可以共享完全相同的 Agent 行为。

---

# 4. Document Engine

这是整个项目中非常重要的一层。

不要让 UI 直接处理原始 Markdown。

采用：

```text
LLM Tokens
     │
     ▼
Incremental Parser
     │
     ▼
Document Model
     │
     ▼
Platform Renderer
```

---

## 4.1 Document Model

例如：

```rust
enum Block {
    Paragraph(InlineContent),
    Heading(Heading),
    CodeBlock(CodeBlock),
    Math(MathBlock),
    List(ListBlock),
    Quote(QuoteBlock),
    ToolCall(ToolCallBlock),
}
```

每个 Block 应拥有稳定 ID。

```text
Block #1  immutable
Block #2  immutable
Block #3  streaming
Block #4  not created
```

这样更新时只修改当前活跃 Block。

---

# 5. Incremental Markdown

禁止每个 token 都重新解析完整消息：

```text
token
 ↓
parse entire document
 ↓
re-render entire message
```

采用：

```text
token
 ↓
incremental parser
 ↓
identify affected block
 ↓
update only affected block
```

例如：

```text
Block 1: Heading       immutable
Block 2: Paragraph     immutable
Block 3: Math          immutable
Block 4: Paragraph     streaming
```

只有 Block 4 持续变化。

Block 完成后转为稳定状态，并尽可能缓存其布局结果。

---

# 6. Streaming UI

LLM token 不应该直接映射成 UI 更新。

错误方式：

```text
Token
 ↓
State mutation
 ↓
Recomposition / Layout
```

推荐：

```text
LLM tokens
     ↓
Rust batching
     ↓
20~50 ms update window
     ↓
Document diff
     ↓
UI update
```

具体刷新间隔根据 benchmark 调整。

目标不是让 UI 每收到一个 token 都刷新，而是让用户感觉输出持续实时。

---

# 7. LaTeX

LaTeX 是性能重点。

Streaming 状态下：

```text
$$
\int_0^
```

不应该每个 token 都进行完整 typesetting。

采用：

```text
Streaming Math
      │
      ├── cheap temporary rendering
      │
      ▼
Closing delimiter received
      │
      ▼
Final Math AST
      │
      ▼
Layout
      │
      ▼
Cache
```

最终公式：

```text
LaTeX source
     ↓
hash
     ↓
layout cache
```

已经完成的公式不得因为后续 token 而重复计算。

---

# 8. Android

Android 不以 Compose 作为聊天界面的默认 renderer。

优先采用传统 Android View 系统。

推荐结构：

```text
RecyclerView
    │
    └── MessageView
          │
          └── DocumentView
                ├── Text
                ├── Code
                ├── Math
                └── Tool
```

## 8.1 为什么

聊天界面存在大量：

* 高频文本更新
* 长文本
* Markdown
* LaTeX
* Tool Output
* Streaming
* 局部刷新

传统 View 可以提供更直接的：

* `invalidate()`
* `requestLayout()`
* `Canvas`
* `StaticLayout`
* `TextView`
* `Drawable`

控制。

不需要为了一次 token 更新触发更高层级的 UI 状态传播。

---

# 9. Android 文本渲染

Markdown 可以转换为结构化 Span / Layout：

```text
Markdown AST
     ↓
Android representation
     ↓
TextView / StaticLayout
```

代码块使用独立 renderer。

数学公式使用独立 Math renderer。

不要把整个 AI 回复塞进一个巨大的 TextView，也不要为每一个 Markdown token 创建 View。

---

# 10. Android DocumentView

推荐最终采用：

```text
RecyclerView
    │
    └── MessageView
          │
          └── DocumentView
                │
                ├── paragraph layout
                ├── code layout
                ├── math layout
                └── tool layout
```

即：

> View 负责生命周期、滚动和可见区域；Document Renderer 负责内容绘制。

这样可以避免过大的 View hierarchy。

---

# 11. Wear OS

Wear OS 是特殊目标，不应简单视为缩小版 Android。

目标：

* 低内存
* 低 CPU
* 低功耗
* 低 UI overhead
* 尽量减少对象创建
* 尽量减少频繁布局

推荐：

```text
Rust Core
    │
    ▼
Compact Document/Event
    │
    ▼
Android Native View / Canvas
```

Wear 不需要承担完整桌面级 Markdown renderer。

必要时只显示：

* 当前回答
* Streaming 状态
* 简化 Markdown
* 重要 Tool 状态
* 操作按钮

完整内容可以由手机/桌面端承担。

---

# 12. Native 与 Rust 的边界

不要把所有代码都塞进 Rust。

Rust 适合：

* Agent runtime
* Parsing
* Document processing
* CPU-intensive processing
* Crypto
* Compression
* 大规模文本处理
* 本地推理相关组件

Kotlin / Swift / 平台代码适合：

* UI
* 生命周期
* 系统 API
* Notification
* Permission
* Platform-specific services

尤其避免：

```text
每一个 token
C++/Rust
 ↓
JNI
 ↓
Kotlin
 ↓
Rust
```

跨 FFI 边界应该进行批量传输。

---

# 13. iOS

iOS 不需要强行复制 Android View 架构。

可以采用：

```text
Rust Core
    ↓
FFI
    ↓
Swift
    ↓
SwiftUI / UIKit
```

UI 可以根据实际 benchmark 选择 SwiftUI 或 UIKit。

核心 Document Model 保持一致。

---

# 14. Desktop

Windows / Linux / macOS 可以有更大的自由度。

初期可以考虑：

```text
Rust Core
    ↓
Desktop API
    ↓
Compose Multiplatform
```

如果后续性能不足，再替换为：

```text
Rust Core
    ↓
Native / Skia / wgpu renderer
```

因此 Compose 在 Desktop 上可以作为生产力工具，而不是架构约束。

---

# 15. Web

Web 不需要强制使用 Kotlin UI。

优先考虑：

```text
Rust Core
    ↓
WASM / protocol
    ↓
TypeScript
    ↓
Web UI
```

如果 Compose Multiplatform Web 的实际性能和开发效率满足要求，可以使用 Compose MP。

最终以 benchmark 而不是技术统一性决定。

---

# 16. TUI

TUI 使用独立 renderer。

```text
Rust Core
    ↓
Document Model
    ↓
Terminal Renderer
    ↓
ANSI / VT
```

TUI 不需要模拟 GUI。

可以直接将 Document Model 映射成：

```text
Terminal Cell
 ├── character
 ├── foreground
 ├── background
 └── attributes
```

这也使 Rust Core 与 TUI 天然契合。

---

# 17. UI 层统一方式

不共享 UI Framework，而共享：

```text
AgentEvent
Document Model
Command
Session
State
Capability
```

例如：

```text
             AgentEvent
                 │
      ┌──────────┼──────────┐
      ▼          ▼          ▼
   Android      iOS        TUI
      │          │          │
      ▼          ▼          ▼
   Native      Native     Terminal
```

这样平台可以拥有完全不同的交互模型。

---

# 18. 性能策略

性能优化优先级：

### P0

* 增量 Markdown Parser
* 增量 Document Model
* Streaming batching
* Stable Block ID
* Lazy rendering
* Virtualized conversation
* LaTeX cache

### P1

* Layout cache
* Image cache
* Code syntax highlighting cache
* Object reuse
* FFI batching

### P2

* GPU renderer
* Native text renderer
* wgpu / Skia
* 更复杂的跨平台 rendering abstraction

不要一开始就实现 P2。

先通过 profiling 找瓶颈。

---

# 19. Benchmark

项目应该建立独立 benchmark，而不是凭感觉判断 Compose / View / Native 哪个快。

测试至少包括：

1. 100 tokens/s streaming
2. 200 tokens/s streaming
3. 10,000 token conversation
4. 大量 Markdown
5. 大量代码块
6. 大量 LaTeX
7. 超长公式
8. 连续 Tool Output
9. 快速滚动
10. 后台 / 前台切换
11. Wear OS 长时间运行

记录：

```text
Frame time
CPU
Memory
Allocations
GC
GPU
Battery
Time to first token
Time to first render
```

尤其需要比较：

```text
Compose
Android View
Custom Canvas
Native renderer
```

最终选择由数据决定。

---

# 20. 推荐 Repository Structure

```text
project/
├── core/
│   └── rust/
│       ├── agent/
│       ├── llm/
│       ├── mcp/
│       ├── markdown/
│       ├── latex/
│       ├── document/
│       ├── session/
│       └── ffi/
│
├── android/
│   ├── app/
│   ├── wear/
│   └── renderer/
│
├── ios/
│
├── desktop/
│   ├── windows/
│   ├── linux/
│   └── macos/
│
├── web/
│
├── tui/
│
└── shared/
    └── protocol/
```

如果最终 KMP 仍然需要，可以将其用于：

```text
shared/
├── models
├── protocol
├── configuration
└── platform abstractions
```

但不强制 KMP 承担 Agent Core。

---

# 21. 开发阶段

## Phase 1 — Rust Core

先实现：

* Agent
* LLM streaming
* Tool system
* Session
* Event model

暂时不考虑 UI。

---

## Phase 2 — Document Engine

实现：

* Incremental Markdown
* Code block
* LaTeX detection
* Document Model
* Incremental diff
* Block ID
* Cache

这是整个项目最值得提前验证的部分。

---

## Phase 3 — Android

优先：

```text
RecyclerView
+
Native View
+
Document Renderer
```

先实现：

* Streaming
* Markdown
* Code
* LaTeX
* Tool output

---

## Phase 4 — Wear OS

基于 Android Core，但建立独立 Wear UI。

重点 benchmark：

* RAM
* CPU
* battery
* streaming responsiveness

---

## Phase 5 — Desktop / iOS

分别实现平台 Renderer。

不要为了共享代码牺牲平台体验。

---

## Phase 6 — TUI

直接利用 Rust Core。

实现：

* Conversation
* Streaming
* Tool status
* MCP
* Keyboard navigation

---

## Phase 7 — Web

根据 Rust/WASM 与 Web UI 的实际需求决定：

* TypeScript
* Compose Multiplatform Web
* Hybrid

---

# 22. 最终技术决策

| 部分             | 技术                                   |
| -------------- | ------------------------------------ |
| Agent Core     | **Rust**                             |
| Streaming      | Rust                                 |
| Tool / MCP     | Rust                                 |
| Markdown       | Rust incremental parser              |
| LaTeX          | Rust parser/layout                   |
| Document Model | Rust                                 |
| Android Phone  | **Android View 优先**                  |
| Android Tablet | Android View 优先                      |
| Wear OS        | **Native View / Canvas**             |
| iOS            | SwiftUI / UIKit                      |
| Desktop        | Compose MP / Native，根据 benchmark     |
| Web            | TypeScript / WASM，或 Compose MP       |
| TUI            | **Rust native TUI**                  |
| 跨平台协议          | Rust-defined API / FFI               |
| 状态同步           | Event-driven                         |
| UI Streaming   | Batched updates                      |
| 性能策略           | Incremental + Cache + Virtualization |

---

# 23. 核心设计原则

最终项目遵循以下原则：

### ① Agent 与 UI 完全解耦

```text
Agent ≠ UI
```

### ② Token ≠ UI update

```text
tokens → batch → document diff → render
```

### ③ 不重新渲染稳定内容

```text
immutable blocks stay immutable
```

### ④ 不为了跨平台而牺牲平台性能

```text
Shared Core
≠
Shared UI
```

### ⑤ Wear OS 是独立性能目标

不能把手机 UI 简单缩小。

### ⑥ Compose 是工具，不是宗教

能满足性能就用；不能满足就换。

### ⑦ Benchmark 优先于主观判断

尤其是：

```text
Compose vs View vs Canvas vs Native
```

必须通过实际 workload 测量。

---

# 24. 最终目标架构

```text
                         ┌───────────────────────────┐
                         │        Rust Core          │
                         │                           │
                         │ Agent                     │
                         │ LLM                       │
                         │ MCP / Tools               │
                         │ Streaming                 │
                         │ Markdown                  │
                         │ LaTeX                     │
                         │ Document Model             │
                         │ Cache                     │
                         └─────────────┬─────────────┘
                                       │
                              Stable Event API
                                       │
             ┌───────────────┬─────────┼──────────┬──────────────┐
             │               │         │          │              │
             ▼               ▼         ▼          ▼              ▼
          Android          iOS      Desktop      Web            TUI
             │               │         │          │              │
       Native View        Native    Compose/    TS/WASM        Rust
             │                         Native
       ┌─────┴──────┐
       ▼            ▼
    Phone/Tablet   Wear
```

**核心思想：Rust 负责“思考和组织内容”，平台负责“以最适合自己的方式显示内容”。**

这样即使未来 Android 最终完全放弃 Compose，或者 Desktop 改成 GPU renderer，甚至 Web 改成完全独立的 TypeScript UI，Agent Core 和 Document Engine 都不需要重写。
