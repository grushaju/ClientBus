# Stage 11.3 — Общая policy доступа

План согласован пользователем 21.10.2026. Этап реализован. Основание: [семантика](NOTIFICATION_SEMANTICS.md), [контракты](NOTIFICATION_CONTRACTS.md).

## Реализация

- [AccessSubject / AccessPolicy](server/src/main/java/kit/penny/clientbus/server/security/policy): явный employeeId; роль, enabled, Organization, membership, supervisor и assignment проверяются scalar SQL по текущей БД. AccessSubject создаётся доверенным BE, не из request body. Проверка не использует JPA identity map, principal roles или Kafka cache.
- Один набор предикатов для Workspace, conversation, channel/account, личных групп, фильтров, каталога сотрудников, notification, history operation и installation. SUPER_ADMIN не получает чужие персональные записи. Межорганизационная membership не расширяет доступ.
- `allowedRecipients` проверяет до 500 кандидатов одним запросом; gateway должен делить большие наборы на batch. `audience` предназначен для исходного решения producer, не для хранения разрешений сессии.
- [NotificationAudienceResolver](server/src/main/java/kit/penny/clientbus/server/notification/NotificationAudienceResolver.java): N1 без self-assignment, N2 только доступному ответственному, N3 ответственный либо supervisor/admin, N4 администраторы канала, N5 только инициатор с доступом. Writer должен удерживать domain lock и сохранить первоначальное решение, включая пустое, в своей транзакции.
- [NotificationRoutingPolicy](server/src/main/java/kit/penny/clientbus/server/notification/NotificationRoutingPolicy.java): персональная аудитория проверяется отдельно от текущей ACL. WORKSPACE/ORGANIZATION дают общий resync без resource ID; подробный conversation-сигнал требует C. FORMER_SCOPE даёт только `access.invalidated` без workspace/resource/correlation, даже после удаления target или membership. Блокированный, чужой или просроченный subject не получает сигнал.
- [InstallationAccessPolicy](server/src/main/java/kit/penny/clientbus/server/notification/InstallationAccessPolicy.java): proof сравнивается constant-time по SHA-256; update/delete требуют текущего owner; явный rebind допускается с proof и точной registrationVersion. Проверка не заменяет CAS в writer. Поздний запрос получает конфликт, без автоматического переноса на новую версию.
- `allowsPush` повторяет текущую target ACL и проверяет recipient/org/binding/endpoint, permission, activity, readAt, alertInvalidatedAt и TTL. Worker дополнительно обязан удерживать актуальный claim и применить результат условным UPDATE.

## Подключённые HTTP-границы

`CurrentUserService.requireWorkspaceAccess/requireConversationAccess` делегируют общую ACL в policy, без повторной проверки cached JPA assignment или principal role. Отдельные ограничения бизнес-команд остаются в соответствующих сервисах. Это не меняет исходные правила диалогов этапа 10. `ChannelService` использует общие проверки для чтения/управления/создания. `OrganizationService.getOrganization(id)` проверяет именно запрошенный id.

Существующий HTTP flow подключения вызывает общую policy до start/status/code/password/email/enable/disable/disconnect; find сохраняет обычное право чтения канала. Notification не добавляет Telegram scopes/capabilities. Внутренние connector startup/callback не зависят от HttpAccessPolicy.

JWT filter сохраняет проверенный срок действия в authentication details. `HttpAccessPolicy.sessionSubject()` переносит его в явный subject для будущего gateway, не сохраняя raw token. Каждый policy-вызов проверяет срок; gateway 11.4 должен дополнительно завершать соединение по expiry и повторно проверять доступ при отправке. Recipient subject без expiry предназначен для фоновой доставки, а не SSE-сессии.

## Границы и дальнейшее подключение

Policy выполняет запрос в текущей транзакции; для отправки требуются короткие транзакции с актуальным READ COMMITTED snapshot. Изменения JPA, которые должны быть видны JDBC-проверке внутри того же writer, нужно flush до проверки. Результат проверки нельзя кэшировать как разрешение будущей отправки. Отказ БД распространяется как ошибка и не разрешает fallback по кэшу; gateway/worker обязаны закрыть выдачу и обработать недоступность.

Общий `NOTIFICATION_PREDICATE` использует aliases n/e/u и должен применяться вместе с проверкой активного subject, одинаково в list/count/read/read-all. Endpoints центра и условные SQL mutations появятся в 11.8. В этом этапе не созданы SSE-соединения, Kafka consumers, установка push endpoints или доставка. Policy и маршрутизация подготовлены и проверены для их подключения в соответствующих этапах.

Очистка UI-состояния после сигнала проверяется при реализации клиента. Текущие проверки подтверждают минимальный безопасный payload, а не уже работающий браузерный flow.

## Проверки

До повторного ревью прошли 56 BE-тестов: AccessPolicyIntegrationTest (12), AccessPolicyFailureTest (2), TelegramAuthorizationControllerTest (6), CurrentUserServiceTest (19), ChannelServiceTest (4), ChannelServiceIntegrationTest (2), JwtServiceTest (6), MessageInboundFlowIntegrationTest (3), ConversationApiIntegrationTest (2).

Интеграционные проверки используют реальные PostgreSQL/Kafka через Testcontainers и Hibernate update. Проверены смена назначения при устаревшем managed entity, отзыв membership, блокировка/смена роли, личные объекты, истечение сессии, cleanup, batch ACL, аудитории, proof/rebind/version и push eligibility. Ошибка БД отдельно проверена как отказ выдачи. UI не изменялся. Миграции и коммиты не создавались.

## Исправления повторного ревью 11.3

Неизвестный type или schemaVersion при распознанной и разрешённой аудитории преобразуется в минимальный stream.resync-required версии 1; закрытый conversation, чужая Organization/персональная аудитория и истёкшая сессия не получают fallback. FORMER_SCOPE сохраняет access.invalidated. Идентификаторы resource/workspace/correlation не переносятся. Ограничение частоты и объединение resync выполняются gateway/client на следующих этапах. Нераспознанный routing envelope не считается разрешением.

InstallationAccessPolicy поддерживает CREATE для конфликта уже существующего installationId с expectedVersion=0. Создание отсутствующей строки выполняет будущий atomic writer; при конфликте уникальности он вызывает этот путь проверки proof. VersionConflict содержит только currentVersion и готовый quoted ETag; endpoint 11.8 должен отобразить его в 409 INSTALLATION_CONFLICT + ETag. Неверный proof не получает версию. UPDATE/DELETE сохраняют проверку owner; CREATE не разрешает mutation существующей строки. Клиент использует полученную версию только для нового действия актуальной сессии.

После исправлений повторно прошёл 41 тест: AccessPolicyIntegrationTest (14), AccessPolicyFailureTest (2), CurrentUserServiceTest (11), TelegramAuthorizationControllerTest (6), ChannelServiceTest (4), ChannelServiceIntegrationTest (2), ConversationApiIntegrationTest (2). Unit-тесты HTTP adapter теперь проверяют делегирование/отказ, а матрица ACL проверяется интеграционно на PostgreSQL.

Статус 22.10.2026: этап 11.4 выполнен; транспорт и результаты проверок — [NOTIFICATION_TRANSPORT.md](NOTIFICATION_TRANSPORT.md). Этап 11.5 согласован и выполнен 22.10.2026: NOTIFICATION_OUTBOX.md. Следующий этап 11.6 требует отдельного согласования.

Результат 11.5 (22.10.2026): транзакционный UI outbox/relay с claim fencing, retry/quarantine и метриками, propagation origin, глобальный provider и REST-обновление Inbox/переписки. Настоящий desktop/mobile browser E2E пройден; подробности и границы следующего этапа — [NOTIFICATION_OUTBOX.md](NOTIFICATION_OUTBOX.md). Миграций и коммитов нет. 11.6 требует отдельного согласования плана.
