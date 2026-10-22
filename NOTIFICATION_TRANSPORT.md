# Stage 11.4 — Kafka/SSE transport

Этап выполнен 22.10.2026 по согласованному плану и указанию пользователя начать 11.4. Основание: [контракты](NOTIFICATION_CONTRACTS.md), [policy](NOTIFICATION_ACCESS.md), [Stage 11](<Stage 11.txt>).

## Реализовано

- `GET /api/events?workspaceId=…`: streaming fetch Bearer JWT, `Cache-Control: no-store`, `X-Accel-Buffering: no`. JSON ошибки до открытия: 400/401/403/429/503 по NotificationApiErrorDto. Нет JWT в URL, SSE id, replay или Last-Event-ID.
- [UiKafkaSubscriber](server/src/main/java/kit/penny/clientbus/server/notification/transport/UiKafkaSubscriber.java) читает только `clientbus.ui.invalidations.v1`, отдельной группой `clientbus-ui-{instanceId}`. Общие TLS/SASL настройки берутся из KafkaProperties, бизнес-десериализаторы не используются. Три партиции; replication factor 1 для текущего окружения; retention 24 часа, max.message.bytes 64 KiB. Для окружения с репликацией топик следует создавать с нужным replication factor при развёртывании.
- Каждая группа должна владеть всеми партициями topic. Ready разрешён после назначения и успешного ответа брокера endOffsets; пустой poll не считается доказательством здоровья. Проверка повторяется каждые 2 секунды, broker timeout 2 секунды; отсутствие успешной проверки 10 секунд закрывает соединения. Rebalance, потеря consumer и shutdown также закрывают их. Восстановление допускает новую сессию и REST reconciliation.
- Poll-поток только декодирует/проверяет размер записи и кладёт ограниченные ссылки в очереди соединений. SQL и socket send выполняются отдельным bounded executor. Ошибочная запись вызывает минимальный resync/закрытие текущих клиентов, затем consumer продолжает обработку, без poison retry loop.
- [UiSseGateway](server/src/main/java/kit/penny/clientbus/server/notification/transport/UiSseGateway.java): локальный registry, ready первым, DB clock_timestamp для alertsAfter, heartbeat и повторная актуальная ACL перед выдачей. Каждая сессия хранит проверенную expiry JWT, а не raw token. Scope Workspace ограничивает обычные события; PERSONAL/FORMER_SCOPE разрешены между доступными Workspace. Утрата выбранного Workspace, Organization или enabled закрывает сессию при следующем событии/heartbeat; expiry проверяется ежесекундно.
- Одинаковые pending invalidations одного type/audience/workspace/resource объединяются. Переполнение очереди заменяет backlog на минимальный resync и закрытие; если ready ещё не отправлен или executor занят, соединение закрывается без роста очереди. Два дополнительных ограниченных потока завершают emitter, чтобы закрытие медленного сокета не блокировало poll/timer. При переполнении очереди завершения новые соединения получают 503.
- До подключения MVC handler SseEmitter получает только ready; writer ждёт фактического commit ответа перед следующей отправкой. Это ограничивает внутренний early-send buffer emitter. Logical write deadline 5 секунд освобождает registry и запускает завершение. Servlet/socket write может физически удерживать поток до тайм-аута контейнера; число потоков и очередей ограничено. Proxy/container write timeouts должны быть настроены в окружении.
- SSE request имеет собственный async timeout до 30 минут, не дольше оставшегося JWT. Security разрешает ASYNC continuation только для `/api/events`; исходный REQUEST требует аутентификацию. SSE не использует default MVC Callable executor: исполнение отправок полностью принадлежит bounded gateway executor.
- Health indicator `uiPipeline`: UP только при готовом подписчике; содержит число локальных соединений. Это проверка Kafka→gateway, а не подтверждение исправности будущего outbox relay. Возраст outbox наблюдается отдельно в 11.5/11.11.

## Настройки

По умолчанию `clientbus.notifications.sse-enabled=false`: endpoint/consumer/topic/health indicator не регистрируются. Для включения транспорта установить true и перезапустить приложение. Остальные флаги создания уведомлений/push/history не меняются.

| Параметр | Значение по умолчанию |
|---|---|
| gateway.instance-id | Случайный UUID на процесс; можно задать уникальный ID экземпляра |
| gateway.max-connections / max-connections-per-employee | 256 / 4 |
| gateway.workers / executor-queue-capacity | 8 / 256 |
| queue-capacity / max-event-bytes / max-outbox-bytes | 128 / 16 KiB / 64 KiB |
| heartbeat-seconds | 15 |
| gateway.write-timeout | 5 секунд |
| gateway.probe-interval / broker-timeout / pipeline-stale-after | 2 / 2 / 10 секунд |

Префикс gateway параметров — `clientbus.notifications.gateway`; остальные строки используют `clientbus.notifications`. Параметры проверяются при binding; interval + broker timeout должны быть меньше stale deadline. Одновременные экземпляры обязаны иметь разные instance-id. Новая группа начинает с latest; consumer не сохраняет offsets, replay браузеру не предоставляется. После рестарта восстановление состояния выполняется REST. В 11.7 добавлена ограниченная очистка собственной группы после graceful stop; после crash остаётся точная административная очистка выведенного экземпляра. Группы на пользователя или соединение не создаются. Новые fetch limits и порядок эксплуатации — [NOTIFICATION_RECOVERY.md](NOTIFICATION_RECOVERY.md).

Внутренний UI record содержит не более 500 explicit recipients; будущий producer разбивает массовую персональную/прежнюю аудиторию на записи этого размера. ORGANIZATION/WORKSPACE не требуют перечислять всех получателей в record. Исходные business Kafka DTO в этот topic не публикуются.

Для reverse proxy отключить buffering/cache/compression у `/api/events`, сохранить Authorization, разрешить долгий HTTP response. Read timeout должен превышать heartbeat с запасом (например, 60 секунд при heartbeat 15); write timeout задаётся конечным. В логах не сохранять Authorization или тела внутренних событий. Заголовок X-Accel-Buffering не заменяет конфигурацию всех видов proxy.

## Клиент

[SseParser](ui/src/api/events/sseParser.ts) поддерживает частичные строки, LF/CRLF/CR, BOM, комментарии и многострочный data. id/retry не создают replay-состояния; незавершённый frame при EOF отбрасывается. Размер frame ограничен 18 KiB с учётом framing, JSON envelope — 16 KiB.

[startUiEventStream](ui/src/api/events/uiEventStream.ts) использует streaming fetch и incremental UTF-8 decoder. Он ждёт ready и Promise начальной REST-сверки от onReady; до её завершения хранит не более 128 событий. Отменённая попытка не выдаёт поздний результат. EOF/ошибка/тайм-аут вызывают recovery и reconnect с jitter/backoff 1–30 секунд, Retry-After учитывается до 60 секунд. HTTP 401/403 терминальны; смена токена завершает старый transport. Приложение должно остановить прежний stream при logout/смене Workspace и создать новый экземпляр после изменения scope.

Watchdog ограничивает ожидание ready 20 секундами и проверяет heartbeat deadline после ready. Unknown type вызывает ограниченный onResync; несовместимая версия терминально переводит transport на безопасный REST fallback, без повторов того же несовместимого протокола. Malformed/oversized frame требует recovery с bounded backoff. onReady получает AbortSignal для отмены REST-запросов при закрытии попытки. В 11.7 частичный отказ REST сохраняет SSE и dirty проблемного ресурса; короткий ready не сбрасывает backoff до 10 секунд устойчивой работы. Политика — [NOTIFICATION_RECOVERY.md](NOTIFICATION_RECOVERY.md).

Граница исходного 11.4: transport library без AppLayout/business integration. В согласованном 11.5 подключены минимальный provider и вертикаль outbox→Kafka→SSE→REST; результат — NOTIFICATION_OUTBOX.md. Полноценные подписки и reconciliation остаются в 11.6; центр и push — в последующих этапах. Браузер и native foreground используют REST/SSE; Kafka находится только на BE.

## Проверки

- Unit gateway: ready ordering, независимые соединения, pipeline loss/reconnect, selected Workspace и personal scope, текущая ACL, expiry, overflow, logical write timeout, saturation executor, connection limit и malformed Kafka record.
- Реальные PostgreSQL/Kafka + встроенный HTTP server: две независимые consumer groups, доставка через настоящий SSE socket, no-store/anti-buffering, отзыв membership без следующего business event, остановка подписчика/503/recovery. Kafka-контейнер приостанавливается для проверки, что heartbeat не маскирует недоступность брокера; затем восстанавливается. Проверены JSON errors при SSE Accept и expired/missing/invalid JWT.
- UI parser/transport: framing, UTF-8 по отдельным байтам, размер, ready/reconciliation queue, overflow, 401/403, reconnect, смена сессии, abort/late result, watchdog, unknown type/schema и malformed envelope. Полный UI suite: 191 тест прошёл; TypeScript check и Vite production build прошли.

Тесты используют собственные контейнеры. Миграции и коммиты не создавались. Browser E2E с настоящими domain changes, rollback и outbox выполняется по плану 11.5.

Итоговый BE-прогон: 70 тестов без ошибок, включая 10 gateway unit и 4 реальных transport integration. UI: 191 тест без ошибок (12 новых transport/parser); tsc --incremental false и Vite build успешны. Static resources скопированы Maven из ui/dist в server/target/classes/static.

## Исправления после ревью 22.10.2026

- SSE admission и проверки ACL перед отправкой используют отдельный read-only PostgreSQL/Hikari pool `ui-acl`, не более gateway.workers соединений, minimumIdle=0. Он наследует JDBC URL, credentials, schema, catalog, driver properties и connection init SQL основного пула. Ожидание соединения ограничено 2 секундами, JDBC query timeout — 2 секундами, PostgreSQL socket timeout — 3 секундами, connect timeout — 2 секундами, cancel signal timeout — 1 секундой. Значения этих трёх JDBC URL параметров заменяются принудительно; остальные параметры, включая TLS, сохраняются. Основной бизнес-пул не изменяется. Для включённого gateway требуется текущий PostgreSQL/Hikari datasource с JDBC URL. Это дедлайны отдельных стадий/SQL, а не единый дедлайн обработки события.
- Зависшая SQL-проверка завершается ошибкой и закрывает соответствующий stream; bounded pool возвращается к обслуживанию запросов после тайм-аута. Kafka readiness по-прежнему описывает только Kafka pipeline и не подменяет health БД.
- Начальная REST-сверка ограничена 30 секундами независимо от heartbeat. По истечении дедлайна отменяется AbortSignal попытки, сбрасывается backlog, выполняются resync и reconnect. Позднее завершение старого onReady не открывает отменённую сессию.
- Допустимый heartbeat-seconds на BE и UI: 1–60 включительно; несовместимое значение отклоняется при binding.
- Добавлены проверки SQL timeout с конкурентными pg_sleep, исчерпания отдельного ACL pool и доступности бизнес-пула, диапазона heartbeat и зависшего onReady при исправных heartbeat.
Проверка исправлений: 41 BE-тест (включая 6 transport integration) и 13 UI transport-тестов прошли; TypeScript без incremental, Vite production build и git diff --check успешны. Полные наборы 70 BE/191 UI выше относятся к исходному завершению 11.4; после исправлений запускался указанный целевой набор. Миграций и коммитов нет.
