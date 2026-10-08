// Two adjustments the generated webpack config needs for the wasm core:
//
// 1. `resolve.modules` is emitted as a relative-only list (["node_modules"]),
//    which disables webpack's ancestor directory walk. The npm workspace root
//    lives above the generated package dir, so the absolute path must be added
//    or every dependency (e.g. @js-joda/core) fails to resolve.
// 2. The Kotlin bridge imports the wasm-bindgen glue through the bare
//    specifier `messenger-wasm-bindings`. A relative specifier would resolve
//    against the compiled Kotlin module's own directory, where the glue does
//    not live, so it is aliased to its absolute path. `__dirname` is
//    <project>/build/wasm/packages/Messenger-webApp, i.e. four levels down.
const path = require('path');

const projectRoot = path.resolve(__dirname, '..', '..', '..', '..');

config.resolve = config.resolve || {};
config.resolve.modules = Array.from(
  new Set([...(config.resolve.modules || ['node_modules']), path.join(projectRoot, 'build', 'wasm', 'node_modules')])
);
config.resolve.alias = Object.assign({}, config.resolve.alias, {
  'messenger-wasm-bindings': path.join(projectRoot, 'webApp', 'build', 'wasm', 'messenger_wasm.js'),
});
