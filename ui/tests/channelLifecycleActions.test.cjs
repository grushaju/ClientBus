const { test } = require('node:test')
const assert = require('node:assert/strict')
const React = require('react')
const { act, create } = require('react-test-renderer')
const load = require('./loadInboxModule.cjs')

const flush = async () => { for (let index = 0; index < 60; index++) await Promise.resolve() }

/**
 * Страница каналов с указанным списком и записью обращений к маршрутам включения и
 * отключения. Диалоги и аватары заглушены: здесь проверяется только то, какое действие
 * страница предлагает каналу в данном статусе — и предлагает ли вообще.
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

const notes = (renderer) => renderer.root
    .findAll(node => node.props?.className === 'channel-card-action-note')
    .map(node => node.children.map(String).join(''))

const channel = (overrides) => ({
    id: 'channel', workspaceId: 'W-A', type: 'TELEGRAM', status: 'DISABLED', name: 'Channel',
    account: { id: 'account', displayName: 'Account' }, ...overrides,
})

/**
 * Отключённый DIKIDI-канал возникает по-настоящему: при откреплении проекта с сохранённой
 * перепиской канал уходит в DISABLED и остаётся. Кнопка «Включить» вела бы telegram-маршрут —
 * запроса включения у DIKIDI нет, а канал возвращается сам при возврате проекта.
 */
test('a disabled DIKIDI channel is not offered an enable action of another platform', async t => {
    const { renderer } = await page(t, [channel({ type: 'DIKIDI' })])

    assert.equal(button(renderer, 'Включить'), undefined,
        'страница предлагает DIKIDI-каналу действие, которого у него нет')
    assert.deepEqual(notes(renderer), ['Включится сам, когда проект снова будет подключён'],
        'причина отсутствия действия обязана быть названа')
})

/** Telegram-канал в том же статусе включается по-прежнему: правка не должна была ничего отнять. */
test('a disabled telegram channel is still enabled through its own route', async t => {
    const { renderer, calls } = await page(t, [channel()])

    const action = button(renderer, 'Включить')
    assert.ok(action, 'у telegram-канала пропало включение')
    assert.deepEqual(notes(renderer), [], 'telegram-каналу не место с пояснением вместо кнопки')
    await act(async () => { await action.props.onClick(); await flush() })

    assert.deepEqual(calls.telegramEnable, ['account'])
    assert.deepEqual(calls.dikidiDisconnect, [])
})

/**
 * Белый список важнее чёрного: «не DIKIDI» означает, что любой следующий канал молча
 * получает чужой маршрут — не только включения, но и диалог авторизации чужой платформы.
 * Канал без своего сценария обязан получить пояснение, а не запрос.
 */
test('a channel without its own route is offered neither connect, enable nor disable', async t => {
    const { renderer, calls } = await page(t, [
        channel({ id: 'created', type: 'WHATSAPP', status: 'CREATED' }),
        channel({ id: 'connecting', type: 'WHATSAPP', status: 'CONNECTING' }),
        channel({ id: 'error', type: 'WHATSAPP', status: 'ERROR' }),
        channel({ id: 'off', type: 'WHATSAPP', status: 'DISABLED' }),
        channel({ id: 'on', type: 'WHATSAPP', status: 'CONNECTED' }),
    ])

    for (const label of ['Подключить', 'Подключить снова', 'Подключение...', 'Включить', 'Отключить']) {
        assert.equal(button(renderer, label), undefined, `каналу без маршрута предложено «${label}»`)
    }
    assert.deepEqual(notes(renderer), new Array(5).fill('Управление этим каналом ещё не настроено'))
    assert.deepEqual(calls.telegramEnable, [])
    assert.deepEqual(calls.telegramDisable, [])
})

/** Достигимые статусы telegram-канала обязаны остаться с кнопкой: правки ничего не отняли. */
test('a telegram channel still gets its connect action in every connectable status', async t => {
    const { renderer } = await page(t, [
        channel({ id: 'created', status: 'CREATED' }),
        channel({ id: 'reconnect', status: 'ERROR' }),
    ])

    assert.ok(button(renderer, 'Подключить'), 'у telegram-канала пропало подключение')
    assert.ok(button(renderer, 'Подключить снова'), 'у telegram-канала пропало повторное подключение')
    assert.deepEqual(notes(renderer), [])
})

/** DIKIDI подключается своим сценарием: белый список не должен был его отрезать. */
test('a dikidi channel still gets its own connect action', async t => {
    const { renderer } = await page(t, [channel({ type: 'DIKIDI', status: 'CREATED' })])

    assert.ok(button(renderer, 'Подключить'), 'у DIKIDI-канала пропало подключение')
    assert.deepEqual(notes(renderer), [], 'DIKIDI-канал получил пояснение вместо своего сценария')
})

/** У DIKIDI отключение своим маршрутом было и раньше — новый белый список его не отменил. */
test('a connected DIKIDI channel is still disconnected through its own route', async t => {
    const { renderer, calls } = await page(t, [channel({ type: 'DIKIDI', status: 'CONNECTED' })])

    const action = button(renderer, 'Отключить')
    assert.ok(action, 'у DIKIDI-канала пропало отключение')
    await act(async () => { await action.props.onClick(); await flush() })

    assert.deepEqual(calls.dikidiDisconnect, ['account'])
    assert.deepEqual(calls.telegramDisable, [])
})
