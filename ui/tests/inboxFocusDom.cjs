// A small focus/event DOM for the real drawer hook. Layout is checked manually;
// these nodes model focus order, disabled controls and existing inert owners.
module.exports = function focusDom() {
    const document = new EventTarget()
    const node = name => ({
        name, ownerDocument: document, parentElement: null, children: [],
        attributes: new Map(), tabIndex: 0, isConnected: true, visible: true,
        hasAttribute(key) { return this.attributes.has(key) },
        getAttribute(key) { return this.attributes.get(key) ?? null },
        setAttribute(key, value) { this.attributes.set(key, value) },
        removeAttribute(key) { this.attributes.delete(key) },
        getClientRects() { return this.visible ? [{}] : [] },
        contains(other) { return this === other || this.children.some(child => child.contains(other)) },
        focus() {
            document.activeElement = this
            const event = new Event('focusin')
            Object.defineProperty(event, 'target', { value: this })
            document.dispatchEvent(event)
        },
    })
    const attach = (parent, ...children) => {
        parent.children = children
        for (const child of children) child.parentElement = parent
    }
    const body = node('body'), app = node('app'), chrome = node('chrome'), existing = node('existing modal')
    const layout = node('layout'), browser = node('browser'), panel = node('panel'), list = node('list'), thread = node('thread')
    const backdrop = node('backdrop'), opener = node('opener'), search = node('search')
    const close = node('close'), disabled = node('disabled'), first = node('first'), last = node('last')
    panel.tabIndex = -1
    disabled.setAttribute('disabled', '')
    existing.setAttribute('inert', 'existing')
    backdrop.setAttribute('data-inbox-backdrop', '')
    attach(body, app)
    attach(app, chrome, existing, layout)
    attach(layout, browser, thread)
    attach(browser, backdrop, panel, list)
    attach(panel, close, disabled, first, last)
    attach(list, opener, search)
    panel.querySelectorAll = () => [close, disabled, first, last]
    document.body = body
    document.activeElement = opener
    return {
        document, panel, list, thread, chrome, existing, backdrop, opener, search, close, first, last,
        createNodeMock: element => {
            if (element.props.className === 'inbox-navigation') return panel
            if (element.props.className?.includes('inbox-navigation-toggle')) return opener
            if (element.props.type === 'search') return search
            return null
        },
        key: (key, shiftKey = false) => {
            const event = Object.assign(new Event('keydown', { cancelable: true }), { key, shiftKey })
            document.dispatchEvent(event)
            return event
        },
    }
}
