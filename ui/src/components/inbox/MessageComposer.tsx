import {
    useRef,
    useState,
    type ChangeEvent,
    type FormEvent
} from 'react'

import {
    sendOutboundMessage
} from '../../api/messageApi'

interface MessageComposerProps {
    conversationId: string
    onSent: () => void
}

function MessageComposer({
                             conversationId,
                             onSent
                         }: MessageComposerProps) {
    const [
        content,
        setContent
    ] = useState('')

    const [
        attachments,
        setAttachments
    ] = useState<File[]>([])

    const [
        isSending,
        setIsSending
    ] = useState(false)

    const [
        error,
        setError
    ] = useState<string | null>(null)

    const inputRef =
        useRef<HTMLInputElement | null>(
            null
        )

    async function handleSubmit(
        event: FormEvent
    ): Promise<void> {
        event.preventDefault()

        const text =
            content.trim()

        if (
            !text &&
            attachments.length === 0
        ) {
            return
        }

        setIsSending(true)
        setError(null)

        try {
            await sendOutboundMessage(
                conversationId,
                text,
                attachments
            )

            setContent('')
            setAttachments([])

            if (inputRef.current) {
                inputRef.current.value =
                    ''
            }

            onSent()
        } catch {
            setError(
                'Не удалось отправить сообщение'
            )
        } finally {
            setIsSending(false)
        }
    }

    function handleFiles(
        event: ChangeEvent<HTMLInputElement>
    ): void {
        const files =
            Array.from(
                event.target.files ?? []
            )

        setAttachments(files)
    }

    return (
        <form
            className="message-composer"
            onSubmit={handleSubmit}
        >
            {error && (
                <div className="message-composer-error">
                    {error}
                </div>
            )}

            {attachments.length > 0 && (
                <div className="message-attachments">
                    {attachments.map(
                        file => (
                            <span
                                key={`${file.name}-${file.size}`}
                            >
                                {file.name}
                            </span>
                        )
                    )}
                </div>
            )}

            <div className="message-composer-row">
                <input
                    ref={inputRef}
                    type="file"
                    multiple
                    accept="image/*,audio/*"
                    onChange={
                        handleFiles
                    }
                    hidden
                />

                <button
                    type="button"
                    onClick={() =>
                        inputRef.current?.click()
                    }
                    disabled={isSending}
                    className="composer-attachment-button"
                >
                    +
                </button>

                <textarea
                    value={content}
                    onChange={event =>
                        setContent(
                            event.target.value
                        )
                    }
                    placeholder="Написать сообщение..."
                    rows={2}
                    disabled={isSending}
                    onKeyDown={event => {
                        if (
                            event.key ===
                            'Enter' &&
                            !event.shiftKey
                        ) {
                            event.preventDefault()

                            void handleSubmit(
                                event
                            )
                        }
                    }}
                />

                <button
                    type="submit"
                    disabled={
                        isSending ||
                        (
                            !content.trim() &&
                            attachments.length ===
                            0
                        )
                    }
                >
                    {isSending
                        ? 'Отправка...'
                        : 'Отправить'}
                </button>
            </div>
        </form>
    )
}

export default MessageComposer