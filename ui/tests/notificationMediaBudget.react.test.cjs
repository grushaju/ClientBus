const { test } = require('node:test')
const assert = require('node:assert/strict')
const React = require('react')
const { act, create } = require('react-test-renderer')
const load = require('./loadInboxModule.cjs')

async function flush() {
    // Response.json()/blob(), React effects and queued admissions all use promises.
    for (let index = 0; index < 80; index++) await Promise.resolve()
}

const image = index => ({
    id: `media-${index}`, conversationId: 'conversation', type: 'IMAGE',
    direction: 'INBOUND', senderType: 'CLIENT', content: null,
    createdAt: '2026-10-22T10:00:00Z', sentAt: '2026-10-22T10:00:00Z',
    processingStatus: 'PROCESSED', deliveryStatus: 'SENT',
})
const attachment = messageId => ({
    id: `file-${messageId}`, messageId, type: 'IMAGE', fileName: `${messageId}.png`,
    contentType: 'image/png', size: 3, storageKey: `test/${messageId}`,
    createdAt: '2026-10-22T10:00:00Z',
})

function media(t, { holdMetadata = true, paused = false } = {}) {
    const budgets = load('../../api/events/requestBudget.ts')
    const scopeModule = load('../../api/events/uiScope.ts')
    const { default: MessageBubble } = load('MessageBubble.tsx', {
        './ImageViewer': () => null,
        './requestBudget': budgets,
        './events/requestBudget': budgets,
        './uiScope': scopeModule,
        '../../api/events/uiScope': scopeModule,
        '../auth/authStorage': { getAccessToken: () => 'test-credential' },
    })
    const records = [], owners = [], originalFetch = global.fetch
    let renderer, currentScope

    function owner(isPaused) {
        const budget = new budgets.RequestBudget(2, 32, 0)
        if (isPaused) budget.pause()
        const scope = { owner: {}, workspaceId: 'workspace', accessVersion: 0, active: true,
            managedPolling: true, requestBudget: budget,
            isCurrent: () => scope.active && currentScope === scope }
        owners.push(scope)
        return scope
    }
    currentScope = owner(paused)

    global.fetch = (input, init = {}) => new Promise((resolve, reject) => {
        const url = String(input), metadata = /\/attachments$/.test(url)
        const record = { url, metadata, signal: init.signal, settled: false }
        const remove = () => record.signal?.removeEventListener('abort', abort)
        const abort = () => {
            if (record.settled) return
            record.settled = true; remove()
            reject(new DOMException('Request cancelled', 'AbortError'))
        }
        record.respond = body => {
            if (record.settled) return
            record.settled = true; remove()
            resolve(new Response(body, { headers: { 'Content-Type': metadata ? 'application/json' : 'image/png' } }))
        }
        records.push(record)
        record.signal?.addEventListener('abort', abort, { once: true })
        if (record.signal?.aborted) { abort(); return }
        if (metadata && !holdMetadata) record.respond(JSON.stringify([attachment(url.split('/')[3])]))
    })

    const tree = count => React.createElement(scopeModule.UiScopeContext.Provider, { value: currentScope },
        Array.from({ length: count }, (_, index) => React.createElement(MessageBubble, { key: index, message: image(index) })))
    t.after(async () => {
        try {
            await act(async () => {
                renderer?.unmount(); renderer = undefined
                for (const scope of owners) { scope.active = false; scope.requestBudget.close() }
                // Release unbound requests when a regression fails before scope cancellation can reach them.
                for (const record of records) record.respond(record.metadata ? '[]' : new Uint8Array([1, 2, 3]))
                await flush()
            })
        } finally { global.fetch = originalFetch }
    })
    return {
        records,
        get scope() { return currentScope },
        get metadata() { return records.filter(record => record.metadata) },
        get blobs() { return records.filter(record => !record.metadata) },
        get images() { return renderer?.root.findAllByType('img') ?? [] },
        mount: count => act(async () => { renderer = create(tree(count)); await flush() }),
        unmount: () => act(async () => { renderer.unmount(); renderer = undefined; await flush() }),
        updateScope: count => act(async () => {
            currentScope.active = false; currentScope = owner(true)
            renderer.update(tree(count)); await flush()
        }),
    }
}

test('automatic attachment metadata respects the GET limit and unmount cancels active and queued reads', async t => {
    const ui = media(t)
    await ui.mount(6)
    assert.equal(ui.metadata.length, 2, 'Only two metadata GETs may start while their responses are held')
    assert.equal(ui.scope.requestBudget.snapshot().queued, 4)
    assert.ok(ui.metadata.every(record => record.signal), 'Metadata GETs need cancellable scope signals')
    const active = [...ui.metadata]
    await ui.unmount()
    assert.ok(active.every(record => record.signal.aborted), 'Unmount must abort active metadata reads')
    assert.equal(ui.scope.requestBudget.snapshot().active, 0)
    assert.equal(ui.scope.requestBudget.snapshot().queued, 0)
    assert.equal(ui.metadata.length, 2, 'Unmounted queued metadata reads must never start')
})

test('automatic attachment blobs share the GET budget and paused admission prevents the next download', async t => {
    const created = [], revoked = []
    t.mock.method(URL, 'createObjectURL', () => { const url = `blob:test-media-${created.length}`; created.push(url); return url })
    t.mock.method(URL, 'revokeObjectURL', url => revoked.push(url))
    const ui = media(t, { holdMetadata: false })
    await ui.mount(6)
    assert.equal(ui.metadata.length, 6)
    assert.equal(ui.blobs.length, 2, 'Only two blob GETs may start after metadata has loaded')
    assert.equal(ui.scope.requestBudget.snapshot().queued, 4)
    assert.ok(ui.blobs.every(record => record.signal), 'Blob GETs need cancellable scope signals')
    ui.scope.requestBudget.pause()
    await act(async () => { ui.blobs[0].respond(new Uint8Array([1, 2, 3])); await flush() })
    assert.equal(ui.blobs.length, 2, 'Completing a download while paused must not admit another GET')
    assert.equal(ui.images.length, 1)
    assert.equal(ui.images[0].props.src, created[0])
    await act(async () => { ui.scope.requestBudget.resume(); await flush() })
    assert.equal(ui.blobs.length, 3, 'Resuming admits the next queued download')
    const active = ui.blobs.filter(record => !record.settled)
    await ui.unmount()
    assert.ok(active.every(record => record.signal.aborted), 'Unmount must cancel blob downloads')
    assert.deepEqual(revoked, created, 'Unmount must release every displayed attachment object URL')
    assert.equal(ui.scope.requestBudget.snapshot().active, 0)
    assert.equal(ui.scope.requestBudget.snapshot().queued, 0)
})

test('a paused scope starts no media GETs and switching scope cancels the old media work', async t => {
    const ui = media(t, { paused: true })
    await ui.mount(4)
    assert.equal(ui.records.length, 0, 'Hidden/offline admission must also pause media metadata')
    await act(async () => { ui.scope.requestBudget.resume(); await flush() })
    assert.equal(ui.metadata.length, 2)
    const oldScope = ui.scope, active = [...ui.metadata]
    await ui.updateScope(4)
    assert.ok(active.every(record => record.signal.aborted), 'A new identity/workspace must cancel old media requests')
    assert.equal(oldScope.requestBudget.snapshot().active, 0)
    assert.equal(oldScope.requestBudget.snapshot().queued, 0)
    assert.equal(ui.records.length, 2, 'The new paused scope must not start replacement media GETs')
    await ui.unmount()
    assert.equal(ui.scope.requestBudget.snapshot().queued, 0)
})
