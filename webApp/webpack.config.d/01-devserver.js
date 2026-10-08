// Compose Multiplatform on Wasm keeps its renderer state in shared memory, so
// the page must be cross-origin isolated. The Kotlin Gradle webpack DSL has no
// `devServer.headers` hook, hence this customization file (auto-merged by the
// Kotlin/JS webpack integration). Production hosting must send the same two
// headers for /app/* — see server/next.config.ts.
config.devServer = config.devServer || {};
config.devServer.headers = Object.assign({}, config.devServer.headers, {
  'Cross-Origin-Opener-Policy': 'same-origin',
  'Cross-Origin-Embedder-Policy': 'require-corp',
});
