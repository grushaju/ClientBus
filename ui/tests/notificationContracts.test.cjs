const assert = require('node:assert/strict')
const { test } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const ts = require('typescript')

const api = JSON.parse(fs.readFileSync(path.resolve(__dirname,
    '../../server/src/main/resources/notification-contract.openapi.json'), 'utf8'))
const names = ['notification', 'uiEvent', 'historySyncOperation', 'pushInstallation']
const files = names.map(name => path.resolve(__dirname, '../src/api/types', name + '.ts'))
const program = ts.createProgram(files, {
    noEmit: true, strict: true, skipLibCheck: true,
    target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.ESNext,
})
const checker = program.getTypeChecker()
const declarations = new Map()
for (const file of files) {
    for (const node of program.getSourceFile(file).statements) {
        if (ts.isInterfaceDeclaration(node) || ts.isTypeAliasDeclaration(node)) {
            declarations.set(node.name.text, node)
        }
    }
}
const literals = node => {
    const type = checker.getTypeFromTypeNode(node.type)
    return (type.isUnion() ? type.types : [type]).map(item => {
        assert.ok(item.flags & ts.TypeFlags.StringLiteral)
        return item.value
    }).sort()
}

test('notification TypeScript contracts compile independently', () => {
    const errors = ts.getPreEmitDiagnostics(program).filter(d => d.category === ts.DiagnosticCategory.Error)
    assert.equal(errors.length, 0, errors.map(d => ts.flattenDiagnosticMessageText(d.messageText, '\n')).join('\n'))
})

test('notification public interfaces and nullable fields match OpenAPI', () => {
    let checked = 0
    for (const [name, node] of declarations) {
        if (!ts.isInterfaceDeclaration(node)) continue
        const schema = api.components.schemas[name]
        assert.ok(schema, name)
        assert.deepEqual(node.members.map(m => m.name.text).sort(), Object.keys(schema.properties).sort(), name)
        for (const member of node.members) {
            const property = schema.properties[member.name.text]
            const type = checker.getTypeFromTypeNode(member.type)
            const nullable = type.isUnion() && type.types.some(t => t.flags & ts.TypeFlags.Null)
            const schemaNullable = Boolean(property.anyOf?.some(s => s.type === 'null'))
            assert.equal(Boolean(nullable), schemaNullable, name + '.' + member.name.text)
            assert.equal(Boolean(member.questionToken), false, name + '.' + member.name.text)
        }
        checked++
    }
    assert.equal(checked, 15)
})

test('notification enum unions and known SSE events match OpenAPI', () => {
    let checked = 0
    for (const [name, node] of declarations) {
        if (!ts.isTypeAliasDeclaration(node) || !api.components.schemas[name]?.enum) continue
        assert.deepEqual(literals(node), [...api.components.schemas[name].enum].sort(), name)
        checked++
    }
    assert.equal(checked, 7)
    assert.deepEqual(literals(declarations.get('KnownUiEventType')),
        [...api.components.schemas.UiEventEnvelope.properties.type['x-known-values']].sort())
    assert.deepEqual(literals(declarations.get('UiResourceKind')),
        [...api.components.schemas.UiResourceRef.properties.kind.enum].sort())
})
