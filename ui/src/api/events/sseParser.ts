export interface SseFrame { event: string; data: string }

/** Incremental SSE framing. UTF-8 decoding belongs to the fetch reader. No replay/id state. */
export class SseParser {
    private line = ''
    private data: string[] = []
    private type = ''
    private bytes = 0
    private skipLf = false
    private first = true
    constructor(private readonly emit: (frame: SseFrame) => void, private readonly maxBytes = 18_432) {}

    push(text: string): void {
        for (const char of text) {
            if (this.first) { this.first = false; if (char === '\ufeff') continue }
            if (this.skipLf) { this.skipLf = false; if (char === '\n') continue }
            if (char === '\r' || char === '\n') {
                this.finishLine()
                this.skipLf = char === '\r'
            } else {
                this.line += char
                if (this.line.length > this.maxBytes) throw new Error('SSE frame too large')
            }
        }
    }
    private finishLine(): void {
        const line = this.line
        this.line = ''
        this.bytes += new TextEncoder().encode(line).length + 1
        if (this.bytes > this.maxBytes) throw new Error('SSE frame too large')
        if (!line) {
            const frame = this.data.length ? { event: this.type || 'message', data: this.data.join('\n') } : null
            this.data = []; this.type = ''; this.bytes = 0
            if (frame) this.emit(frame)
        } else if (!line.startsWith(':')) {
            const colon = line.indexOf(':')
            const field = colon < 0 ? line : line.slice(0, colon)
            let value = colon < 0 ? '' : line.slice(colon + 1)
            if (value.startsWith(' ')) value = value.slice(1)
            if (field === 'data') this.data.push(value)
            else if (field === 'event') this.type = value
        }
    }
    // An unterminated event at EOF is discarded, as required by SSE framing.
}
