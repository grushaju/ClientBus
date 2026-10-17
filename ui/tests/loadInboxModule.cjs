const fs = require('node:fs')
const path = require('node:path')
const { createRequire } = require('node:module')
const ts = require('typescript')

// Load the actual TS/TSX graph with the project's compiler. Only explicitly
// supplied boundaries are mocked; React and its renderer use the same runtime.
module.exports = function loadInboxModule(relative, mocks = {}) {
    const cache = new Map()
    function load(filename) {
        if (cache.has(filename)) return cache.get(filename).exports
        const module = { exports: {} }
        cache.set(filename, module)
        const originalRequire = createRequire(filename)
        const requireDependency = name => {
            if (Object.hasOwn(mocks, name)) return mocks[name]
            if (name.startsWith('.')) {
                const dependency = path.resolve(path.dirname(filename), name)
                for (const extension of ['.ts', '.tsx']) {
                    if (fs.existsSync(dependency + extension)) return load(dependency + extension)
                }
            }
            return originalRequire(name)
        }
        const compiled = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
            compilerOptions: {
                module: ts.ModuleKind.CommonJS,
                target: ts.ScriptTarget.ES2020,
                jsx: ts.JsxEmit.ReactJSX,
            },
            fileName: filename,
        }).outputText
        new Function('require', 'module', 'exports', compiled)(requireDependency, module, module.exports)
        return module.exports
    }
    return load(path.resolve(__dirname, '../src/components/conversations', relative))
}
