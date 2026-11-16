package kit.penny.clientbus.connector.dikidi;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.common.notification.EventOrigin;
import kit.penny.clientbus.connector.api.IChannelConnector;
import kit.penny.clientbus.connector.api.command.MarkMessagesReadCommand;
import kit.penny.clientbus.connector.api.command.SendMessageCommand;
import kit.penny.clientbus.connector.api.command.SyncConversationHistoryCommand;
import kit.penny.clientbus.connector.api.command.SyncRecentChatsCommand;
import kit.penny.clientbus.connector.api.host.HostChannelAccountSnapshot;
import kit.penny.clientbus.connector.api.host.HostChannelAccounts;
import kit.penny.clientbus.connector.api.host.HostConversationCheckpoints;
import kit.penny.clientbus.connector.api.host.HostConversationCheckpoints.ConversationCheckpoint;
import kit.penny.clientbus.connector.api.host.HostInboundPublisher;
import kit.penny.clientbus.connector.api.model.ConnectorConversation;
import kit.penny.clientbus.connector.api.model.ConnectorInboundMessage;
import kit.penny.clientbus.connector.api.model.ConnectorSendResult;
import kit.penny.clientbus.connector.api.model.SyncConversationHistoryResult;
import kit.penny.clientbus.integration.dikidi.DikidiClient;
import kit.penny.clientbus.integration.dikidi.DikidiCredential;
import kit.penny.clientbus.integration.dikidi.DikidiFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Канальный коннектор DIKIDI: чтение клиентских диалогов и истории через общий
 * DikidiClient (профиль OWNER_CHAT), отправка текста/картинок (C07+C06), отметка
 * прочтения (C08) и receipt прочтения через эхо (13.6, CRM_DIKIDI_PROTOCOL.md).
 * Поиск контакта и создание нового диалога не реализованы — протокольно не
 * подтверждены; вызовы запрещены явно, а не молча.
 *
 * Классификация без догадок (Stage 13.5): клиентский диалог — разрешимый клиент
 * среди участников и разрешимый владелец по пробнику сообщений (автор out=1, иначе
 * единственный автор входящих). Служебная поддержка DIKIDI, каталог ботов и
 * неизвестная форма пропускаются с логом и никогда не становятся карточкой клиента.
 *
 * Источники событий: начальный импорт идёт History-путём (хост форсирует
 * HISTORY_IMPORT — персональных уведомлений нет); сообщения, пришедшие после
 * завершённого initial sync, коннектор публикует сам через HostInboundPublisher
 * с origin LIVE. Курсор — HostConversationCheckpoints, двигается только после
 * передачи страницы; повторы гасятся дедупом хоста по внешнему id сообщения.
 * Начальная история не объявляется полной, пока не доказана пустой страницей C05.
 */
public class DikidiChannelConnector implements IChannelConnector {
    private static final Logger log = LoggerFactory.getLogger(DikidiChannelConnector.class);
    /** Бюджет страниц C02 за один проход recent-chats (бюджетная дисциплина как в C05). */
    private static final int DIALOG_LIST_PAGES = 5;
    /** Потолок загружаемого файла: лимит платформы захватом не подтверждён — страховка памяти. */
    private static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
    /** Бюджет обхода C05 для C08: тот же порядок, что у начального импорта (5 стр × 20). */
    private static final int MARK_READ_PAGE_BUDGET = 5;

    private final DikidiClient client;
    private final DikidiTechnicalCredentials credentials;
    private final HostChannelAccounts accounts;
    private final HostConversationCheckpoints checkpoints;
    private final HostInboundPublisher inbound;
    private final DikidiChatReads reads;
    private final DikidiChatAccess chatAccess;
    private final kit.penny.clientbus.connector.api.host.HostAttachmentStorage attachments;
    private final int initialBudgetPages;

    public DikidiChannelConnector(DikidiClient client, DikidiTechnicalCredentials credentials,
            HostChannelAccounts accounts, HostConversationCheckpoints checkpoints,
            HostInboundPublisher inbound, DikidiChatReads reads, DikidiChatAccess chatAccess,
            int initialBudgetPages) {
        this(client, credentials, accounts, checkpoints, inbound, reads, chatAccess, initialBudgetPages, null);
    }

    public DikidiChannelConnector(DikidiClient client, DikidiTechnicalCredentials credentials,
            HostChannelAccounts accounts, HostConversationCheckpoints checkpoints,
            HostInboundPublisher inbound, DikidiChatReads reads, DikidiChatAccess chatAccess,
            int initialBudgetPages, kit.penny.clientbus.connector.api.host.HostAttachmentStorage attachments) {
        this.client = client; this.credentials = credentials; this.accounts = accounts;
        this.checkpoints = checkpoints; this.inbound = inbound; this.reads = reads;
        this.chatAccess = chatAccess; this.initialBudgetPages = Math.max(1, initialBudgetPages);
        this.attachments = attachments;
    }

    /** Успешный CRM-health этой проверкой не считается: канал входит в CONNECTED только после успеха. */
    public DikidiChatAccess.Result verifyChatAccess(java.util.UUID channelAccountId) {
        return chatAccess.verify(channelAccountId);
    }

    @Override public boolean supports(ChannelType channelType) { return channelType == ChannelType.DIKIDI; }
    /**
     * Отправка текста в DIKIDI (C06, захват пользователя 15.11.2026) — через executeOnce:
     * изменяющая операция никогда не повторяется восстановлением сессии (защита от дублей).
     * Доказанный отказ платформы (error.code≠0) — ConnectorSendRejectedException: внешнего
     * эффекта нет, хост освободит клейм и после повторов пометит FAILED. Прочие сбои после
     * POST — исход неизвестен: клейм удерживается (13.2b), автоматического повтора нет.
     */
    @Override public ConnectorSendResult handle(SendMessageCommand command) {
        // Текст уходит как есть (веб-клиент не тримит); проверка «пустое сообщение»
        // не должна переписывать пользовательский текст.
        String text = command.content() == null ? "" : command.content();
        if (text.isBlank() && command.attachments().isEmpty())
            throw new kit.penny.clientbus.connector.api.error.ConnectorSendRejectedException(
                    "DIKIDI outbound requires text or attachments");
        for (var attachment : command.attachments())
            if (attachment.type() != kit.penny.clientbus.common.enums.MessageAttachmentType.IMAGE
                    || attachment.content() == null)
                throw new kit.penny.clientbus.connector.api.error.ConnectorSendRejectedException(
                        "DIKIDI outbound supports readable image attachments only (C07)");
        var account = accounts.require(command.channelAccountId());
        String company = company(account);
        try (DikidiCredential credential = credentials.obtain(account.id())) {
            var outcome = client.executeOnce(credential, null, session -> {
                String dialog;
                var uploads = new ArrayList<DikidiChatReads.Uploaded>();
                // Всё до C06 — pre-dispatch: сбой здесь доказуемо не тронул переписку,
                // значит это отказ (клейм освобождается, после повторов DLT→FAILED),
                // а не вечное удержание UNKNOWN (ресью N1 15.11: хвост бэклога
                // «pre-dispatch hang» закрыт на отправке — первом боевом write-пути).
                try {
                    reads.shell(session, company);
                    dialog = command.dialogExternalId() != null && !command.dialogExternalId().isBlank()
                            ? command.dialogExternalId()
                            : resolveDialog(session, company, command.recipientExternalId());
                    for (var attachment : command.attachments()) {
                        byte[] bytes;
                        try (var stream = attachment.content()) { bytes = stream.readAllBytes(); }
                        catch (java.io.IOException unreadable) {
                            throw new kit.penny.clientbus.connector.api.error.ConnectorSendRejectedException(
                                    "DIKIDI outbound attachment is unreadable");
                        }
                        if (bytes.length == 0 || bytes.length > MAX_UPLOAD_BYTES)
                            throw new kit.penny.clientbus.connector.api.error.ConnectorSendRejectedException(
                                    "DIKIDI outbound attachment outside 1..10 MiB (platform limit unproven)");
                        uploads.add(reads.uploadImage(session, company, attachment.fileName(),
                                attachment.contentType(), bytes));
                    }
                } catch (DikidiFailure beforeSend) {
                    throw new kit.penny.clientbus.connector.api.error.ConnectorSendRejectedException(
                            "DIKIDI failed before C06 send (" + beforeSend.code() + "); outbound was not attempted",
                            beforeSend);
                } catch (IllegalStateException unresolvedDialog) {
                    throw new kit.penny.clientbus.connector.api.error.ConnectorSendRejectedException(
                            "DIKIDI dialog could not be resolved before send", unresolvedDialog);
                }
                return reads.sendMessage(session, company, sendForm(dialog, text, uploads));
            });
            if (!outcome.accepted())
                throw new kit.penny.clientbus.connector.api.error.ConnectorSendRejectedException(
                        "DIKIDI rejected outbound, error.code=" + outcome.errorCode());
            return new ConnectorSendResult(outcome.externalId());
        }
    }

    /**
     * Форма C06 подтверждена публичным кодом веб-клиента (backend.js submit handler +
     * upload-виджет dynamic-2.0.js, 15.11.2026): tabHash, dialog, text и files[] — по
     * одному числовому fileId на вложение (результат C07-загрузки); joining сервер
     * собирает сам. Company в форме не участвует: контекст проекта держит сессия.
     */
    private static List<Map.Entry<String, String>> sendForm(String dialog, String text,
            List<DikidiChatReads.Uploaded> uploads) {
        var form = new ArrayList<Map.Entry<String, String>>();
        form.add(Map.entry("tabHash", DikidiChatReads.TAB_HASH));
        form.add(Map.entry("dialog", dialog));
        // Веб-клиент шлёт text всегда (hidden-input, даже пустой) и НЕ шлёт company —
        // контекст проекта держит сессия (backend.js submit handler, 15.11.2026).
        form.add(Map.entry("text", text));
        for (var upload : uploads) form.add(Map.entry("files[]", upload.fileId()));
        return List.copyOf(form);
    }

    /**
     * C08 отметка прочтения (POST /api/dialog/read/, поля tabHash/dialog/msg[] — форма
     * подтверждена публичным backend.js 15.11.2026). msg — конкретные id ВХОДЯЩИХ
     * сообщений с id не выше lastReadExternalId (нумерация платформы возрастает —
     * предположение зафиксировано в CRM_DIKIDI_PROTOCOL.md). Страницы C05 (DESC)
     * обходятся вниз до пересечения курсора прочтения: одна свежая страница не
     * покрывала бы >20 непрочитанных и старые зависали бы навсегда (ресью N1 15.11);
     * бюджет обхода — как у начального импорта. Числовой lastReadExternalId обязателен:
     * нечитаемый курсор — fail-closed без POST (отметить «всё» по мусорному входу
     * значило бы поставить ложный прочитанный статус). Операция идемпотентна —
     * допустим auth-повтор (execute), сбой уходит в DLQ честно: отметка не поставлена.
     */
    @Override public void handle(MarkMessagesReadCommand command) {
        var account = accounts.require(command.channelAccountId());
        String company = company(account);
        long last = parseId(command.lastReadExternalId());
        if (last < 0) {
            log.warn("DIKIDI mark-read skipped: unreadable read cursor {} (account={}, dialog={})",
                    command.lastReadExternalId(), account.id(), command.dialogExternalId());
            return;
        }
        try (DikidiCredential credential = credentials.obtain(account.id())) {
            client.execute(credential, null, session -> {
                reads.shell(session, company);
                String dialog = command.dialogExternalId() != null && !command.dialogExternalId().isBlank()
                        ? command.dialogExternalId()
                        : resolveDialog(session, company, command.recipientExternalId());
                var ids = new ArrayList<String>();
                String cursor = null;
                for (int page = 0; page < MARK_READ_PAGE_BUDGET; page++) {
                    var current = reads.messages(session, dialog, cursor, DikidiChatReads.MAX_PAGE, company);
                    if (current.messages().isEmpty() || current.historyStartReached()) break;
                    long oldest = Long.MAX_VALUE;
                    for (var message : current.messages()) {
                        long id = parseId(message.id());
                        if (id < 0) {
                            log.warn("DIKIDI mark-read ignores non-numeric message id {} (dialog={})",
                                    message.id(), dialog);
                            continue;
                        }
                        oldest = Math.min(oldest, id);
                        if (!message.out() && id <= last) ids.add(message.id());
                    }
                    // Пересекли курсор: всё старше уже прочитано по смыслу команды,
                    // дальних страниц не касаемся.
                    if (oldest <= last) break;
                    if (cursor != null && oldest >= parseId(cursor)) break; // без прогресса — защита от петли
                    cursor = Long.toString(oldest);
                }
                reads.markRead(session, company, dialog, List.copyOf(ids));
                return null;
            });
        }
    }

    private static long parseId(String value) {
        try { return value == null ? -1 : Long.parseLong(value.trim()); }
        catch (NumberFormatException notNumeric) { return -1; }
    }

    @Override
    public List<ConnectorConversation> handle(SyncRecentChatsCommand command) {
        var account = accounts.require(command.channelAccountId());
        String company = company(account);
        try (DikidiCredential credential = credentials.obtain(account.id())) {
            // Сессия блокируется на каждый логический шаг, а не на весь обход диалогов:
            // интерактивный CRM/отправка не ждёт освобождения lock'а подолгу (starvation-
            // требование контракта 13.1). listing — один шаг; далее каждый диалог своим
            // шагом с повторной дешёвой предпосылкой C01 (после возможного re-login).
            var collected = new ArrayList<DikidiChatReads.Dialog>();
            var seenDialogs = new java.util.HashSet<String>();
            Map<String, Integer> unread = Map.of();
            long cursor = 0L;
            // Живой прогон 15.11.2026: обход только первой страницы C02 терял целые диалоги
            // (диалог с вложениями «Карта и чек» не импортировался вовсе — more=true).
            // Курсор time= самый старый date предыдущей страницы; форма курсора протоколом
            // не доказана, поэтому защита: дедуп по id, стоп при нулевом прогрессе, бюджет страниц.
            for (int page = 1; page <= DIALOG_LIST_PAGES; page++) {
                final long pageCursor = cursor;
                final boolean firstPage = page == 1;
                var listing = client.execute(credential, null, session -> {
                    reads.shell(session, company);
                    var result = reads.dialogs(session, company, pageCursor);
                    return firstPage ? new Listing(result, reads.unread(session, company))
                            : new Listing(result, Map.of());
                });
                if (firstPage) unread = listing.unread();
                int added = 0;
                for (var dialog : listing.dialogs().dialogs())
                    if (seenDialogs.add(dialog.id())) { collected.add(dialog); added++; }
                long oldest = listing.dialogs().dialogs().stream()
                        .mapToLong(DikidiChatReads.Dialog::dateEpoch).filter(d -> d > 0).min().orElse(0L);
                if (!listing.dialogs().more() || added == 0 || oldest == 0 || oldest == pageCursor) break;
                cursor = oldest;
                if (page == DIALOG_LIST_PAGES)
                    log.info("DIKIDI dialogs list still has more=true after {} pages: account={}, "
                            + "older dialogs wait for the next pass", DIALOG_LIST_PAGES, account.id());
            }
            var result = new ArrayList<ConnectorConversation>();
            Map<String, Integer> unreadSnapshot = unread;
            for (var dialog : collected) {
                try {
                    var conversation = client.execute(credential, null,
                            session -> conversation(session, account, company, dialog, unreadSnapshot));
                    if (conversation != null) result.add(conversation);
                } catch (DikidiFailure failure) {
                    // Живой прогон 15.11.2026: один неразобранный диалог (дрейфующая форма C04)
                    // ронял весь recent-chats-прогон в DLQ — ни один хороший диалог не создавался.
                    // Форма ответов DIKIDI документирована как дрейфующая, поэтому отказ одного
                    // диалога пропускает его с логом; потеря доступа — сессийный отказ, он честен
                    // для всего прогона (клиент уже выполнил само-излечение ровно один раз).
                    if (failure.code() == DikidiFailure.Code.AUTH_REQUIRED) throw failure;
                    log.warn("DIKIDI dialog skipped on failure {}: account={}, dialog={}",
                            failure.code(), account.id(), dialog.id());
                }
            }
            return List.copyOf(result);
        }
    }

    private record Listing(DikidiChatReads.DialogsPage dialogs, Map<String, Integer> unread) {}

    /** Один lock-hop: участники, пробник, классификация, LIVE-пасс и карточка диалога. */
    private ConnectorConversation conversation(DikidiClient.Session session, HostChannelAccountSnapshot account,
            String company, DikidiChatReads.Dialog dialog, Map<String, Integer> unread) {
        reads.shell(session, company);
        var participants = reads.participantsDetailed(session, dialog.id(), company);
        var sample = reads.messages(session, dialog.id(), null, 5, company).messages();
        var classified = classify(participants, sample);
        if (classified.isEmpty()) {
            log.info("DIKIDI dialog skipped (client/support not resolvable without guessing): account={}, "
                    + "dialog={}, participants={}", account.id(), dialog.id(), participants.size());
            return null;
        }
        var checkpoint = checkpoints.load(account.id(), dialog.id());
        if (checkpoint.isPresent() && checkpoint.get().initialSyncDone()
                && (dialog.dateEpoch() > checkpoint.get().lastDialogEpoch() || dialog.badge() > 0))
            publishLive(session, account, company, dialog, classified.get(), checkpoint.get());
        var client = classified.get();
        return new ConnectorConversation(account.id(), client.clientId(), client.clientName(), null,
                client.clientName(), dialog.dateEpoch() > 0 ? Instant.ofEpochSecond(dialog.dateEpoch()) : null,
                dialog.lastText().isBlank() ? null : dialog.lastText(),
                unread.getOrDefault(dialog.id(), 0),
                storeIcon(session, company, dialog.icon()),
                dialog.id());
    }

    /**
     * Иконка диалога C02 (аватар клиента): сырой удалённый URL в avatarUrl не отдаётся —
     * контракт хоста «avatarUrl = локальный storageKey» (эндпоинты /avatar/content отдают
     * байты из хранилища; URL доходит до Paths и роняет запрос InvalidPathException).
     * Скачивание в профиле MEDIA; любое отсутствие/сбой — null (аватар заменяется
     * инициалами, карточку диалога не ломает; повтор дойдёт следующим тиком).
     */
    private String storeIcon(DikidiClient.Session session, String company, String icon) {
        if (attachments == null || icon == null || !icon.startsWith("https://")) return null;
        try {
            byte[] content = reads.media(session, icon, company);
            try (var input = new java.io.ByteArrayInputStream(content)) {
                return attachments.store(input, attachmentFileName(icon), content.length,
                        "image/jpeg").storageKey();
            }
        } catch (java.io.IOException | RuntimeException failure) {
            log.debug("DIKIDI dialog icon not stored (avatar stays empty): {}", failure.toString());
            return null;
        }
    }

    /**
     * LIVE-пасс: свежие сообщения новее курсора публикуются с origin LIVE
     * (только они порождают персональные уведомления — PersonalNotificationWriter отсекает
     * HISTORY_IMPORT/SYNC). C05 отдаёт страницы от новых к старым, поэтому до курсора
     * дошагивают last-курсором: одна первая страница ≥20 свежих не теряет старых свежих.
     * Курсор сохраняется после полной передачи страницы; падение между публикацией и
     * сохранением даёт повтор, который гасится дедупом сообщений. Если бюджета страниц
     * не хватило до курсора — ничего не публикуется и не двигается (отставание честно
     * переживается явным логом; потери молча не происходит, догрузка — history-путём).
     */
    private void publishLive(DikidiClient.Session session, HostChannelAccountSnapshot account, String company,
            DikidiChatReads.Dialog dialog, Classified client, ConversationCheckpoint checkpoint) {
        String cursor = checkpoint.newestMessageId();
        var news = new ArrayList<DikidiChatReads.Message>();
        var page = reads.messages(session, dialog.id(), null, DikidiChatReads.MAX_PAGE, company);
        int pages = 1;
        boolean reachedCursor = false;
        while (true) {
            boolean tailOlder = false;
            for (var message : page.messages()) {
                if (cursor != null && Long.parseLong(message.id()) <= Long.parseLong(cursor)) tailOlder = true;
                else if (!message.text().isBlank() || !message.attachments().isEmpty()) news.add(message);
            }
            if (tailOlder || page.messages().isEmpty()) { reachedCursor = true; break; }
            if (pages >= initialBudgetPages) break;
            page = reads.messages(session, dialog.id(), minId(page.messages()), DikidiChatReads.MAX_PAGE, company);
            pages++;
        }
        if (!reachedCursor) {
            log.warn("DIKIDI live backlog exceeds per-pass budget; nothing published, cursor unchanged "
                    + "(deferred to history path): account={}, dialog={}, pages={}",
                    account.id(), dialog.id(), pages);
            return;
        }
        news.sort(Comparator.comparingLong(message -> Long.parseLong(message.id())));
        for (var message : news) {
            var stored = storeAttachments(session, company, message);
            if (message.text().isBlank() && stored.isEmpty()) {
                log.warn("DIKIDI attachment-only live message without downloadable content skipped: dialog={}, message={}",
                        dialog.id(), message.id());
                continue;
            }
            inbound.publish(toInbound(account, client.clientId(), client.clientName(), dialog.id(), message,
                    EventOrigin.LIVE, stored));
        }
        if (!news.isEmpty()) {
            String newest = news.get(news.size() - 1).id();
            long epoch = Math.max(checkpoint.lastDialogEpoch(),
                    news.stream().mapToLong(DikidiChatReads.Message::epoch).max().orElse(0));
            checkpoints.save(account.id(), dialog.id(), new ConversationCheckpoint(newest,
                    checkpoint.oldestLoadedId(), true, checkpoint.historyStartReached(), epoch));
            log.info("DIKIDI live messages published: account={}, dialog={}, count={}",
                    account.id(), dialog.id(), news.size());
        } else {
            checkpoints.save(account.id(), dialog.id(), new ConversationCheckpoint(cursor,
                    checkpoint.oldestLoadedId(), true, checkpoint.historyStartReached(), dialog.dateEpoch()));
        }
    }

    @Override
    public SyncConversationHistoryResult handle(SyncConversationHistoryCommand command) {
        var account = accounts.require(command.channelAccountId());
        String company = company(account);
        try (DikidiCredential credential = credentials.obtain(account.id())) {
            return client.execute(credential, null, session -> history(session, account, company, command));
        }
    }

    private SyncConversationHistoryResult history(DikidiClient.Session session, HostChannelAccountSnapshot account,
            String company, SyncConversationHistoryCommand command) {
        reads.shell(session, company);
        String dialogId = command.dialogExternalId() != null ? command.dialogExternalId()
                : resolveDialog(session, company, command.conversationExternalId());
        var existing = checkpoints.load(account.id(), dialogId);
        // Курсор: явный before из durable-цепочки сильнее внутреннего; без него — продолжение
        // незавершённого initial импорта от самого старого загруженного сообщения.
        String cursor = command.beforeExternalId();
        boolean resuming = false;
        if (cursor == null && existing.isPresent() && !existing.get().initialSyncDone()
                && existing.get().oldestLoadedId() != null) {
            cursor = existing.get().oldestLoadedId();
            resuming = true;
        }
        var collected = new ArrayList<ConnectorInboundMessage>();
        String newest = existing.map(ConversationCheckpoint::newestMessageId).orElse(null);
        String oldest = existing.map(ConversationCheckpoint::oldestLoadedId).orElse(null);
        var page = reads.messages(session, dialogId, cursor, command.limit(), company);
        boolean startReached = page.historyStartReached();
        collect(collected, session, account, company, command.conversationExternalId(), dialogId, page.messages());
        if (!page.messages().isEmpty()) {
            newest = larger(newest, maxId(page.messages()));
            oldest = oldest == null ? minId(page.messages()) : smaller(oldest, minId(page.messages()));
        }
        int pages = 1;
        // Явный before (durable-цепочка HistorySyncService) = ровно одна страница: операции
        // нужна предсказуемая единица работы. Глубина допустима только начальному импорту.
        while (command.beforeExternalId() == null && !startReached && pages < initialBudgetPages && !page.messages().isEmpty()) {
            page = reads.messages(session, dialogId, minId(page.messages()), DikidiChatReads.MAX_PAGE, company);
            pages++;
            startReached = page.historyStartReached();
            collect(collected, session, account, company, command.conversationExternalId(), dialogId, page.messages());
            if (!page.messages().isEmpty()) oldest = oldest == null ? minId(page.messages())
                    : smaller(oldest, minId(page.messages()));
        }
        boolean initialDone = startReached || existing.map(ConversationCheckpoint::initialSyncDone).orElse(false);
        String endReason = startReached ? "HISTORY_START_REACHED"
                : (pages >= initialBudgetPages ? "BUDGET_PARTIAL" : "PAGE_COMPLETE");
        long epoch = collected.isEmpty() ? existing.map(ConversationCheckpoint::lastDialogEpoch).orElse(0L)
                : collected.stream().mapToLong(m -> m.sentAt().getEpochSecond()).max().orElse(0);
        checkpoints.save(account.id(), dialogId, new ConversationCheckpoint(newest, oldest, initialDone,
                startReached || existing.map(ConversationCheckpoint::historyStartReached).orElse(false), epoch));
        if (resuming) log.info("DIKIDI initial import continued: account={}, dialog={}, pages={}, collected={}",
                account.id(), dialogId, pages, collected.size());
        collected.sort(Comparator.comparing(ConnectorInboundMessage::sentAt)
                .thenComparing(m -> Long.parseLong(m.externalId())));
        return new SyncConversationHistoryResult(List.copyOf(collected), startReached, endReason);
    }

    /** legacy-путь без ссылки диалога: резолв по списку и участникам (дорого, только совместимость). */
    private String resolveDialog(DikidiClient.Session session, String company, String clientExternalId) {
        long cursor = 0L;
        var seen = new java.util.HashSet<String>();
        for (int page = 1; page <= DIALOG_LIST_PAGES; page++) {
            var dialogsPage = reads.dialogs(session, company, cursor);
            int added = 0;
            for (var dialog : dialogsPage.dialogs()) {
                if (!seen.add(dialog.id())) continue;
                added++;
                var participants = reads.participantsDetailed(session, dialog.id(), company);
                var me = clientExternalId == null ? null : participants.get(clientExternalId);
                if (me == null) continue;
                // Тот же контракт, что у classify: роли читаются — клиент по role=user;
                // нет — прежний признак двустороннего диалога (ровно два участника).
                boolean rolesKnown = participants.values().stream().allMatch(p -> p.role() != null);
                if (rolesKnown ? "user".equals(me.role()) : participants.size() == 2) return dialog.id();
            }
            long oldest = dialogsPage.dialogs().stream()
                    .mapToLong(DikidiChatReads.Dialog::dateEpoch).filter(d -> d > 0).min().orElse(0L);
            if (!dialogsPage.more() || added == 0 || oldest == 0 || oldest == cursor) break;
            cursor = oldest;
        }
        throw new IllegalStateException("DIKIDI dialog not found for counterpart: " + clientExternalId);
    }

    /**
     * Классификация без догадок. Живой прогон 15.11.2026 уточнил модель: в одном клиентском
     * диалоге может быть НЕСКОЛЬКО сотрудников салона (каждый пишет out=1, role=support —
     * два разных сотрудника салона), клиент — автор входящих (out=0). Прежний признак
     * «ровно два участника» такие диалоги терял.
     * Доказательства: автор входящих единственный (два разных входящих автора —
     * неподтверждённая множественность, пропуск). Если роли читаются у всех участников
     * (живой прогон 15.11.2026), клиент — единственный участник с role=user; при этом
     * сотрудник DIKIDI во входящих (role≠user) — не клиент (служебный диалог платформы).
     * Если роли не читаются (дрейф 13.11.2026) — прежние доказательства двустороннего
     * диалога: ровно два участника, клиент — не автор out=1, а при отсутствии out —
     * единственный автор входящих.
     */
    static Optional<Classified> classify(Map<String, DikidiChatReads.Participant> participants,
            List<DikidiChatReads.Message> sample) {
        if (participants.isEmpty()) return Optional.empty();
        String client = null;
        String owner = null;
        for (var message : sample) {
            if (!participants.containsKey(message.userId())) continue;
            if (message.out()) { if (owner == null) owner = message.userId(); continue; }
            if (client != null && !client.equals(message.userId())) return Optional.empty();
            client = message.userId();
        }
        String classified;
        if (participants.values().stream().allMatch(p -> p.role() != null)) {
            if (client == null) {
                var sole = participants.entrySet().stream()
                        .filter(e -> "user".equals(e.getValue().role())).map(Map.Entry::getKey).toList();
                if (sole.size() != 1) return Optional.empty();
                classified = sole.get(0);
            } else {
                if (!"user".equals(participants.get(client).role())) return Optional.empty();
                if (participants.values().stream().filter(p -> "user".equals(p.role())).count() != 1)
                    return Optional.empty();
                classified = client;
            }
        } else {
            if (participants.size() != 2) return Optional.empty();
            var ids = List.copyOf(participants.keySet());
            if (owner != null && !owner.equals(client)) classified = ids.get(0).equals(owner) ? ids.get(1) : ids.get(0);
            else if (client != null && !client.equals(owner)) classified = client;
            else return Optional.empty();
        }
        String classifiedOwner = owner != null && !owner.equals(classified) ? owner : otherOf(participants, classified);
        if (classifiedOwner == null) return Optional.empty(); // одностронний состав — не диалог салона с клиентом
        return Optional.of(new Classified(classified, participants.get(classified).name(), classifiedOwner));
    }

    private static String otherOf(Map<String, DikidiChatReads.Participant> participants, String exceptId) {
        for (var id : participants.keySet()) if (!id.equals(exceptId)) return id;
        return null;
    }
    record Classified(String clientId, String clientName, String ownerUserId) {}

    /** Начальный/постраничный импорт: origin HISTORY_IMPORT (хост всё равно форсирует его). */
    private void collect(List<ConnectorInboundMessage> target, DikidiClient.Session session,
            HostChannelAccountSnapshot account, String company,
            String clientExternalId, String dialogId, List<DikidiChatReads.Message> messages) {
        for (var message : messages) {
            if (message.text().isBlank() && message.attachments().isEmpty()) continue; // пустота — не сообщение
            if (!message.out() && clientExternalId != null && !clientExternalId.equals(message.userId()))
                continue; // входящий автор вне клиента диалога — не приписываем по догадке
            var stored = storeAttachments(session, company, message);
            if (message.text().isBlank() && stored.isEmpty()) {
                log.warn("DIKIDI attachment-only message without downloadable content skipped: dialog={}, message={}",
                        dialogId, message.id());
                continue;
            }
            target.add(toInbound(account, clientExternalId, null, dialogId, message, EventOrigin.HISTORY_IMPORT, stored));
        }
    }

    /**
     * Вложения joining: «image» скачивается с CDN (MEDIA-профиль) и кладётся в
     * хост-хранилище — тот же контракт хранения, что у telegram-вложений.
     * Преходящие сбои (auth/лимит/временные) пробиваются наверх: страница не
     * передаётся, чекпойнт стоит, диалог дойдёт повтором. Постоянные (403/битый
     * url) — вложение честно теряется с логом, текст сообщения не гибнет.
     */
    private List<kit.penny.clientbus.connector.api.model.ConnectorMessageAttachment> storeAttachments(
            DikidiClient.Session session, String company, DikidiChatReads.Message message) {
        if (attachments == null || message.attachments().isEmpty()) return List.of();
        var stored = new ArrayList<kit.penny.clientbus.connector.api.model.ConnectorMessageAttachment>();
        for (var attachment : message.attachments()) {
            if (!"image".equals(attachment.kind()) || attachment.url() == null)
                continue; // иные виды joining протоколом не подтверждены (13.6)
            final byte[] content;
            try {
                content = reads.media(session, attachment.url(), company);
            } catch (DikidiFailure failure) {
                if (failure.code() == DikidiFailure.Code.AUTH_REQUIRED
                        || failure.code() == DikidiFailure.Code.TEMPORARY_FAILURE
                        || failure.code() == DikidiFailure.Code.RATE_LIMITED
                        || failure.code() == DikidiFailure.Code.CONNECTION_BUSY) throw failure;
                log.warn("DIKIDI attachment skipped (permanent): message={}, kind={}, code={}",
                        message.id(), attachment.kind(), failure.code());
                continue;
            }
            try (var input = new java.io.ByteArrayInputStream(content)) {
                var saved = attachments.store(input, attachmentFileName(attachment.url()), content.length, "image/jpeg");
                stored.add(new kit.penny.clientbus.connector.api.model.ConnectorMessageAttachment(
                        kit.penny.clientbus.common.enums.MessageAttachmentType.IMAGE,
                        saved.storageKey(), saved.fileName(), saved.contentType(), saved.size()));
            } catch (java.io.IOException | RuntimeException failure) {
                log.warn("DIKIDI attachment storage failed, message kept without it: message={}, cause={}",
                        message.id(), failure.toString());
            }
        }
        return List.copyOf(stored);
    }

    private static String attachmentFileName(String url) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        int query = name.indexOf('?');
        return query >= 0 ? name.substring(0, query) : name;
    }

    /**
     * Направление: out=1 → senderExternalId = externalId аккаунта (хост определяет
     * исходящие-эхо сравнением с externalId канала). Фактический автор всегда уходит в
     * authorExternalId (15.11.2026): на стороне салона несколько сотрудников, хост по
     * маппингу мастеров превращает его в employeeId — без маппинга и у супер-админа
     * остаётся NULL. Тип: есть скачанное вложение — IMAGE (правило подтверждено живыми
     * Telegram-строками: картинка с подписью «Ку-ку» = IMAGE; дефект 15.11.2026:
     * text.isBlank() давал TEXT для «текст+фото», и диалог не показывал файлы),
     * иначе TEXT.
     */
    private static ConnectorInboundMessage toInbound(HostChannelAccountSnapshot account, String clientExternalId,
            String clientName, String dialogId, DikidiChatReads.Message message, EventOrigin origin,
            List<kit.penny.clientbus.connector.api.model.ConnectorMessageAttachment> stored) {
        return new ConnectorInboundMessage(account.id(), clientExternalId, clientName, null, clientName,
                message.out() ? account.externalId() : message.userId(), message.id(),
                stored.isEmpty() ? MessageType.TEXT : MessageType.IMAGE,
                message.text(), "{\"dikidi\":{\"senderUserId\":\"" + message.userId() + "\",\"out\":"
                        + message.out() + "}}",
                Instant.ofEpochSecond(message.epoch()), stored, origin, dialogId, message.userId(),
                // 13.6 отчёт о доставке: веб-клиент показывает «не прочитано» у исходящих
                // по флагам read — read>0 значит «собеседник прочитал»; входящие не имеют
                // receipt-семантики (null), прочтение не подтверждённое не понижается (FALSE).
                message.out() ? (message.read() > 0 ? Boolean.TRUE : Boolean.FALSE) : null);
    }

    private static String company(HostChannelAccountSnapshot account) {
        if (account.externalId() == null || account.externalId().isBlank())
            throw new IllegalStateException("DIKIDI channel account has no company externalId: " + account.id());
        return account.externalId();
    }

    private static String maxId(List<DikidiChatReads.Message> messages) {
        return messages.stream().map(DikidiChatReads.Message::id)
                .max(Comparator.comparingLong(Long::parseLong)).orElse(null);
    }
    private static String minId(List<DikidiChatReads.Message> messages) {
        return messages.stream().map(DikidiChatReads.Message::id)
                .min(Comparator.comparingLong(Long::parseLong)).orElse(null);
    }
    private static String smaller(String current, String candidate) {
        if (candidate == null) return current;
        return Long.parseLong(current) <= Long.parseLong(candidate) ? current : candidate;
    }
    private static String larger(String current, String candidate) {
        if (candidate == null) return current;
        return current == null || Long.parseLong(current) < Long.parseLong(candidate) ? candidate : current;
    }
}
