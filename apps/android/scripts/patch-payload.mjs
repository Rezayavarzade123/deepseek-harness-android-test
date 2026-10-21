#!/usr/bin/env node
/**
 * Prepare the harness installation that ships inside the APK.
 *
 *   node scripts/patch-payload.mjs prune <payload>   remove packages the phone cannot run
 *   node scripts/patch-payload.mjs patch <payload>   apply the two Android patches
 *
 * The payload is a plain `npm install @deepseek-ai/dsh` tree. Pruning drops
 * optional plugins whose native or WASM payloads target other systems and the
 * platform-specific addon builds npm selected for the build host; the harness
 * reports missing optional plugins at boot and keeps running, which the boot
 * check in scripts/README.md relies on.
 */
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const [, , command, payload] = process.argv
if (command === undefined || payload === undefined) {
  console.error('usage: patch-payload.mjs <prune|patch> <payload-dir>')
  process.exit(2)
}

const modules = join(payload, 'node_modules')

/** Packages removed whole: other-platform natives, WASM engines, unused SDKs. */
const PRUNE_PACKAGES = [
  '@deepseek-ai/libreoffice-kit',
  '@deepseek-ai/libreoffice-kit-wasm',
  '@deepseek-ai/dsh-office-to-pdf',
  '@deepseek-ai/dsh-experimental-inspector',
  '@deepseek-ai/dsh-experimental-inspector-profile',
  '@deepseek-ai/dsh-client-ui-sidebar-documentpreview',
  'sherpa-onnx-node',
  'sherpa-onnx-linux-x64',
  'sherpa-onnx-win-x64',
  'sharp',
  '@img',
  '@opentelemetry',
  '@anthropic-ai',
  '@google',
  'openai',
  '@smithy',
  '@vscode',
  'node-pty',
  'koffi',
  '@koromix',
]

/** File and directory names that never matter at runtime. */
const PRUNE_FILE_SUFFIXES = ['.map', '.d.ts', '.d.mts', '.d.cts', '.tsbuildinfo']
const PRUNE_FILE_NAMES = /^(README|LICENSE|LICENCE|CHANGELOG|AUTHORS|CONTRIBUTING)(\..*)?$/i
const PRUNE_DIR_NAMES = new Set(['test', 'tests', '__tests__', 'coverage', '.github', '.vscode'])

function removePackage(name) {
  const target = join(modules, name)
  if (existsSync(target)) {
    rmSync(target, { recursive: true, force: true })
    return true
  }
  return false
}

/** Drop platform-specific addon packages npm installed for the build host. */
function removeForeignNativePackages() {
  const removed = []
  for (const entry of readdirSync(modules)) {
    if (/^node-addon-require-builtin-(linux|darwin|win32)-/.test(entry)) {
      rmSync(join(modules, entry), { recursive: true, force: true })
      removed.push(entry)
    }
  }
  return removed
}

function sweep(directory) {
  let files = 0
  let dirs = 0
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) {
      if (PRUNE_DIR_NAMES.has(entry.name)) {
        rmSync(path, { recursive: true, force: true })
        dirs++
      } else {
        const nested = sweep(path)
        files += nested.files
        dirs += nested.dirs
      }
      continue
    }
    if (PRUNE_FILE_SUFFIXES.some(suffix => entry.name.endsWith(suffix)) || PRUNE_FILE_NAMES.test(entry.name)) {
      rmSync(path, { force: true })
      files++
    }
  }
  return { files, dirs }
}

function prune() {
  const removed = PRUNE_PACKAGES.filter(removePackage)
  const foreign = removeForeignNativePackages()
  const swept = sweep(modules)
  console.log(`pruned ${removed.length + foreign.length} packages, ${swept.files} files, ${swept.dirs} directories`)
  for (const name of [...removed, ...foreign]) console.log(`  - ${name}`)
}

const REQUIRE_BUILTIN_SHIM = `'use strict'
// Android payload patch (scripts/patch-payload.mjs): the shipped addon is a
// Node-API binary with no bionic build, so route requireBuiltin() through Node's
// own internals, which \`node --expose-internals\` exposes to CommonJS. The app
// always launches Node with that flag.
function requireBuiltin(id) {
  if (typeof id !== 'string' || !id.startsWith('internal/')) {
    throw new Error(\`require-builtin: \${String(id)} is not an internal module id\`)
  }
  return require(id)
}
function isAllowedInternalId(id) {
  return typeof id === 'string' && id.startsWith('internal/')
}
function getBindingInfo() {
  return { backend: 'js', platform: process.platform, arch: process.arch }
}
module.exports = { requireBuiltin, isAllowedInternalId, getBindingInfo }
module.exports.default = module.exports
`

const REQUIRE_BUILTIN_PACKAGE = JSON.stringify({
  name: 'node-addon-require-builtin',
  version: '0.1.9-android-js.0',
  description: 'JS replacement for the native require-builtin addon over --expose-internals.',
  type: 'commonjs',
  main: 'lib/index.js',
  exports: { '.': { require: './lib/index.js', import: './lib/index.js', default: './lib/index.js' } },
  engines: { node: '>=22.19' },
  license: 'MIT',
}, null, 2) + '\n'

const FLOCK_ANDROID_BRANCH = `    const { platform, arch } = process;
    if (platform === 'android') {
        // Android payload patch (scripts/patch-payload.mjs): no bionic build of
        // this addon exists. The phone runs one harness process, so an
        // in-process advisory lock preserves the single-writer contract.
        return jsLockBinding();
    }`

const FLOCK_JS_BINDING = `let jsLocks;
/** In-process advisory lock used where the native addon is unavailable. */
function jsLockBinding() {
    if (jsLocks === undefined)
        jsLocks = new Set();
    return {
        tryLock(fd, resolve) {
            jsLocks.add(fd);
            resolve(0);
        },
    };
}
let binding;`

function patchFlock() {
  const path = join(modules, '@deepseek-ai/node-addon-system/lib/flock.js')
  if (!existsSync(path)) {
    // The package was pruned with the other native packages; nothing to patch.
    return 'flock.js absent (node-addon-system pruned)'
  }
  let source = readFileSync(path, 'utf8')
  if (source.includes('Android payload patch')) return 'flock.js already patched'
  const anchor = 'let binding;'
  const platformCheck = `    const { platform, arch } = process;
    if (platform !== 'linux' && platform !== 'darwin') {`
  if (!source.includes(anchor) || !source.includes(platformCheck)) {
    throw new Error('flock.js layout changed: update scripts/patch-payload.mjs')
  }
  source = source.replace(anchor, FLOCK_JS_BINDING)
  source = source.replace(platformCheck, FLOCK_ANDROID_BRANCH)
  writeFileSync(path, source)
  return 'flock.js patched for android'
}

function patchRequireBuiltin() {
  const dir = join(modules, 'node-addon-require-builtin')
  if (existsSync(dir)) rmSync(dir, { recursive: true, force: true })
  const lib = join(dir, 'lib')
  mkdirSync(lib, { recursive: true })
  writeFileSync(join(dir, 'package.json'), REQUIRE_BUILTIN_PACKAGE)
  writeFileSync(join(lib, 'index.js'), REQUIRE_BUILTIN_SHIM)
  return 'node-addon-require-builtin replaced with the JS shim'
}

function patch() {
  if (!existsSync(modules)) throw new Error(`no node_modules in ${payload}`)
  console.log(patchRequireBuiltin())
  console.log(patchFlock())
}

if (command === 'prune') prune()
else if (command === 'patch') patch()
else {
  console.error(`unknown command: ${command}`)
  process.exit(2)
}
