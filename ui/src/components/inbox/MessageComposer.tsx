import {
    useRef,
    useState,
    type ChangeEvent,
    type FormEvent,
    type KeyboardEvent,
} from 'react'

import { sendOutboundMessage } from '../../api/messageApi'

import type { MessageType } from '../../api/types/message'

type AttachmentMode =
    | 'NONE'
    | 'IMAGE'
    | 'AUDIO'

interface Props {
    conversationId: string
    onSent?: () => void | Promise<void>
}

function MessageComposer({
                             conversationId,
                             onSent,
                         }: Props) {
    const [content, setContent] =
        useState('')

    const [attachments, setAttachments] =
        useState<File[]>([])

    const [attachmentMode, setAttachmentMode] =
        useState<AttachmentMode>('NONE')

    const [menuOpen, setMenuOpen] =
        useState(false)

    const [sending, setSending] =
        useState(false)

    const [error, setError] =
        useState<string | null>(null)

    const fileInputRef =
        useRef<HTMLInputElement | null>(null)

    const clearAttachments = () => {
        setAttachments([])
        setAttachmentMode('NONE')

        if (fileInputRef.current) {
            fileInputRef.current.value = ''
        }
    }

    const openFilePicker = (
        mode: 'IMAGE' | 'AUDIO',
    ) => {
        setAttachmentMode(mode)
        setAttachments([])
        setError(null)
        setMenuOpen(false)

        if (fileInputRef.current) {
            fileInputRef.current.value = ''

            fileInputRef.current.accept =
                mode === 'IMAGE'
                    ? 'image/*'
                    : 'audio/*'

            fileInputRef.current.multiple =
                mode === 'IMAGE'

            fileInputRef.current.click()
        }
    }

    const handleFileChange = (
        event: ChangeEvent<HTMLInputElement>,
    ) => {
        const files =
            Array.from(
                event.target.files ?? [],
            )

        if (files.length === 0) {
            return
        }

        if (attachmentMode === 'IMAGE') {
            if (files.length > 10) {
                setAttachments([])
                setError(
                    'Можно отправить не более 10 фотографий',
                )
                return
            }

            const invalidFile =
                files.find(
                    file =>
                        !file.type.startsWith(
                            'image/',
                        ),
                )

            if (invalidFile) {
                setAttachments([])
                setError(
                    'Можно выбрать только изображения',
                )
                return
            }

            setAttachments(files)
            setError(null)

            return
        }

        if (attachmentMode === 'AUDIO') {
            if (files.length > 1) {
                setAttachments([])
                setError(
                    'Можно отправить только один аудиофайл',
                )
                return
            }

            const file = files[0]

            if (!file.type.startsWith('audio/')) {
                setAttachments([])
                setError(
                    'Можно выбрать только аудиофайл',
                )
                return
            }

            setAttachments([file])
            setError(null)
        }
    }

    const removeAttachment = (
        index: number,
    ) => {
        setAttachments(current =>
            current.filter(
                (_, fileIndex) =>
                    fileIndex !== index,
            ),
        )

        setError(null)
    }

    const handleSend = async () => {
        const trimmedContent =
            content.trim()

        if (
            !trimmedContent &&
            attachments.length === 0
        ) {
            return
        }

        if (attachmentMode === 'IMAGE') {
            if (
                attachments.length === 0 ||
                attachments.length > 10
            ) {
                setError(
                    'Можно отправить от 1 до 10 фотографий',
                )

                return
            }
        }

        if (attachmentMode === 'AUDIO') {
            if (attachments.length !== 1) {
                setError(
                    'Для аудио необходимо выбрать один файл',
                )

                return
            }
        }

        setSending(true)
        setError(null)

        try {
            let type: MessageType = 'TEXT'

            if (attachmentMode === 'IMAGE') {
                type = 'IMAGE'
            } else if (
                attachmentMode === 'AUDIO'
            ) {
                type = 'AUDIO'
            }

            await sendOutboundMessage(
                conversationId,
                type,
                trimmedContent || null,
                attachments,
            )

            setContent('')
            clearAttachments()

            await onSent?.()
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось отправить сообщение',
            )
        } finally {
            setSending(false)
        }
    }

    const handleSubmit = (
        event: FormEvent<HTMLFormElement>,
    ) => {
        event.preventDefault()
        void handleSend()
    }

    const handleKeyDown = (
        event: KeyboardEvent<HTMLTextAreaElement>,
    ) => {
        if (
            event.key !== 'Enter' ||
            event.shiftKey
        ) {
            return
        }

        event.preventDefault()

        if (!sending) {
            void handleSend()
        }
    }

    return (
        <form
            className="message-composer"
            onSubmit={handleSubmit}
        >
            {attachments.length > 0 && (
                <div className="message-composer-attachments">
                    {attachments.map(
                        (file, index) => (
                            <div
                                key={`${file.name}-${index}`}
                                className="message-composer-attachment"
                            >
                                <span className="message-composer-attachment-name">
                                    {file.name}
                                </span>

                                <button
                                    type="button"
                                    className="message-composer-attachment-remove"
                                    onClick={() =>
                                        removeAttachment(
                                            index,
                                        )
                                    }
                                    disabled={sending}
                                >
                                    ×
                                </button>
                            </div>
                        ),
                    )}
                </div>
            )}

            {error && (
                <div className="message-composer-error">
                    {error}
                </div>
            )}

            <div className="message-composer-row">
                <div className="message-composer-add">
                    <button
                        type="button"
                        className="ui-button ui-button-sm ui-button-secondary message-composer-add-button"
                        onClick={() =>
                            setMenuOpen(
                                open => !open,
                            )
                        }
                        disabled={sending}
                    >
                        Добавить
                    </button>

                    {menuOpen && (
                        <div className="message-composer-add-menu">
                            <button
                                type="button"
                                onClick={() =>
                                    openFilePicker(
                                        'IMAGE',
                                    )
                                }
                                disabled={sending}
                            >
                                Фото
                            </button>

                            <button
                                type="button"
                                onClick={() =>
                                    openFilePicker(
                                        'AUDIO',
                                    )
                                }
                                disabled={sending}
                            >
                                Аудио
                            </button>
                        </div>
                    )}
                </div>

                <textarea
                    className="ui-textarea"
                    value={content}
                    onChange={event =>
                        setContent(
                            event.target.value,
                        )
                    }
                    onKeyDown={handleKeyDown}
                    placeholder="Введите сообщение..."
                    disabled={sending}
                    rows={1}
                />

                <button
                    type="submit"
                    className="ui-button ui-button-primary"
                    disabled={
                        sending ||
                        (
                            !content.trim() &&
                            attachments.length === 0
                        )
                    }
                >
                    {sending
                        ? 'Отправка…'
                        : 'Отправить'}
                </button>
            </div>

            <input
                ref={fileInputRef}
                type="file"
                hidden
                accept={
                    attachmentMode === 'IMAGE'
                        ? 'image/*'
                        : 'audio/*'
                }
                multiple={
                    attachmentMode === 'IMAGE'
                }
                onChange={handleFileChange}
            />
        </form>
    )
}

export default MessageComposer