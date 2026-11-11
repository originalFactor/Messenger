# Embedded llm-typewriter

This internal Android/Desktop KMP module embeds the Apache-licensed sources and Android fonts
from https://github.com/ECSDevs/llm-typewriter, at the revision recorded in `NOTICE`. Shared
consumes this project; do not also add the Maven renderer binary. Keep upstream copyright headers
and the source attribution when updating it. Upstream JVM sources live in `desktopMain` here.

- The local inline-math layout measures and paints at the same font size (1.2 × body size to
  compensate for KaTeX's smaller x-height) and reserves the complete measured height. Never clamp
  matrices, fractions or descenders to prose line height or clip them. Density/font-scale changes
  must invalidate placeholder sizes. List markers align with the content column's first baseline.
- Preserve upstream parser prefix stability, streaming state behavior, replaceable image loading,
  Android Cascadia font resources and the common parser tests when updating sources.
- `MarkdownStyles` accepts localized thinking-card title and expanded/collapsed labels; include
  them in equality so remembered renderers react to language changes. Open thinking blocks stay
  expanded, then collapse on completion; the whole header is an accessible toggle.
- Dependency versions belong in the root catalog. Keep desktop RaTeX native platforms and the
  app's Skiko exclusions intact.
- Verify `./gradlew-cli :llm-typewriter:desktopTest :llm-typewriter:compileAndroidMain` and
  `./gradlew-cli :shared:desktopTest --tests '*ChatBubbleContentTest'`.
