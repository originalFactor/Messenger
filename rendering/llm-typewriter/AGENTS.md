# llm-typewriter source patch

This internal Android/Desktop KMP module rebuilds the **catalog-pinned Maven Central sources**
of `cc.ptoe:llm-typewriter`; it is not a separate renderer or a published fork. Shared consumes
this project instead of the upstream binary, preventing duplicate classes.

- `patchTypewriterSources` extracts upstream JVM/common and Android sources plus the original
  Android AAR's Cascadia font resources. Generated sources belong under `build/`, never commit them.
- `patches/inline-math.before.kt` is the exact upstream section; `inline-math.after.kt` replaces it.
  A version upgrade MUST review this patch: the build fails if the before section no longer matches
  exactly once. Preserve upstream copyright/license headers.
- The patch measures and paints inline math at the same font size (1.2 × body size to compensate
  for KaTeX's smaller x-height) and reserves the complete measured height. Never clamp matrices,
  fractions or descenders to prose line height or clip them to it. Density/font-scale changes must
  invalidate placeholder sizes. Block math and streaming/parser behavior remain upstream-owned.
- Dependency versions belong in the root catalog. Keep upstream runtime dependencies (including all
  desktop RaTeX native platforms), Android font resources and the app's Skiko exclusions intact.
- Verify via `./gradlew-cli :shared:desktopTest --tests '*ChatBubbleContentTest'` plus
  `:llm-typewriter-patched:compileAndroidMain`; tests in shared exercise the actual chat renderer.
- Remove this module and restore the binary dependency when an upstream release provides this fix.
