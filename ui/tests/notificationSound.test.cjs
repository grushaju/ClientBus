const { test } = require('node:test')
const assert = require('node:assert/strict')
const load = require('./loadInboxModule.cjs')

function environment(t, locks) {
    const old = Object.fromEntries(['window', 'localStorage', 'navigator'].map(key => [key, Object.getOwnPropertyDescriptor(global, key)]))
    const values = new Map()
    Object.defineProperty(global, 'navigator', { configurable: true, value: { locks } })
    global.window = {}
    global.localStorage = { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value) }
    t.after(() => { for (const [key, descriptor] of Object.entries(old)) descriptor ? Object.defineProperty(global, key, descriptor) : delete global[key] })
    return { values, module: load('../notifications/notificationSound.ts') }
}

function audioClass({ fail = false, hold } = {}) {
    return class {
        static instances = []
        constructor() { this.state = 'suspended'; this.currentTime = 12; this.played = 0; this.stopped = 0; this.constructor.instances.push(this) }
        resume() { return (hold ?? (fail ? Promise.reject(new Error('blocked')) : Promise.resolve())).then(() => { this.state = 'running' }) }
        async close() { this.state = 'closed' }
        createOscillator() { return { frequency: { setValueAtTime() {}, exponentialRampToValueAtTime() {} }, connect() {}, disconnect() {},
            start: () => { this.played++ }, stop: () => { this.stopped++ } } }
        createGain() { return { gain: { setValueAtTime() {}, exponentialRampToValueAtTime() {} }, connect() {}, disconnect() {} } }
    }
}

test('audio creates/resumes only on activation and a rejected autoplay attempt never queues a later tone', async t => {
    const { module: { NotificationAudio } } = environment(t)
    const states = [], Audio = audioClass({ fail: true }); window.AudioContext = Audio
    const sound = new NotificationAudio(state => states.push(state))
    assert.equal(sound.play(), false); assert.equal(Audio.instances.length, 0)
    assert.equal(await sound.activate(), false)
    assert.equal(sound.play(), false); assert.equal(Audio.instances[0].played, 0)
    assert.deepEqual(states, ['blocked'])
    window.AudioContext = audioClass()
    sound.close(); sound.open()
    assert.equal(await sound.activate(), true)
    assert.equal(window.AudioContext.instances[0].played, 0, 'Unlocking does not replay the previously missed event')
    assert.equal(sound.play(), true)
    sound.close()
    assert.equal(sound.ready, false); assert.equal(sound.play(), false)
})

test('unsupported audio and late resume after logout are harmless', async t => {
    const { module: { NotificationAudio } } = environment(t)
    let state
    const unsupported = new NotificationAudio(value => { state = value })
    assert.equal(await unsupported.activate(), false); assert.equal(state, 'unavailable')
    let resolve
    const held = new Promise(done => { resolve = done })
    window.AudioContext = audioClass({ hold: held })
    const sound = new NotificationAudio()
    const pending = sound.activate(); sound.close(); resolve()
    assert.equal(await pending, false); assert.equal(sound.play(), false)
})

test('the notification clip is prepared once and played through the real buffer-source path after activation', async t => {
    const { module: { NotificationAudio } } = environment(t)
    let requests = 0, clips = 0
    const Base = audioClass()
    window.AudioContext = class extends Base {
        async decodeAudioData(bytes) { assert.equal(bytes.byteLength, 8); return { duration: .7 } }
        createBufferSource() { return { connect() {}, disconnect() {}, start() { clips++ }, stop() {} } }
    }
    window.fetch = async (url, init) => {
        assert.equal(url, '/notification.ogg'); assert.equal(init.cache, 'force-cache'); requests++
        return { ok: true, arrayBuffer: async () => new ArrayBuffer(8) }
    }
    const sound = new NotificationAudio()
    assert.equal(sound.play(), false); assert.equal(requests, 0)
    assert.equal(await sound.activate(), true); assert.equal(clips, 0)
    assert.equal(sound.play(), true); assert.equal(clips, 1)
    assert.equal(window.AudioContext.instances[0].played, 0, 'The built-in oscillator is only a fallback')
    assert.equal(await sound.activate(), true); assert.equal(requests, 1)
    sound.close()
})

test('a failed clip decode keeps a short fallback without playing or replaying during loading', async t => {
    const { module: { NotificationAudio } } = environment(t)
    let finish
    window.AudioContext = class extends audioClass() {
        async decodeAudioData() { throw new Error('unsupported codec') }
    }
    window.fetch = () => new Promise(resolve => { finish = resolve })
    const sound = new NotificationAudio()
    const activation = sound.activate()
    await Promise.resolve()
    assert.equal(sound.ready, false); assert.equal(sound.play(), false)
    finish({ ok: true, arrayBuffer: async () => new ArrayBuffer(8) })
    assert.equal(await activation, true)
    assert.equal(window.AudioContext.instances[0].played, 0)
    assert.equal(sound.play(), true); assert.equal(window.AudioContext.instances[0].played, 1)
    sound.close()
})

test('an old resume rejection after close/open cannot mark the new running context blocked', async t => {
    const { module: { NotificationAudio } } = environment(t)
    let reject
    const oldResume = new Promise((_resolve, fail) => { reject = fail })
    const states = [], Base = audioClass()
    window.AudioContext = class extends Base {
        constructor() { super(); this.first = this.constructor.instances.length === 1 }
        resume() { return this.first ? oldResume : super.resume() }
    }
    const sound = new NotificationAudio(value => states.push(value))
    const pending = sound.activate()
    sound.close(); sound.open()
    assert.equal(await sound.activate(), true)
    reject(new Error('old context'))
    assert.equal(await pending, false)
    assert.deepEqual(states, ['ready']); assert.equal(sound.ready, true)
    sound.close()
})

test('same-origin locks serialize independent tab ledgers and consume burst-suppressed IDs', async t => {
    let tail = Promise.resolve(), plays = 0, now = 10000
    const locks = { request: (_key, _options, action) => { const run = tail.then(action); tail = run.catch(() => {}); return run } }
    const { module: first, values } = environment(t, locks)
    const second = load('../notifications/notificationSound.ts')
    t.mock.method(Date, 'now', () => now)
    const play = () => { plays++; return true }
    assert.deepEqual(await Promise.all([first.claimNotificationSound('O:E', 'same', () => true, play),
        second.claimNotificationSound('O:E', 'same', () => true, play)]), [true, false])
    assert.equal(plays, 1)
    assert.equal(await second.claimNotificationSound('O:E', 'burst', () => true, play), false)
    now += 2001
    assert.equal(await first.claimNotificationSound('O:E', 'burst', () => true, play), false, 'Delayed push must not replay a burst ID')
    assert.equal(await first.claimNotificationSound('O:E', 'next', () => true, play), true)
    const serialized = [...values.values()].join('')
    assert.doesNotMatch(serialized, /templateArgs|workspaceId|body|title/)
    assert.equal(plays, 2)
})

test('waiting sound claims recheck authorization, preference and TTL before touching the ledger', async t => {
    let run, eligible = true, plays = 0
    const { module, values } = environment(t, { request: (_key, _options, action) => new Promise(resolve => { run = () => resolve(action()) }) })
    const pending = module.claimNotificationSound('O:E', 'pending', () => eligible, () => { plays++; return true })
    eligible = false; run()
    assert.equal(await pending, false); assert.equal(plays, 0); assert.equal(values.size, 0)
    const controller = new AbortController()
    eligible = true
    const logout = module.claimNotificationSound('O:E', 'logout', () => true, () => { plays++; return true }, controller.signal)
    controller.abort(); run()
    assert.equal(await logout, false); assert.equal(plays, 0)
})

test('blocked storage and missing locks retain best effort session dedup and a bounded ledger', async t => {
    const { module } = environment(t)
    global.localStorage = { getItem() { throw new Error('blocked') }, setItem() { throw new Error('blocked') } }
    let plays = 0, now = 10000
    t.mock.method(Date, 'now', () => now)
    const play = () => { plays++; return true }
    assert.equal(await module.claimNotificationSound('O:E', 'one', () => true, play), true)
    now += 3000
    assert.equal(await module.claimNotificationSound('O:E', 'one', () => true, play), false)
    assert.equal(await module.claimNotificationSound('O:OTHER', 'one', () => true, play), true)
    assert.equal(plays, 2)
})

test('preferences API sends only its boolean setting, rejects malformed responses and bypasses caches', async () => {
    const calls = []
    let body = { soundEnabled: true }
    const api = load('../../api/notificationApi.ts', {
        '../auth/authStorage': { getAccessToken: () => 'token' },
        './apiClient': { apiFetch: async (url, init) => { calls.push({ url, init }); return { json: async () => body } } },
    })
    assert.deepEqual(await api.getNotificationPreferences(), { soundEnabled: true })
    body = { soundEnabled: false }
    assert.deepEqual(await api.updateNotificationPreferences(false), { soundEnabled: false })
    assert.equal(calls[0].url, '/api/notifications/preferences')
    assert.equal(calls[0].init.cache, 'no-store')
    assert.equal(calls[1].init.method, 'PUT')
    assert.deepEqual(JSON.parse(calls[1].init.body), { soundEnabled: false })
    for (body of [null, {}, { soundEnabled: 'false' }]) await assert.rejects(api.getNotificationPreferences())
})
