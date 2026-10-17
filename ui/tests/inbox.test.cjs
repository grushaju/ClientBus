const assert = require('node:assert/strict')
const { test } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const load = require('./loadInboxModule.cjs')
const selection = load('list/inboxSelection.ts')
const filters = load('filters/conversationFilterModel.ts')
const access = load('list/inboxAccess.ts')
const icons = load('groups/groupIcons.ts')
const dates = load('filters/filterDates.ts')
const employee = { isEmployee: true, isSuperAdmin: false, canViewAll: false, employeeId: 'employee-1' }

test('group selection survives conversation navigation and URL encoding', () => {
    const group = { view: 'GROUP', groupId: 'group & 1' }
    const url = new URL(selection.conversationUrl('conversation-1', group), 'http://localhost')
    assert.equal(url.pathname, '/inbox/conversation-1')
    assert.deepEqual(selection.selectionFromTab(url.searchParams.get('tab'), url.searchParams.get('groupId')), group)
    assert.equal(selection.conversationUrl(undefined, { view: 'MINE' }), '/inbox?tab=mine')
})

test('invalid or forbidden representations cannot expand employee access', () => {
    assert.deepEqual(selection.normalizeSelection({ view: 'ALL' }, employee), { view: 'MINE' })
    assert.deepEqual(selection.selectionFromTab('group', ''), { view: 'MINE' })
    assert.deepEqual(selection.selectionFromTab('unknown'), { view: 'MINE' })
    assert.deepEqual(selection.normalizeSelection({ view: 'GROUP', groupId: 'g' }, { ...employee, isSuperAdmin: true }), { view: 'ALL' })
    assert.deepEqual(selection.normalizeSelection({ view: 'UNASSIGNED' }, { ...employee, isSuperAdmin: true }), { view: 'UNASSIGNED' })
})

test('supervisor access is restricted to its workspace; membership actions to assignee', () => {
    const memberships = [{ workspaceId: 'a', isSupervisor: true }]
    assert.equal(access.getInboxAccess('b', 'e', true, false, memberships).canViewAll, false)
    assert.equal(access.getInboxAccess('a', 'e', true, false, memberships).canViewAll, true)
    assert.equal(access.canManageConversationGroups(employee, 'employee-1'), true)
    assert.equal(access.canManageConversationGroups(employee, null), false)
    assert.equal(access.canManageConversationGroups(employee, 'other'), false)
})

test('clearing saved conditions removes them from search and from a saved copy', () => {
    const draft = filters.criteriaFromDefinition({
        view: 'GROUP', groupId: 'old', search: 'old', channelAccountIds: ['channel'],
        activityFrom: '2026-10-01T12:00:01', activityTo: '2026-10-17T12:00:02',
        clientAccountIds: ['client-account'], sort: 'LAST_MESSAGE_ASC', page: 9, size: 50,
    })
    draft.search = '  '
    draft.channelAccountIds = []
    draft.activityFrom = ''
    draft.activityTo = ''
    const definition = filters.buildSavedDefinition({ view: 'UNASSIGNED' }, draft)
    assert.equal(definition.search, null)
    assert.deepEqual(definition.channelAccountIds, [])
    assert.equal(definition.activityFrom, null)
    assert.equal(definition.activityTo, null)
    assert.equal(definition.groupId, null)
    assert.equal(definition.view, 'UNASSIGNED')
    assert.deepEqual(definition.clientAccountIds, ['client-account'])
    assert.equal(definition.sort, 'LAST_MESSAGE_ASC')
    assert.equal('page' in definition, false)
    assert.equal('size' in definition, false)
    assert.deepEqual(filters.buildSearchRequest({ view: 'UNASSIGNED' }, draft, 2, 10), { ...definition, page: 2, size: 10 })
})

test('saved filter restores calendar dates and account constraints without shared arrays', () => {
    const definition = { view: 'MINE', clientAccountIds: ['account'], channelAccountIds: ['channel'], activityFrom: '2026-10-17T12:34:56.123456Z' }
    const draft = filters.criteriaFromDefinition(definition)
    draft.channelAccountIds.push('second')
    assert.deepEqual(definition.channelAccountIds, ['channel'])
    assert.equal(draft.activityFrom, dates.toLocalDateInput(definition.activityFrom))
    assert.equal(filters.buildSavedDefinition({ view: 'MINE' }, draft).activityFrom, dates.toFilterInstant(draft.activityFrom))
    const a = filters.createEmptyCriteria(), b = filters.createEmptyCriteria()
    a.clientAccountIds.push('a')
    assert.deepEqual(b.clientAccountIds, [])
})

test('unrecognized server group icons use a valid local fallback', () => {
    assert.equal(icons.resolveGroupIcon('../unknown.svg'), icons.DEFAULT_GROUP_ICON)
    assert.equal(icons.resolveGroupIcon(null), icons.DEFAULT_GROUP_ICON)
    assert.equal(icons.resolveGroupIcon('heart.svg'), 'heart.svg')
    for (const icon of icons.GROUP_ICON_OPTIONS) {
        assert.ok(fs.existsSync(path.resolve(__dirname, '../public/assets/group', icon.value)))
    }
})

function dataLoader(searchInbox, channels = async () => [], accounts = async () => []) {
    return load('list/inboxData.ts', {
        '../../../api/conversationApi': { searchInbox },
        '../../../api/channelApi': { getWorkspaceChannels: channels },
        '../../../api/clientAccountApi': { getClientAccountsByIds: accounts },
    }).loadInboxConversations
}

test('pagination preserves server order and enriches account/channel data', async () => {
    const calls = []
    const account = { id: 'client-account' }
    const channel = { id: 'channel', account: { id: 'channel-account' } }
    const conversation = id => ({ id, clientAccountId: account.id, channelAccountId: channel.account.id })
    const loader = dataLoader(async (workspaceId, request) => {
        calls.push([workspaceId, request])
        return { content: [conversation(request.page === 0 ? 'old' : 'new')], page: request.page, hasNext: request.page === 0 }
    }, async id => { assert.equal(id, 'workspace'); return [channel] }, async ids => {
        assert.deepEqual(ids, ['client-account'])
        return [account]
    })
    const result = await loader('workspace', { view: 'MINE', sort: 'LAST_MESSAGE_ASC' }, () => true)
    assert.deepEqual(result.items.map(item => item.conversation.id), ['old', 'new'])
    assert.deepEqual(calls.map(([, request]) => request.page), [0, 1])
    assert.ok(calls.every(([, request]) => request.sort === 'LAST_MESSAGE_ASC'))
    assert.equal(result.items[0].channel, channel)
    assert.equal(result.items[1].clientAccount, account)
})

test('obsolete search response stops pagination and enrichment', async () => {
    let current = true, resolveSearch
    const loader = dataLoader(() => new Promise(resolve => { resolveSearch = resolve }),
        async () => { throw Error('Must not load channels for obsolete search') })
    const result = loader('workspace', { view: 'MINE' }, () => current)
    current = false
    resolveSearch({ content: [], page: 0, hasNext: true })
    assert.equal(await result, null)
})

test('scope change during enrichment discards the complete obsolete result', async () => {
    let current = true
    const loader = dataLoader(async () => ({ content: [{ id: 'c', clientAccountId: 'a' }], page: 0, hasNext: false }),
        async () => [], async () => { current = false; return [{ id: 'a' }] })
    assert.equal(await loader('workspace', { view: 'MINE' }, () => current), null)
})

test('empty results still load channels and missing related entities remain nullable', async () => {
    const empty = dataLoader(async () => ({ content: [], page: 0, hasNext: false }),
        async () => [{ id: 'channel' }], async () => { throw Error('Empty account request') })
    assert.deepEqual((await empty('w', { view: 'MINE' }, () => true)).items, [])
    const missing = dataLoader(async () => ({ content: [{ id: 'c' }], page: 0, hasNext: false }))
    const result = await missing('w', { view: 'MINE' }, () => true)
    assert.equal(result.items[0].clientAccount, null)
    assert.equal(result.items[0].channel, null)
})

test('API errors propagate and non-advancing pagination cannot loop forever', async () => {
    const broken = dataLoader(async () => { throw Error('Search unavailable') })
    await assert.rejects(broken('w', { view: 'MINE' }, () => true), /Search unavailable/)
    const repeating = dataLoader(async () => ({ content: [], page: 0, hasNext: true }))
    await assert.rejects(repeating('w', { view: 'MINE' }, () => true), /пагинация/)
})

test('calendar date bounds respect local zones and reject impossible dates', () => {
    const originalZone = process.env.TZ
    try {
        process.env.TZ = 'Europe/Moscow'
        assert.equal(dates.toFilterInstant('2026-10-17'), '2026-10-16T21:00:00.000Z')
        assert.equal(dates.toFilterInstant('2026-10-17', 'end'), '2026-10-17T20:59:59.999999Z')
        assert.equal(dates.toLocalDateInput('2026-10-17T09:30:12.123Z'), '2026-10-17')
        assert.equal(dates.toLocalDateInput('2026-10-17T22:30:00Z'), '2026-10-18')
        assert.equal(dates.toLocalDateInput('2026-10-17'), '2026-10-17')
        assert.equal(dates.toFilterInstant('2026-10-17T12:30:12.123456789+03:00'), '2026-10-17T12:30:12.123456789+03:00')
        assert.equal(dates.toLocalDateInput(''), '')
        assert.equal(dates.toFilterInstant(''), null)
        assert.throws(() => dates.toFilterInstant('invalid date'), /корректные даты/)
        assert.throws(() => dates.toFilterInstant('2026-02-30'), /корректные даты/)
        assert.equal(dates.toFilterInstant('2028-02-29'), '2028-02-28T21:00:00.000Z')
        process.env.TZ = 'America/New_York'
        assert.equal(dates.toFilterInstant('2026-03-08'), '2026-03-08T05:00:00.000Z')
        assert.equal(dates.toFilterInstant('2026-03-08', 'end'), '2026-03-09T03:59:59.999999Z')
        assert.equal(dates.toFilterInstant('2026-11-01'), '2026-11-01T04:00:00.000Z')
        assert.equal(dates.toFilterInstant('2026-11-01', 'end'), '2026-11-02T04:59:59.999999Z')
        process.env.TZ = 'America/Sao_Paulo'
        assert.equal(dates.toFilterInstant('2018-11-04'), '2018-11-04T03:00:00.000Z')
        assert.equal(dates.toFilterInstant('2018-11-04', 'end'), '2018-11-05T01:59:59.999999Z')
    } finally {
        if (originalZone === undefined) delete process.env.TZ
        else process.env.TZ = originalZone
    }
})
