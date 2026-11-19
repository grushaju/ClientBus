const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

/**
 * Этот тест читает исходники вместо вызова функций — по делу: дефект, из-за которого он
 * заведён, в Node невоспроизводим. У URLSearchParams.size поддержка появилась поздно
 * (Firefox 139), а в рантайме тестов это свойство есть, поэтому любой вызов api-функции
 * прошёл бы и с браком — молча потерянный параметр меняет область выборки, а не выдачу
 * страницы, и пожаловаться на него некому.
 */
function sources(dir) {
    return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
        const full = path.join(dir, entry.name)
        if (entry.isDirectory()) return sources(full)
        return /\.tsx?$/.test(entry.name) ? [{ file: full, text: fs.readFileSync(full, 'utf8') }] : []
    })
}

test('modules under src/api never build a query string from URLSearchParams.size', () => {
    const root = path.resolve(__dirname, '..')
    // Тернарник после .size, а не смежные операторы: `metadata.size ?? x` и `size?.x` —
    // обычные обращения к свойству размера, они правилу не помеха (проверено на пробе).
    const ternaryOnSize = /\.size\s*\?(?![?=.])/
    const offenders = sources(path.join(root, 'src', 'api'))
        .filter(({ text }) => ternaryOnSize.test(text))
        .map(({ file }) => path.relative(root, file).replace(/\\/g, '/'))
    assert.deepEqual(offenders, [],
        'сборка query обязана опираться на toString(): молча потерянный параметр расширяет область выборки')
})
