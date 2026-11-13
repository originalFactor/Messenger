# Third-party source notices

## Animated corner shapes

`shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/ui/components/internal/AnimatedShape.kt`

Copyright 2024 The Android Open Source Project. Licensed under the Apache License,
Version 2.0; see the repository's `LICENSE` and the retained source-file header.

Imported from the Apache-2.0-marked file in BakaSU manager at commit
`548d1dda060ca198823d8808804d01f5209ef4a9`:
https://github.com/Baka-SU/BakaSU/blob/548d1dda060ca198823d8808804d01f5209ef4a9/manager/app/src/main/java/org/bakasu/bakasu/ui/component/settings/material3internal/AnimatedShape.kt

Messenger changes its package for Kotlin Multiplatform. The original copyright,
license header and animation implementation are retained. The other BakaSU manager
settings widgets are not copied; Messenger's layout, interaction and grouping
components are implemented locally.

## Interactive artifact system-webview helper

`desktopApp/webview` links Tao (Apache-2.0) and, on Windows/macOS, Wry
(Apache-2.0 OR MIT). The Linux adapter uses webkit2gtk-rs (MIT), gtk-rs
(MIT), and the system WebKitGTK/GTK libraries. WebKit uses BSD and LGPL
licenses; its licensing details are available at https://webkit.org/licensing-webkit/.
The helper does not bundle a modified WebKit or Chromium engine. Linux
installations provide WebKitGTK, macOS provides WKWebView, and Windows
requires the installed Microsoft WebView2 Runtime under its own terms.

Upstream sources and licenses:

- https://github.com/tauri-apps/tao (Apache-2.0)
- https://github.com/tauri-apps/wry (Apache-2.0 OR MIT)
- https://github.com/tauri-apps/webkit2gtk-rs (MIT)
- https://github.com/gtk-rs/gtk3-rs (MIT)
- https://webkit.org/licensing-webkit/
