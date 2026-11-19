const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

/**
 * Этот тест читает исходники вместо вызова функций — по делу: в рантайме тестов
 * URLSearchParams.size есть (в браузерах он, к слову, широко доступен с 2023 года, MDN
 * https://developer.mozilla.org/en-US/docs/Web/API/URLSearchParams/size), поэтому любой вызов
 * api-функции прошёл бы и с браком. Опасен сам способ: undefined вместо свойства делает
 * условие ложным, и параметр теряется молча — а потерянный параметр это расширенная область
 * выборки, а не ошибка на странице.
 */
function sources(dir) {
    return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
        const full = path.join(dir, entry.name)
        if (entry.isDirectory()) return sources(full)
        return /\.tsx?$/.test(entry.name) ? [{ file: full, text: fs.readFileSync(full, 'utf8') }] : []
    })
}

/** Переменные, в которых в этом файле лежит URLSearchParams: правило читает имена, а не форму. */
function paramNames(text) {
    const names = new Set()
    for (const match of text.matchAll(/(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*new URLSearchParams\b/g)) {
        names.add(match[1])
    }
    return names
}

/** Комментарии в счёт не идут: правило про код, а не про то, что про него написано. */
const withoutComments = (text) => text.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\/\/[^\n]*/g, ' ')

test('modules under src/api never read the size property of their URLSearchParams', () => {
    const root = path.resolve(__dirname, '..')
    const files = sources(path.join(root, 'src', 'api'))
    const offenders = []
    let watched = 0
    for (const { file, text } of files) {
        const names = paramNames(text)
        if (names.size === 0) continue
        watched++
        const code = withoutComments(text)
        for (const name of names) {
            if (new RegExp(`\\b${name.replace(/\$/g, '\\$')}\\.size\\b`).test(code)) {
                offenders.push(`${path.relative(root, file).replace(/\\/g, '/')} → ${name}.size`)
            }
        }
    }

    // Проверка обязана иметь что проверять: пустой список «нарушений» от пустого обхода
    // отличают эти два условия, иначе тест позеленеет в один день с переездом каталога.
    assert.ok(files.length >= 10, `правило обошло слишком мало модулей: ${files.length}`)
    assert.ok(watched >= 2, `правило не нашло файлов с URLSearchParams: ${watched}`)
    assert.deepEqual(offenders, [],
        'сборка query обязана опираться на toString(): молча потерянный параметр расширяет область выборки')
})
