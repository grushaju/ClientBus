import {  useEffect, useState } from 'react'

import {
    assignClientAccount,
    reassignClientAccount,
    unassignClientAccount,
    getClientAvatarContent,
} from '../../api/clientApi'

import {
    getClientAccountAvatarContent,
} from '../../api/clientAccountApi'

import type { ClientAccountDto } from '../../api/types/clientAccount'
import type { ClientListItemDto } from '../../api/types/client'

import ClientAccountPicker from './ClientAccountPicker'
import ClientReassignDialog from './ClientReassignDialog'
import ClientAccountAvatar from './ClientAccountAvatar'

import PlatformIcon from '../common/platform/PlatformIcon'
import {
    getPlatformName,
} from '../common/platform/platform'

interface ClientAccountsSectionProps {
    clientId: string
    accounts: ClientAccountDto[]
    onAccountsChange: (
        accounts: ClientAccountDto[],
    ) => void
    onConversationsReload?: () => Promise<void>
}

function ClientAccountsSection({
                                   clientId,
                                   accounts,
                                   onAccountsChange,
                                   onConversationsReload,
                               }: ClientAccountsSectionProps) {
    const [pickerOpen, setPickerOpen] =
        useState(false)

    const [reassignAccount, setReassignAccount] =
        useState<ClientAccountDto | null>(null)

    const [reassignTarget, setReassignTarget] =
        useState<ClientListItemDto | null>(null)

    const [busyAccountId, setBusyAccountId] =
        useState<string | null>(null)

    const [error, setError] =
        useState<string | null>(null)

    const [accountAvatarUrl, setAccountAvatarUrl] =
        useState<string | null>(null)

    const [targetAvatarUrl, setTargetAvatarUrl] =
        useState<string | null>(null)

    useEffect(() => {
        let cancelled = false
        let loadedAccountUrl: string | null = null
        let loadedTargetUrl: string | null = null

        setAccountAvatarUrl(null)
        setTargetAvatarUrl(null)

        if (!reassignAccount || !reassignTarget) {
            return
        }

        async function loadAvatar(
            loader: () => Promise<Blob>,
            setUrl: (url: string | null) => void,
            saveUrl: (url: string | null) => void,
        ) {
            try {
                const blob = await loader()

                if (cancelled || blob.size === 0) {
                    return
                }

                const url = URL.createObjectURL(blob)
                saveUrl(url)
                setUrl(url)
            } catch {
                // Аватар необязателен: при ошибке останется плейсхолдер.
            }
        }

        void loadAvatar(
            () => getClientAccountAvatarContent(reassignAccount.id),
            setAccountAvatarUrl,
            url => { loadedAccountUrl = url },
        )

        void loadAvatar(
            () => getClientAvatarContent(reassignTarget.id),
            setTargetAvatarUrl,
            url => { loadedTargetUrl = url },
        )

        return () => {
            cancelled = true

            if (loadedAccountUrl) {
                URL.revokeObjectURL(loadedAccountUrl)
            }

            if (loadedTargetUrl) {
                URL.revokeObjectURL(loadedTargetUrl)
            }
        }
    }, [reassignAccount, reassignTarget])

    async function handleAssign(
        account: ClientAccountDto,
    ) {
        setBusyAccountId(account.id)
        setError(null)

        try {
            const updatedAccount =
                await assignClientAccount(
                    clientId,
                    account.id,
                )

            onAccountsChange([
                ...accounts,
                updatedAccount,
            ])

            await onConversationsReload?.()

            setPickerOpen(false)
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось привязать аккаунт',
            )
        } finally {
            setBusyAccountId(null)
        }
    }

    async function handleUnassign(
        account: ClientAccountDto,
    ) {
        const title =
            getAccountTitle(account)

        if (
            !window.confirm(
                `Отвязать аккаунт «${title}» от этого клиента?`,
            )
        ) {
            return
        }

        setBusyAccountId(account.id)
        setError(null)

        try {
            await unassignClientAccount(
                clientId,
                account.id,
            )

            onAccountsChange(
                accounts.filter(
                    item =>
                        item.id !== account.id,
                ),
            )

            await onConversationsReload?.()
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось отвязать аккаунт',
            )
        } finally {
            setBusyAccountId(null)
        }
    }

    async function handleTransfer(
        targetClient: ClientListItemDto,
    ) {
        if (!reassignAccount) {
            return
        }

        const account =
            reassignAccount

        setBusyAccountId(account.id)
        setError(null)

        try {
            await reassignClientAccount(
                targetClient.id,
                account.id,
            )

            onAccountsChange(
                accounts.filter(
                    item =>
                        item.id !== account.id,
                ),
            )

            await onConversationsReload?.()

            setReassignAccount(null)
            setReassignTarget(null)
        } catch (err) {
            setError(
                err instanceof Error
                    ? err.message
                    : 'Не удалось передать аккаунт',
            )
        } finally {
            setBusyAccountId(null)
        }
    }

    return (
        <>
            <section className="client-details-card client-accounts-card">
                <div className="client-details-card-header">
                    <h2>
                        Аккаунты клиента
                    </h2>

                    <span>
                        {accounts.length}
                    </span>
                </div>

                {error && (
                    <div className="clients-error client-accounts-error">
                        {error}
                    </div>
                )}

                {accounts.length === 0 ? (
                    <div className="client-details-empty client-accounts-empty">
                        <span>
                            Нет привязанных аккаунтов
                        </span>
                    </div>
                ) : (
                    <div className="client-account-list">
                        {accounts.map(
                            account => {
                                const busy =
                                    busyAccountId ===
                                    account.id

                                const accountTitle =
                                    getAccountTitle(
                                        account,
                                    )

                                const platformName =
                                    getPlatformName(
                                        account.channelType,
                                    )

                                return (
                                    <div
                                        key={
                                            account.id
                                        }
                                        className="client-account-item"
                                    >
                                        <div className="client-account-item-main">
                                            <ClientAccountAvatar
                                                accountId={account.id}
                                                avatarUrl={account.avatarUrl}
                                                displayName={
                                                    account.displayName ||
                                                    account.username ||
                                                    account.phone
                                                }
                                                size={40}
                                            />

                                            <PlatformIcon
                                                type={account.channelType}
                                                size={18}
                                            />

                                            <div className="client-account-item-content">
                                                <div className="client-account-item-title">
                                                    <strong>
                                                        {
                                                            accountTitle
                                                        }
                                                    </strong>
                                                </div>

                                                <div className="client-account-item-platform">
                                                    {
                                                        platformName
                                                    }
                                                </div>

                                                {(
                                                    account.username ||
                                                    account.phone
                                                ) && (
                                                    <div className="client-account-item-meta">
                                                        {account.username && (
                                                            <span>
                                                                @{account.username}
                                                            </span>
                                                        )}

                                                        {account.phone && (
                                                            <span>
                                                                {
                                                                    account.phone
                                                                }
                                                            </span>
                                                        )}
                                                    </div>
                                                )}
                                            </div>
                                        </div>

                                        <div className="client-account-item-actions">
                                            <button
                                                type="button"
                                                className="ui-button ui-button-secondary ui-button-sm"
                                                disabled={
                                                    busy
                                                }
                                                onClick={() =>
                                                    setReassignAccount(
                                                        account,
                                                    )
                                                }
                                            >
                                                Передать
                                            </button>

                                            <button
                                                type="button"
                                                className="ui-button ui-button-danger ui-button-sm"
                                                disabled={
                                                    busy
                                                }
                                                onClick={() =>
                                                    handleUnassign(
                                                        account,
                                                    )
                                                }
                                            >
                                                Отвязать
                                            </button>
                                        </div>
                                    </div>
                                )
                            },
                        )}
                    </div>
                )}

                <div className="client-account-add">
                    <button
                        type="button"
                        className="ui-button ui-button-secondary ui-button-block"
                        onClick={() => {
                            setError(null)
                            setPickerOpen(true)
                        }}
                    >
                        <span aria-hidden="true">
                            +
                        </span>

                        Привязать аккаунт
                    </button>
                </div>
            </section>

            <ClientAccountPicker
                open={pickerOpen}
                onClose={() => {
                    if (!busyAccountId) {
                        setPickerOpen(false)
                    }
                }}
                onSelect={handleAssign}
                busy={
                    busyAccountId !== null
                }
            />

            <ClientReassignDialog
                open={
                    reassignAccount !== null
                }
                account={reassignAccount}
                currentClientId={
                    clientId
                }
                onClose={() => {
                    if (!busyAccountId) {
                        setReassignAccount(
                            null,
                        )
                    }
                }}
                onSelect={
                    targetClient => {
                        setReassignTarget(
                            targetClient,
                        )
                    }
                }
                busy={
                    busyAccountId !== null
                }
            />

            {reassignAccount &&
                reassignTarget && (
                    <div
                        className="client-modal-overlay"
                        onMouseDown={event => {
                            if (
                                event.target ===
                                event.currentTarget &&
                                !busyAccountId
                            ) {
                                setReassignTarget(
                                    null,
                                )
                            }
                        }}
                    >
                        <div
                            className="client-modal client-confirm-modal"
                            role="dialog"
                            aria-modal="true"
                            aria-labelledby="client-transfer-confirm-title"
                        >
                            <div className="client-modal-header">
                                <div>
                                    <h2 id="client-transfer-confirm-title">
                                        Подтвердите передачу
                                    </h2>

                                    <span>
                                        Аккаунт будет передан другому клиенту
                                    </span>
                                </div>

                                <button
                                    type="button"
                                    className="client-modal-close"
                                    onClick={() =>
                                        setReassignTarget(
                                            null,
                                        )
                                    }
                                    disabled={
                                        busyAccountId !==
                                        null
                                    }
                                    aria-label="Закрыть"
                                >
                                    ×
                                </button>
                            </div>

                            <div className="client-modal-body">
                                <div className="client-reassign-confirm">
                                    <div className="client-reassign-person">
                                        <div className="client-reassign-account-avatar">
                                            {accountAvatarUrl ? (
                                                <img src={accountAvatarUrl} alt=""/>
                                            ) : (
                                                <span aria-hidden="true">👤</span>
                                            )}
                                        </div>

                                        <div className="client-reassign-platform-icon">
                                            <PlatformIcon
                                                type={reassignAccount.channelType}
                                                size={20}
                                            />
                                        </div>

                                        <span className="client-reassign-confirm-label">
                                            Исходный аккаунт
                                        </span>

                                        <strong className="client-reassign-person-name">
                                            {getAccountTitle(reassignAccount)}
                                        </strong>

                                        {reassignAccount.username && (
                                            <span className="client-reassign-person-meta">
                                                @{reassignAccount.username}
                                            </span>
                                            )}

                                        {reassignAccount.phone && (
                                            <span className="client-reassign-person-meta">
                                                {reassignAccount.phone}
                                            </span>
                                        )}
                                    </div>

                                    <div
                                        className="client-reassign-confirm-arrow"
                                        aria-hidden="true"
                                    >
                                        →
                                    </div>

                                    <div className="client-reassign-person">
                                        <div className="client-reassign-client-avatar">
                                            {targetAvatarUrl ? (
                                                <img src={targetAvatarUrl} alt=""/>
                                            ) : (
                                                <span aria-hidden="true">👤</span>
                                            )}
                                        </div>

                                        <span className="client-reassign-confirm-label">
                                            Новый клиент
                                        </span>

                                        <strong className="client-reassign-person-name">
                                            {getClientTitle(reassignTarget)}
                                        </strong>

                                        {reassignTarget.phoneList
                                            .filter(phone => phone.trim())
                                            .map((phone, index) => (
                                                <span
                                                    className="client-reassign-person-meta"
                                                    key={`${phone}-${index}`}
                                                >
                                                    {phone}
                                                </span>
                                            ))}
                                    </div>
                                </div>
                            </div>

                            <div className="client-modal-footer">
                                <button
                                    type="button"
                                    className="ui-button ui-button-secondary"
                                    onClick={() =>
                                        setReassignTarget(
                                            null,
                                        )
                                    }
                                    disabled={
                                        busyAccountId !==
                                        null
                                    }
                                >
                                    Отмена
                                </button>

                                <button
                                    type="button"
                                    className="ui-button ui-button-primary"
                                    onClick={() =>
                                        handleTransfer(
                                            reassignTarget,
                                        )
                                    }
                                    disabled={
                                        busyAccountId !==
                                        null
                                    }
                                >
                                    {busyAccountId !==
                                    null
                                        ? 'Передача…'
                                        : 'Передать'}
                                </button>
                            </div>
                        </div>
                    </div>
                )}
        </>
    )
}

function getAccountTitle(
    account: ClientAccountDto,
): string {
    return (
        account.displayName ||
        account.username ||
        account.phone ||
        account.externalId
    )
}

function getClientTitle(
    client: ClientListItemDto,
): string {
    const fullName =
        `${client.firstName} ${client.lastName}`.trim()

    return (
        fullName ||
        client.phoneList[0] ||
        client.id
    )
}

export default ClientAccountsSection