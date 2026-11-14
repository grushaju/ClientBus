-- 13.2b: внешняя ссылка диалога (identity of dialog, not just counterpart).
-- Снимает жёсткое 1:1 «один диалог на участника» для каналов с множеством диалогов
-- на клиента (DIKIDI). UUID диалогов и все ссылки (message, historysyncoperation,
-- groupmembership) не изменяются.
--
-- Hibernate ddl-auto не удаляет старое ограничение и не создаёт частичные индексы,
-- поэтому грани перехода фиксируются здесь. Бизнес-схема в новых окружениях
-- создаётся Hibernate ПОСЛЕ Flyway, поэтому каждая ветвь охранена to_regclass:
-- на чистой БД миграция — no-op (канон создаётся сущностями, частичные индексы
-- появляются на существующих БД; поведение индексов покрыто отдельным тестом
-- миграции на копии схемы).

do $$
begin
    if to_regclass('dbo.conversation') is not null then
        alter table dbo.conversation
            add column if not exists dialogexternalid varchar(200);

        comment on column dbo.conversation.dialogexternalid
            is 'Внешняя ссылка диалога платформы (например chatId Telegram); NULL для легаси-строк и каналов, где диалог = участник';

        -- Старое уникальное ограничение 1:1 (могло быть создано Hibernate
        -- как констрейнт или как индекс).
        alter table dbo.conversation
            drop constraint if exists conversation_channel_client_uix;

        drop index if exists conversation_channel_client_uix;

        -- Новый канон: диалог уникален по внешней ссылке в пределах аккаунта канала.
        create unique index if not exists conversation_channel_client_dialog_uix
            on dbo.conversation (channelaccountid, dialogexternalid)
            where dialogexternalid is not null;

        -- Легаси-строки без ссылки: по-прежнему не более одного диалога на участника,
        -- пока самолечение (первый входящий со ссылкой) не проставит dialogexternalid.
        create unique index if not exists conversation_channel_client_nodialog_uix
            on dbo.conversation (channelaccountid, clientaccountid)
            where dialogexternalid is null;

        create index if not exists conversation_channel_dialog_idx
            on dbo.conversation (channelaccountid, dialogexternalid);
    end if;
end $$;

-- Журнал корреляции раннего результата отправки: слот корреляции — ссылка диалога,
-- а при её отсутствии id участника (coalesce); pending id участника в разных диалогах
-- может повторяться, поэтому слот входит в ключ. Платформенное событие может знать
-- только chatId диалога, а попытка регистрации — id участника; предполагать их равными
-- больше нельзя.

do $$
begin
    if to_regclass('dbo.outboundsendcallback') is not null then
        alter table dbo.outboundsendcallback
            add column if not exists dialogexternalid varchar(256);

        alter table dbo.outboundsendcallback
            drop constraint if exists outboundcallbackkeyuq;

        drop index if exists outboundcallbackkeyuq;

        create unique index if not exists outboundsendcallback_slot_uix
            on dbo.outboundsendcallback (channelaccountid,
                coalesce(dialogexternalid, recipientexternalid), pendingexternalid);
    end if;
end $$;
