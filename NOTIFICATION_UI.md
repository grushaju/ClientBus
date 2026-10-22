# Stage 11.6 — UI lifecycle и reconciliation

План с учётом пяти замечаний ревью согласован пользователем 22.10.2026. Основание: [Stage 11](<Stage 11.txt>), [семантика E24–E29/E38–E40, T11/T13/T21](NOTIFICATION_SEMANTICS.md), [контракты](NOTIFICATION_CONTRACTS.md).

## 1. Граница реализации

Глобальный provider остаётся внутри authenticated layout. REST определяет состояние; SSE передаёт invalidation, а не готовые бизнес-DTO. Реализован lifecycle браузерного клиента и ресурсов Inbox. Новых BE endpoints, миграций и коммитов нет. Telegram остаётся обычным IChannelConnector.

Производители остальных событий добавляются в 11.9; наличие подписки не означает, что соответствующий writer уже публикует событие. Центр уведомлений/mobile push относятся к 11.8, сохраняемые history operations — к 11.10. Политика fallback, HTTP-бюджет, эксплуатационные пределы и редкая контрольная сверка реализованы в согласованном 11.7: [NOTIFICATION_RECOVERY.md](NOTIFICATION_RECOVERY.md).

## 2. Владение сессией и запросами

- AuthProvider хранит поколение сессии и AbortController. Logout, смена аккаунта и событие storage отменяют прежнего владельца. Результат профиля, memberships и организации применяется только при совпадении владельца и текущих credentials. Старая ошибка не очищает credentials нового аккаунта; поздний ответ login после logout не авторизует клиента.
- refreshCurrentUser объединяет одновременные чтения одного поколения identity. access/identity control и SSE 403 увеличивают поколение, отменяют также ручной запрос профиля/каталога Workspace и исключают его из single-flight. Следующая сверка получает новый снимок; завершение старого Promise не удаляет новый. Сигнал проходит через fetch и разбор тела ответа; общий deadline ограничивает также адаптер, который не реагирует на отмену. Перед вторым этапом загрузки и применением результата проверяются владелец и поколение.
- WorkspaceProvider изолирует каталог по сессии/сотруднику. Поздний каталог аккаунта A не выбирает Workspace аккаунта B. Фоновое обновление сохраняет более новый явный выбор, если этот Workspace ещё доступен. Удалённый/отозванный Workspace исключается после REST-подтверждения.
- UiScopeContext содержит непрозрачного владельца, текущий Workspace и версию пересмотра доступа. Токен не входит в resource keys и URL. Смена аккаунта, организации, роли, Workspace или supervisor текущего Workspace создаёт новую область. A → B → A не возобновляет первого владельца.
- useUiLifetime охватывает начальные чтения, polling, ручное обновление и применение результатов команд. Смена области отменяет старые чтения; их ответы не меняют новый экран. Результат завершённой команды может обновить прежний список после ухода из диалога только пока жива та же разрешённая область; после отзыва доступа такой DTO не публикуется.
- Явный выбор другого Workspace в AppLayout закрывает маршрут прежнего диалога. Deep link по-прежнему разрешает Workspace через доступный Conversation DTO; поздний ответ старого владельца не меняет выбор.

StrictMode cleanup/setup отменяет прежние запросы и допускает новые. Завершение старого Promise не удаляет новый single-flight и не сбрасывает его pending.

## 3. Отзыв доступа и авторизация

access.invalidated и identity.invalidated обрабатываются сразу, в том числе до окончания ready reconciliation. Для matching ресурсов coordinator выполняет revoke: отменяет активное чтение, увеличивает поколение, очищает закрытые данные и оставляет необходимость повторного REST-чтения. Контрольный сигнал не ожидает освобождения очереди обычных событий.

Conversation control сопоставляется с conversationId, Workspace control — с Workspace. Неопределённый Workspace ещё не разрешённого deep link обрабатывается консервативно. Отзыв другого известного Workspace не очищает текущий Inbox. Скрытая переписка также подписана на очистку, хотя обычные refetch для неё приостановлены.

SSE 401 завершает локальную авторизацию. SSE 403 очищает текущие ресурсы и запускает пересмотр identity/доступных Workspace. Смена credentials завершает старый stream и синхронизирует AuthContext. REST 401 текущего владельца вызывает тот же локальный logout и при выключенном SSE; неуспешный login и поздний 401 прежних credentials не завершают новую сессию. Внутренний сигнал истечения не содержит токена.

Подтверждённые 403/404 списка/диалога скрывают закрытые данные. Отказ optional account/channel details очищает соответствующие сведения и показывает ошибку без бесконечного разрыва SSE; transient failure сохраняет необходимость повторного чтения. Серверная ACL остаётся обязательной. Глобальный отзыв JWT/logout-all не заявляется: это local logout, expiry и актуальная серверная policy по G07.

## 4. Подписанные ресурсы

| Ресурс | Обновление |
|---|---|
| Identity, memberships, организация, доступные Workspace | ready/resync/visibility и identity/access/workspace/employees invalidation |
| Inbox query | inbox/messages invalidation, локальный conversation-updated; область — Workspace и параметры запроса |
| Сообщения текущего диалога | messages invalidation, ready/resync/возврат; дочитывание до прежней границы, обновление старых outgoing statuses |
| Метаданные текущего диалога, client account, канал, assignment selector | conversation/inbox/messages/clients/channels/employees/workspace invalidation и ручные команды |
| Профиль клиента, его телефоны/аватар и другие аккаунты | независимые ресурсы по clientId; clients invalidation, ready/resync/возврат и ручной retry, с отменой при отзыве доступа |
| Личные группы | groups invalidation, ready/resync/возврат |
| SYSTEM/PRIVATE сохранённые фильтры | filters invalidation, ready/resync/возврат; независимые чтения и ошибки каталогов |

useUiResource объединяет initial/manual/SSE чтения одного смонтированного каталога. singleFlightRead сериализует metadata GET диалога. Подписки принадлежат жизненному циклу смонтированного query; общий кэш всех страниц этим этапом не вводится.

## 5. Coalescing, ошибки и локальное состояние

Coordinator хранит revision/completed. Обычная invalidation не отменяет разрешённый ответ: после него выполняется один следующий ограниченный refetch. Ошибка сохраняет dirty. Ready ждёт требуемую ревизию, а не бесконечное окончание непрерывного потока событий. Стартовые интервалы: coalescing 100 ms, минимум 250 ms между обычными запусками, retry 1 s; настройка нагрузки относится к 11.7.

Локальные conversation-updated (read/send/assignment/client relationship) используют тот же dirty-механизм. Они не отменяют Inbox-запрос внутри ready и не заставляют SSE переподключаться из-за собственного обновления. Этот конфликт был найден настоящим desktop E2E и закрыт отдельной React-регрессией.

Скрытый мобильный список/переписка сохраняют dirty без обычного SSE-refetch. При возвращении подписка возобновляет нужное чтение. Существующее поведение начальной загрузки сохранено. Отзыв доступа очищает данные и в скрытом представлении.

Списки не размонтируются при обычном обновлении. Черновик composer, ручная прокрутка, локальные фильтры и формы сохраняются в разрешённой области. Изменения групп/фильтров, завершённые во время GET, объединяются с ответом через journal/tombstones. Поздний GET не воскрешает удалённый фильтр/группу и не стирает собственную команду. Версии локально принятых сообщений защищают send/retry от старого REST-ответа. Очистка закрытой области имеет приоритет над сохранением локальных данных.

Зависимость диалога от memberships ограничена его Workspace: изменение supervisor в другом Workspace не запускает полную загрузку и не сбрасывает composer/прокрутку. Invalidation ответственного остаётся незавершённой до окончания локальной assignment-команды, затем получает свежий REST-снимок. После конфликта команды выполняется новый GET даже при наличии более раннего чтения. Обновления профиля клиента и других аккаунтов не закрывают открытый picker; версия локальной привязки защищает её от более старого списка.

## 6. Проверки

- Полный Node/React набор: 232 passed. Включает 25 lifecycle-тестов с настоящими provider/context/hooks, регрессии Inbox/переписки, непрерывные invalidation, сохранение dirty/ошибок, shared-read cancellation и немедленный control до ready.
- Проверены поздние success/failure профиля A после login B, отмена login при logout, native storage event, REST 401 старого/текущего владельца, SSE 401/403, Workspace A → B → A, поздний каталог A, более новый явный выбор Workspace и control другого Workspace.
- Настоящие ConversationView/MessageComposer/MessageList проверены вместе с SSE-подписками: черновик и ручная прокрутка сохраняются. Initial/ready обновление групп и фильтров не стирает завершённые локальные команды.
- TypeScript приложения и production Vite build прошли. Проверка E2E types выполняется отдельно.
- Browser acceptance запускается через NotificationBrowserAcceptance на отдельных PostgreSQL/Kafka. Два сценария выполняются на desktop/mobile: реальная доставка двум сессиям/ACL/rollback/dedup/reconnect, а также смена аккаунта и storage logout между вкладками. Проверка мобильного списка выполняется после возврата к нему; черновик сохраняется. Прогон после исправлений ревью: 4 passed (22.4 s), NotificationBrowserAcceptance — 1 passed, Maven BUILD SUCCESS 22.10.2026 14:23:34 +03:00.

Команды:

```powershell
node --test ui/tests
node ui/node_modules/typescript/bin/tsc --project ui/tsconfig.app.json --incremental false
node ui/node_modules/typescript/bin/tsc --project ui/e2e/tsconfig.json
# Vite запускать из ui после проверки TypeScript:
node node_modules/vite/bin/vite.js build
mvn -o -pl server -am '-Dtest=NotificationBrowserAcceptance' '-Dsurefire.failIfNoSpecifiedTests=false' test-compile surefire:test
```

Логи текущей проверки: server/target/notification-11-6-fixes-ui.log, notification-11-6-fixes-browser-maven.log, notification-browser.log. Тестовый manifest с credentials удаляется harness в finally. Диагностика тестового SSE содержит только Workspace, HTTP status и названия event types.

## 7. Исправления повторного ревью 11.6

Все четыре замечания исправлены в коде 22.10.2026. Добавлены 11 регрессий:

- Ручной identity GET до access/identity control или SSE 403 отменяется; старое завершение с 401 не возвращает прежнюю роль и не завершает актуальную сессию. Отдельно проверен ручной каталог Workspace до отзыва membership.
- Изменение прав другого Workspace сохраняет тот же textarea, черновик и ручную прокрутку без лишнего GET диалога.
- Invalidation во время успешной/конфликтующей assignment-команды дожидается её окончания и читает актуального ответственного. Отдельная регрессия проверяет конфликт без SSE при более раннем ручном GET.
- clients.invalidated, ready и resync обновляют профиль, телефоны, аватар и другие аккаунты при прежнем clientId, сохраняя picker. Проверены пересечение initial/ready с локальной привязкой и отмена старых чтений при отзыве доступа.

Итог: 11.6 реализован по согласованному плану с учётом ревью. Флаги BE сохраняют прежние defaults. Уточнённый план 11.7 согласован пользователем; реализация и приёмка описаны в [NOTIFICATION_RECOVERY.md](NOTIFICATION_RECOVERY.md). Этап 11.8 требует отдельного согласования плана.
