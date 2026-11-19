const { test } = require('node:test')
const assert = require('node:assert/strict')
const React = require('react')
const { act, create } = require('react-test-renderer')
const load = require('./loadInboxModule.cjs')

const flush = async () => { for (let index = 0; index < 60; index++) await Promise.resolve() }

/**
 * Страница каналов с указанным списком и записью обращений к маршрутам включения и
 * отключения. Диалоги и аватары заглушены: здесь проверяется только то, какое действие
 * страница предлагает каналу в данном статусе.
 */
async function page(t, channels) {
    const calls = { telegramEnable: [], telegramDisable: [], dikidiDisconnect: [] }
    const Empty = () => null
    const Page = load('../../pages/ChannelsPage.tsx', {
        'react-router-dom': { useSearchParams: () => [new URLSearchParams(), () => undefined] },
        '../auth/AuthContext': { useAuth: () => ({ isSuperAdmin: false }) },
        '../workspace/WorkspaceContext': { useWorkspace: () => ({ currentWorkspace: { id: 'W-A' } }) },
        '../api/channelApi': { getWorkspaceChannels: async () => channels, deleteChannel: async () => undefined },
        '../api/telegramChannelApi': {
            enableTelegramChannel: async id => { calls.telegramEnable.push(id) },
            disableTelegramChannel: async id => { calls.telegramDisable.push(id) },
        },
        '../api/dikidiChannelApi': {
            connectDikidiChannel: async () => undefined,
            disconnectDikidiChannel: async id => { calls.dikidiDisconnect.push(id) },
        },
        '../components/channels/ChannelDetailsDialog': { ChannelDetailsDialog: Empty },
        '../components/channels/ChannelConnectionDialog': { ChannelConnectionDialog: Empty },
        '../components/channels/CreateChannelDialog': { CreateChannelDialog: Empty },
        '../components/channels/DeleteChannelDialog': { DeleteChannelDialog: Empty },
        '../components/channels/EditChannelDialog': { EditChannelDialog: Empty },
        '../components/channels/ChannelAccountAvatar': { ChannelAccountAvatar: Empty },
        '../components/common/platform/PlatformIcon': { default: Empty },
    }).default

    let renderer
    await act(async () => { renderer = create(React.createElement(Page)); await flush() })
    t.after(async () => { await act(async () => { renderer?.unmount(); await flush() }) })
    return { renderer, calls }
}

const button = (renderer, label) => renderer.root
    .findAll(node => node.type === 'button')
    .find(node => node.children.map(String).join('') === label)

const note = (renderer) => renderer.root
    .findAll(node => node.props?.className === 'channel-card-action-note')

const channel = (overrides) => ({
    id: 'channel', workspaceId: 'W-A', type: 'TELEGRAM', status: 'DISABLED', name: 'Channel',
    account: { id: 'account', displayName: 'Account' }, ...overrides,
})

/**
 * Отключённый DIKIDI-канал возникает по-настоящему: при откреплении проекта с сохранённой
 * перепиской канал уходит в DISABLED и остаётся. Кнопка «Включить» вела бы telegram-маршрут
 * — запроса включения у DIKIDI нет, а канал возвращается сам при возврате проекта.
 */
test('a disabled DIKIDI channel is not offered an enable action of another platform', async t => {
    const { renderer, calls } = await page(t, [channel({ type: 'DIKIDI' })])

    assert.equal(button(renderer, 'Включить'), undefined,
        'страница предлагает DIKIDI-каналу действие, которого у него нет')
    assert.equal(note(renderer).length, 1, 'причина отсутствия действия обязана быть названа')
    assert.deepEqual(calls.telegramEnable, [])
})

/** Telegram-канал в том же статусе включается по-прежнему: правка не должна была ничего отнять. */
test('a disabled telegram channel is still enabled through its own route', async t => {
    const { renderer, calls } = await page(t, [channel()])

    const action = button(renderer, 'Включить')
    assert.ok(action, 'у telegram-канала пропало включение')
    await act(async () => { await action.props.onClick(); await flush() })

    assert.deepEqual(calls.telegramEnable, ['account'])
    assert.deepEqual(calls.dikidiDisconnect, [])
})
