import { test as base, expect, type Page, type Route } from '@playwright/test'
import type { ConversationDto, ConversationGroupDto, ConversationSearchRequest } from '../../src/api/types/conversation'
import type { SavedConversationFilterDto } from '../../src/api/types/savedConversationFilter'

const timestamp = '2026-10-19T09:00:00Z'
const workspace = { id: 'workspace-1', organizationId: 'organization-1', name: 'Тестовый Workspace', avatarUrl: null, createdAt: timestamp, updatedAt: timestamp }
const employee = { id: 'employee-1', organizationId: workspace.organizationId, username: 'tester', firstName: 'Тестовый', lastName: 'Сотрудник', email: 'tester@example.test', phone: '', enabled: true, avatarUrl: null, createdAt: timestamp, updatedAt: timestamp }

export class InboxApi {
    role: 'employee' | 'supervisor' | 'admin' = 'employee'
    searchFailure: number | null = null
    conversationFailure: number | null = null
    rejectMembership = false
    failSend = false
    hidden = false
    searches: ConversationSearchRequest[] = []
    calls: { method: string; path: string; body: unknown }[] = []
    unexpected: string[] = []
    pageErrors: string[] = []
    soundEnabled = true
    account = { id: 'account-1', clientId: null as string | null, channelType: 'TELEGRAM', externalId: 'external-1', username: 'contact_one', phone: '70000000001', displayName: 'Контакт Один', avatarUrl: null }
    client = { id: 'client-1', organizationId: workspace.organizationId, firstName: 'Иван', lastName: 'Клиент', phoneList: ['70000000001'], avatarUrl: null, createdAt: timestamp, updatedAt: timestamp }
    groups: ConversationGroupDto[] = [{ id: 'group-1', workspaceId: workspace.id, ownerEmployeeId: employee.id, name: 'Важные', icon: 'heart.svg', createdAt: timestamp, updatedAt: timestamp }]
    filters: SavedConversationFilterDto[] = [
        { id: 'private-1', name: 'Мой фильтр', filterType: 'PRIVATE', filterDefinition: { view: 'MINE', unread: 'HAS_UNREAD' }, createdAt: timestamp, updatedAt: timestamp },
        { id: 'system-1', name: 'Системный фильтр', filterType: 'SYSTEM', filterDefinition: { view: 'ALL', unread: 'HAS_UNREAD' }, createdAt: timestamp, updatedAt: timestamp },
    ]
    membership = new Set(['group-1'])
    conversation: ConversationDto = { id: 'conversation-1', workspaceId: workspace.id, clientAccountId: 'account-1', channelAccountId: 'channel-account-1', assignedEmployeeId: employee.id, lastMessageAt: timestamp, lastMessagePreview: 'Проверка браузера', unreadCount: 0, historyStartReached: true, createdAt: timestamp, updatedAt: timestamp }
    channels = [{ id: 'channel-1', workspaceId: workspace.id, name: 'Тестовый канал', type: 'TELEGRAM', status: 'CONNECTED', account: { id: 'channel-account-1', externalId: 'channel-external', username: 'test_channel', phone: null, displayName: 'Тестовый канал', avatarUrl: null } }]

    private async reply(route: Route, value: unknown, status = 200) {
        await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(value) })
    }

    async handle(route: Route) {
        const request = route.request()
        const url = new URL(request.url())
        const path = url.pathname
        const method = request.method()
        const body = request.headers()['content-type']?.includes('application/json') ? request.postDataJSON() : request.postData()
        this.calls.push({ method, path, body })
        const reply = (value: unknown, status = 200) => this.reply(route, value, status)
        const error = (status: number, message: string) => reply({ code: status === 403 ? 'ACCESS_DENIED' : 'TEST_ERROR', message }, status)
        // These fixtures exercise Inbox with the optional backend transport disabled.
        if (path === '/api/events') return reply({}, 404)
        if (path === '/api/notifications/summary' && method === 'GET') return reply({ unreadCount: 0 })
        if (path === '/api/notifications/preferences' && method === 'GET') return reply({ soundEnabled: this.soundEnabled })
        if (path === '/api/notifications/preferences' && method === 'PUT' && typeof body?.soundEnabled === 'boolean') {
            this.soundEnabled = body.soundEnabled
            return reply({ soundEnabled: this.soundEnabled })
        }

        if (path === '/api/auth/login') return reply({ accessToken: 'browser-fixture-token', tokenType: 'Bearer' })
        if (path === '/api/employees/me') return reply({ ...employee, role: this.role === 'admin' ? 'SUPER_ADMIN' : 'EMPLOYEE' })
        if (path === '/api/employees/me/workspaces') return reply([{ employeeId: employee.id, workspaceId: workspace.id, isSupervisor: this.role === 'supervisor' }])
        if (path === '/api/organizations/organization-1') return reply({ id: workspace.organizationId, name: 'Browser Test Organization' })
        if (path === '/api/workspaces/my') return reply([workspace])
        if (path === '/api/employees/workspace/workspace-1') return reply([employee, { ...employee, id: 'employee-2', firstName: 'Другой' }])
        if (path === '/api/channels/workspace/workspace-1') return reply(this.channels)
        if (path === '/api/clientaccounts/by-ids' || path === '/api/clientaccounts/workspace/workspace-1') return reply([this.account])
        if (path === '/api/clientaccounts/account-1') return reply(this.account)
        if (path === '/api/clientaccounts/client/client-1' || path === '/api/clients/client-1/clientaccounts') return reply([this.account])
        if (path === '/api/clients' || path === '/api/clients/search') return reply([this.client])
        if (path === '/api/clients/client-1') return reply(this.client)
        if (path === '/api/clients/from-conversation/conversation-1' && method === 'POST') {
            this.client = { ...this.client, ...body }
            this.account.clientId = this.client.id
            return reply(this.client)
        }
        if (path === '/api/conversations/workspace/workspace-1/search') {
            this.searches.push(body)
            if (this.searchFailure) return error(this.searchFailure, 'Сбой обновления списка')
            const show = !this.hidden && (body.view !== 'UNASSIGNED' || !this.conversation.assignedEmployeeId)
                && (body.view !== 'MINE' || this.conversation.assignedEmployeeId === employee.id)
            return reply({ content: show ? [this.conversation] : [], page: 0, size: 10, totalElements: show ? 1 : 0, totalPages: show ? 1 : 0, hasNext: false })
        }
        if (path === '/api/conversations/conversation-1') {
            if (this.conversationFailure) { this.hidden = true; return error(this.conversationFailure, 'Диалог недоступен') }
            return reply(this.conversation)
        }
        if (path === '/api/conversations/conversation-1/read') return reply(this.conversation)
        if (path === '/api/messages/conversation/conversation-1/history/sync') return reply({ messages: [], historyStartReached: true })
        if (path.startsWith('/api/conversations/conversation-1/assignment')) {
            this.conversation.assignedEmployeeId = method === 'DELETE' ? null : employee.id
            return reply(this.conversation)
        }
        if (path === '/api/messages/conversation/conversation-1') return reply({ content: [{ id: 'message-1', conversationId: this.conversation.id, type: 'TEXT', direction: 'INBOUND', senderType: 'CLIENT', content: 'Проверка браузера', sentAt: timestamp, createdAt: timestamp, deliveryStatus: 'READ', processingStatus: 'PROCESSED' }], page: { number: 0, size: 50, totalPages: 1, totalElements: 1 } })
        if (/^\/api\/messages\/[^/]+\/attachments$/.test(path)) return reply([])
        if (path === '/api/messages/outbound') {
            if (this.failSend) return error(503, 'Отправка временно недоступна')
            return reply({ id: 'message-2', conversationId: this.conversation.id, type: 'TEXT', direction: 'OUTBOUND', senderType: 'EMPLOYEE', content: 'Тестовый ответ', sentAt: timestamp, createdAt: timestamp, deliveryStatus: 'SENT', processingStatus: 'PROCESSED' })
        }
        const groupBase = '/api/conversations/workspace/workspace-1/groups'
        if (path === `${groupBase}/membership`) return reply([...this.membership])
        if (path.includes('/members')) {
            if (this.rejectMembership) { this.hidden = true; return error(403, 'Состав группы больше недоступен') }
            if (method === 'DELETE') this.membership.delete('group-1')
            else this.membership.add('group-1')
            return reply(null)
        }
        if (path === groupBase) {
            if (method === 'POST') this.groups.push({ ...this.groups[0], ...body, id: 'group-2' })
            return reply(method === 'POST' ? this.groups.at(-1) : this.groups)
        }
        if (path.startsWith(`${groupBase}/`)) {
            const id = path.split('/').at(-1)
            if (method === 'DELETE') { this.groups = this.groups.filter(item => item.id !== id); return reply(null) }
            const group = this.groups.find(item => item.id === id)
            Object.assign(group!, body)
            return reply(group)
        }
        const filterBase = '/api/workspaces/workspace-1/conversation-filters'
        if (path === filterBase) {
            if (method === 'POST') {
                const filter = { ...this.filters[0], ...body, id: 'saved-filter' }
                this.filters.push(filter)
                return reply(filter)
            }
            return reply(this.filters.filter(filter => filter.filterType === url.searchParams.get('filterType')))
        }
        if (path.startsWith(`${filterBase}/`)) {
            const id = path.split('/').at(-1)
            if (method === 'DELETE') { this.filters = this.filters.filter(item => item.id !== id); return reply(null) }
            const filter = this.filters.find(item => item.id === id)
            Object.assign(filter!, body)
            return reply(filter)
        }
        this.unexpected.push(`${method} ${path}`)
        return error(501, `Не предусмотрен ответ: ${method} ${path}`)
    }
}

export const test = base.extend<{ api: InboxApi }>({
    api: [async ({ page }, use) => {
        const api = new InboxApi()
        page.on('pageerror', error => api.pageErrors.push(error.message))
        await page.addInitScript(() => localStorage.setItem('clientbus.accessToken', 'browser-fixture-token'))
        // Every API request is intercepted. Unexpected calls fail the test and
        // never reach a developer's running backend.
        await page.route('**/api/**', route => api.handle(route))
        await use(api)
        expect(api.unexpected, 'Все API-запросы должны иметь явный тестовый ответ').toEqual([])
        expect(api.pageErrors, 'Ошибки JavaScript в браузере').toEqual([])
    }, { auto: true }],
})

export { expect }

export async function showList(page: Page) {
    if (await page.getByRole('dialog', { name: 'Фильтры и группы' }).isVisible()) return
    // Initial auto-selection can switch mobile from the list to the thread.
    // Wait for that selection before deciding whether to press Back.
    await expect(page.locator('.conversation-header, .conversation-error').first()).toBeAttached()
    const back = page.getByRole('button', { name: 'К списку диалогов', exact: true })
    if (await back.isVisible()) await back.click()
    await expect(page.getByRole('complementary', { name: 'Список диалогов' })).toBeVisible()
}

export async function showWorkbench(page: Page) {
    if (await page.getByRole('dialog', { name: 'Фильтры и группы' }).isVisible()) return
    await showList(page)
    const menu = page.getByRole('button', { name: 'Фильтры и группы', exact: true })
    if (await menu.isVisible() && await menu.getAttribute('aria-expanded') !== 'true') await menu.click()
    await expect(page.getByRole('region', { name: 'Редактор фильтра' })).toBeVisible()
}
