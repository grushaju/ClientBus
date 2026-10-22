# Stage 11.7 — восстановление, деградация и пределы нагрузки

Уточнённый план принят пользователем 22.10.2026 после ревью. Этап реализован и приёмка пройдена 22.10.2026. Основание: [Stage 11, 11.7](<Stage 11.txt>), [контракты](NOTIFICATION_CONTRACTS.md), [UI lifecycle 11.6](NOTIFICATION_UI.md).

Реализация охватывает активные ресурсы Inbox, текущую identity и каталог Workspace. REST определяет состояние. Наличие SSE heartbeat не доказывает публикацию outbox. Центр уведомлений/mobile push остаются в 11.8, полное покрытие доменных writers — в 11.9, полная эксплуатационная наблюдаемость — в 11.11. Миграций и коммитов нет; Telegram остаётся обычным IChannelConnector.

## 1. Режимы восстановления

| Условие | Автоматическое поведение | Выход |
|---|---|---|
| connecting/reconciling | Ожидание готовности транспорта и ограниченной REST-сверки; fallback страхует длительное восстановление | Завершение сверки либо переход к деградации |
| SSE открыт, REST-сверка успешна | Событийные обновления и редкая контрольная сверка активных ресурсов | Ошибка транспорта/REST, смена области |
| SSE открыт, часть REST недоступна | Сокет сохраняется; успешные ресурсы применяются, ошибки остаются dirty и повторяются с backoff | Успешная сверка возвращает обычный режим |
| EOF, timeout, transient HTTP/network failure | Reconnect с backoff/jitter; ограниченный REST fallback | Готовый stream и успешная сверка |
| 404 / SSE выключен | REST fallback; тот же transport автоматически не перезапускается | Новый scope, reload или новое развёртывание UI |
| Несовместимая schemaVersion | Терминальный incompatible transport и REST fallback; неизвестные DTO не применяются | Новый scope/reload с совместимым UI/BE |
| Неизвестный event type при совместимой версии | Ограниченная общая сверка через dirty/coalescing, сокет сохраняется | Продолжение обычного потока |
| SSE 403 | Немедленная очистка matching данных, отмена старых identity/catalogue GET и пересмотр доступа | Разрешённый scope; старое разрешение не восстанавливается поздним ответом |
| 401 / logout / смена credentials | Прежний transport и его запросы завершаются | Новая authenticated область |
| Вкладка скрыта / offline | Периодическая сверка и запуск ожидающих GET приостановлены; access cleanup продолжает действовать | Возврат/online помечает активные ресурсы dirty, без очереди пропущенных тиков |

`stream.ready` запускает ограниченную сверку; бизнес-сигналы до её завершения буферизуются, identity/access control обрабатывается сразу. Неуспех отдельного REST-ресурса переводит восстановление в degraded, а не замыкает цикл REST failure → SSE reconnect → повтор всех запросов. Deadline зависшей сверки и ограничения буфера сохраняются.

Короткоживущий ready не сбрасывает reconnect backoff: для сброса требуется не менее 10 секунд открытого соединения. Retry-After поддерживает секунды и HTTP-date с клиентским пределом 60 секунд; аналогичный hint REST-ошибки сохраняется в ApiError и учитывается при повторе ресурса.

## 2. Планировщик и HTTP-бюджет

- UiRecovery выполняет одну периодическую сверку смонтированных ресурсов. Обычные SSE/local invalidation, fallback и контрольная сверка используют тот же coordinator и dirty. Переходы connecting/backoff не откладывают fallback до бесконечности.
- InvalidationCoordinator сохраняет ревизии, один refetch на ресурс, экспоненциальный retry и признак частичного отказа. Закрытый coordinator не передаёт поздний health callback новому владельцу; StrictMode повторная подписка поддерживается.
- RequestBudget ограничивает фактические GET, включая вложенные параллельные запросы и чтение тела ответа. Бюджет наследуется через AbortSignal в coordinator, lifetime/resource hooks, Inbox и сообщения. Загрузка аватаров текущего клиента/аккаунтов/сотрудников в UiScope также использует его.
- Очередь FIFO ограничена. Переполнение возвращает локальную transient 429, dirty сохраняется. Отмена удаляет ожидание; смена области отменяет активные/ожидающие запросы. Offline/hidden приостанавливает выдачу новых разрешений; уже начатый запрос может завершиться.
- POST/PUT/DELETE бизнес-команды не ждут автоматического GET-бюджета. Очистка при access control выполняется немедленно. Bootstrap Auth/Workspace до создания UiScope не относится к фоновому refetch-бюджету.
- Автоматическая сверка не обходит retryAt отказавшего ресурса. Подтверждённые 403/404 сохраняют правила очистки/terminal denial 11.6; явный retry или новый разрешённый scope восстанавливает чтение.

Старые таймеры metadata 10 секунд и messages 5 секунд отключены только при managedPolling. `enabled=false` возвращает прежние таймеры и отключает новый scheduler/HTTP-бюджет, сохраняя SSE и исправления lifecycle. Telegram connection flow polling относится к прежнему процессу подключения коннектора и не меняется.

## 3. Конфигурация UI

Параметры Vite задаются при сборке, проверяются до выпуска bundle. Изменение требует новой сборки UI. Команды dev/build/preview явно используют vite.config.ts: старый сгенерированный vite.config.js не может подменить конфигурацию. Node TypeScript outputs вынесены в node_modules/.tmp, чтобы не создавать JS рядом с исходным recoveryPolicy.ts. Это начальные значения, а не обещание production SLO.

| Переменная | Default | Назначение |
|---|---:|---|
| VITE_UI_RECOVERY_ENABLED | true | Управляемая политика; false — откат к прежним таймерам |
| VITE_UI_RECOVERY_FALLBACK_MS | 10000 | Номинальная fallback-сверка |
| VITE_UI_RECOVERY_FALLBACK_MAX_MS | 60000 | Максимальная пауза fallback после ошибок |
| VITE_UI_RECOVERY_CONTROL_MS | 120000 | Редкая сверка при работающем SSE |
| VITE_UI_RECOVERY_JITTER | 0.2 | Разнесение запусков; control 120–144 s, fallback ограничен max |
| VITE_UI_RECOVERY_MAX_CONCURRENT | 4 | Параллельные GET одной UiScope |
| VITE_UI_RECOVERY_MAX_QUEUED | 128 | Ожидающие GET |
| VITE_UI_RECOVERY_REQUEST_SPACING_MS | 100 | Минимальный интервал старта GET: до 10 запусков/s одной UiScope |
| VITE_UI_RECOVERY_RETRY_MS / RETRY_MAX_MS | 1000 / 60000 | Retry отдельного ресурса |

Ключи/очереди не содержат токена; диагностические snapshot содержат только режим и числовые счётчики. Ограничение действует на браузерную область: несколько вкладок/пользователей суммируют нагрузку. Межвкладочный лидер, серверный глобальный REST rate limiter и native background worker этим этапом не вводятся.

Задержка восстановления включает обнаружение отказа, выбранный интервал с jitter, очередь HTTP и длительность REST/пагинации. При недоступном REST конечная задержка актуализации не гарантируется; очередь, память и частота повторов остаются ограничены.

## 4. Gateway и consumer groups

Существующие пределы gateway сохраняются: 256 соединений/экземпляр, 4/сотрудник/экземпляр, 8 writers и очередь executor 256; очередь соединения 128, payload до 16 KiB, внутренний outbox до 64 KiB. Увеличение wire limits требует изменения контракта. ACL перед отправкой и изоляция отдельного gateway DB pool сохраняются.

Новые параметры `clientbus.notifications.gateway`:

| Параметр | Default | Предел |
|---|---:|---|
| max-poll-records | 100 | 1–1000 |
| max-fetch-bytes | 1048576 | 64 KiB–16 MiB |
| cleanup-group-on-stop | true | Можно отключить, если delete-group ACL недоступна |

Kafka fetch допускает особое поведение для первого большого record; в дополнение действуют topic max.message.bytes и проверка maxOutboxBytes до десериализации. Размер payload не становится неограниченным из-за увеличения fetch-бюджета.

Один активный gateway обязан иметь собственный instance-id и группу `clientbus-ui-<instance-id>`. Пустой ID создаёт случайный UUID на процесс. Совпадение ID между работающими экземплярами вызывает потерю полного partition assignment и закрытие admission; объединять два gateway в одну группу нельзя. Проверено восстановление после устранения такого конфликта.

Повторный start не создаёт второй поток, пока прежний subscriber ещё останавливается. Stop будит consumer, ограниченно ждёт завершения и закрывает сокеты. При graceful stop удаляется только собственная группа; Kafka отказывает в удалении активной группы. Admin ожидание/close ограничены broker timeout. Отказ admin-операции не удаляет чужие группы и не блокирует shutdown без ограничения.

После аварийного завершения остаётся процедура очистки: оператор подтверждает, что экземпляр выведен и не перезапустится с этим ID, проверяет точную группу штатным Kafka admin CLI и удаляет только её. Автоматического sweep всех пустых групп нет. Число экземпляров и остаточных групп ограничивается эксплуатационной конфигурацией; полный runbook/метрики завершаются в 11.11.

## 5. Проверки и измерения

Итоговые проверки:

- BE: 30 passed — gateway limits/overflow/slow writer, изоляция scheduler/outbox, два gateway, graceful cleanup одной группы, конфликт instance-id/rebalance, реальный stop/start Kafka и доставка после восстановления. Финальный BE-прогон: BUILD SUCCESS 22.10.2026 15:10:20 +03:00.
- UI: полный Node/React набор — 255 passed, включая 23 новых проверки 11.7. Покрыты очередь/параллельность/тело ответа, отмена и hidden/offline, rollback flag, отсутствие старого message polling в managed режиме, Retry-After, частичный REST failure, непрерывная нагрузка, несовместимая схема и backoff короткоживущих соединений.
- TypeScript приложения, Vite config и E2E прошёл без изменения пользовательского ui/tsconfig.app.tsbuildinfo. Production build и отдельный rollback bundle с VITE_UI_RECOVERY_ENABLED=false собраны; maxConcurrent=0 отклонён при сборке.
- Нагрузочный профиль Node: 24 ресурса, 2 GET/ресурс, burst 20 invalidation каждые 2 ms в течение 120 ms; HTTP spacing 10 ms, concurrency 4, queue 64. Представительный прогон: 98 GET, peak active 3, peak queued 47, rejected 0, dirty/running/queued/active в конце 0; 1607 ms. Это локальная проверка ограничений с искусственной сетью, не production benchmark.
- Настоящий browser: 4 сценария на desktop/mobile — live/ACL/rollback/dedup/reconnect; account switch/storage logout; SSE 404 с fallback; остановленный publisher relay при живом Kafka/SSE heartbeat. Последний сценарий должен пройти через контрольный интервал 120–144 секунд, без нового messages.invalidated и со старым черновиком. Проверка более 90 секунд исключает незамеченный старый 5-секундный polling.

Финальный browser-прогон на production defaults: 8 passed (5.5 min); NotificationBrowserAcceptance — 1 passed, Maven BUILD SUCCESS 22.10.2026 15:35:21 +03:00. Fallback-сценарии: desktop 12.7 s, mobile 12.2 s; сценарии пропущенного сигнала при живом heartbeat — около 2.3 min каждый. В каждом recovery-сценарии число дополнительных GET меньше 30; draft сохранён. Это наблюдения локального изолированного стенда.

Проверен bundle index-Cdx8Xv4o.js; SHA-256 index.html в ui/dist и server/target/classes/static совпадает: `4DA66D48755A9913054A10AF008264BE00043A243C6FE7C5C6441FFD5FD762AB`. Manifest с credentials отсутствует после завершения. Пользовательский ui/tsconfig.app.tsbuildinfo не изменён этим этапом.

Тестовый Kafka получает свободный, но явно закреплённый host port: Docker stop/start не меняет advertised endpoint. Пользовательский Kafka на 9092 не перезапускается. Browser control/publisher gate доступны только в тестовом профиле notification-browser, отсутствуют в runtime artifact.

Логи: `server/target/notification-11-7-all-ui.log`, `notification-11-7-recovery-ui.log`, `notification-11-7-backend.log`, `notification-11-7-browser-maven.log`, `notification-browser.log`. Временный manifest с credentials удаляется harness в finally.

Кодревью выявило шесть дефектов: исходное регрессионное покрытие приведено в разделе 6, исправления и повторная проверка — в разделе 7. 11.8 не начат и требует отдельного согласования плана по правилу пользователя.

## 6. Регрессионное покрытие кодревью 22.10.2026

Сначала добавлены 16 проверок ожидаемого поведения: 11 UI и 5 параметризованных BE-сценариев. Исполняемый код приложения в рамках добавления покрытия не менялся. Тесты включены в обычные наборы, без skip/todo и без утверждений, закрепляющих ошибочное поведение. Следующая таблица фиксирует исходный красный прогон до исправлений.

| Замечание | Сценарий и ожидаемый результат | Тесты | До исправлений |
|---|---|---|---|
| P1: потеря отмены после ранней ошибки | Параллельный GET возвращает 503, соседний зависает при чтении тела. Тело отменяется, permit освобождается; повторение не блокирует последующий исправный GET. Контроли: успешное чтение обоих тел и parent abort. | `ui/tests/notificationRequestScope.test.cjs`, 4 теста | 2 FAIL, 2 PASS |
| P2: вложения вне бюджета | Настоящий MessageBubble и API ограничивают metadata/blob GET, не запускают очередь на паузе, продолжают её после resume и отменяют работу при unmount/смене scope. | `ui/tests/notificationMediaBudget.react.test.cjs`, 3 теста | 3 FAIL |
| P2: потеря прогресса старых статусов | Загружена отдельная старая страница с 16 исходящими сообщениями; полный проход статусов занимает 40 секунд при настоящем deadline coordinator 30 секунд. После нескольких попыток полученные результаты сохранены, все статусы READ, текущая страница остаётся на месте. | `ui/tests/notificationLifecycle.react.test.cjs`, `old message statuses make durable progress across reconciliation deadlines` | FAIL: все 16 остаются SENT |
| P2: ready обходит backoff | Пять повторных stream.ready продолжают обновлять исправный ресурс, но не повторяют GET отказавшего до истечения Retry-After 60 секунд. На границе срока повтор разрешён. | `ui/tests/notificationLifecycle.react.test.cjs`, `reconnected stream.ready preserves Retry-After while healthy subscriptions still reconcile` | FAIL: 5 повторов вместо 1 |
| P2: hook теряет Retry-After | Настоящий HTTP API и message hook получают 429/503 с Retry-After: 60. Повтор не начинается через default 1 секунду, но выполняется после 60 секунд и очищает ошибку. | `ui/tests/notificationLifecycle.react.test.cjs`, `message reconciliation retains HTTP ... Retry-After through the real API and hook`, 2 теста | 2 FAIL |
| P3: некорректные Admin timeouts | Настоящий Kafka Admin создаётся с настройками UiKafkaSubscriber для всех принимаемых broker-timeout: 1/500/999/1000/2000 ms. Подписчик не запускается, соединение с брокером для проверки валидации не требуется. | `server/src/test/java/kit/penny/clientbus/server/notification/transport/UiKafkaAdminConfigurationTest.java`, 5 случаев | 1/500/999 ms FAIL; 1000/2000 ms PASS |

Проверка выполнена следующими командами:

```powershell
cd ui
npm test
cd ..
mvn -o -pl server -am '-Dtest=UiKafkaAdminConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test-compile surefire:test
```

Исходный полный UI-набор: **266 тестов, 257 passed, 9 failed, 0 cancelled/skipped/todo**. Все прежние 255 тестов и два новых контрольных сценария прошли. Исходный целевой BE-набор: **5 тестов, 2 passed, 3 failed, 0 errors/skipped**; падения вызваны ConfigException при default.api.timeout.ms < request.timeout.ms. Из 16 добавленных проверок 12 воспроизвели дефекты, 4 контрольные прошли.

UI использует управляемые ответы и виртуальные таймеры; 90 секунд восстановления старых статусов проходят без ожидания реального времени. BE использует настоящую валидацию Kafka Admin без запуска Docker или брокера. TypeScript/build и browser E2E для изменения тестов повторно не запускались.

Логи исходного прогона: `server/target/notification-11-7-review-coverage-ui.log`, `server/target/notification-11-7-review-admin-test.log`.

## 7. Исправления по кодревью 22.10.2026

- `withCancellation` при завершении операции отменяет оставшиеся дочерние чтения. Ранняя ошибка Promise.all и успешный результат Promise.race освобождают слоты зависших соседей. Сохраняются исходная ошибка и уже разобранный результат; все текущие callers разбирают тело внутри области операции.
- Автоматический `stream.ready` соблюдает `retryAt`, а message hook передаёт coordinator исходную ошибку, включая status и retryAfterMs. Это относится и к ошибкам чтения текущей страницы, и к запросам старых статусов.
- Metadata и blob вложений используют AbortSignal, общий GET-бюджет и useUiLifetime. Пауза бюджета действует на оба вида загрузки; unmount/смена scope отменяют работу. Результаты привязаны к владельцу, прежние metadata/URL не отображаются в новом scope, object URL освобождаются.
- Старые статусы применяются по мере получения с проверкой session/generation и версии локального сообщения. Конечный набор незавершённых ID сохраняется между попытками и сбрасывается при reload/denial/завершении прохода. Успешно проверенный неизменившийся SENT/DELIVERED также удаляется из текущего набора, поэтому поздние ID не голодают. Новые ID входят в следующий проход.
- Kafka Admin получает согласованные API/request timeouts не меньше 1 секунды. Внешнее ожидание cleanup и close по-прежнему ограничено настроенным broker-timeout; положительные subsecond/submillisecond значения не запрещены.

Дополнительно закреплены успешный Promise.race с ненужным зависшим чтением, продолжение длинного прохода с неизменившимися SENT, защита локального статуса от старого ответа, освобождение object URL и Kafka Admin с broker-timeout=1 ns. Итоговое покрытие ревью — 14 UI и 6 BE-проверок; исходные assertions сохранены.

Полный UI-набор: **269/269 passed, 0 failed/cancelled/skipped/todo**. TypeScript приложения, Vite config и E2E, production Vite build прошли. Пользовательский ui/tsconfig.app.tsbuildinfo сохранён.

Связанный BE-набор: **28/28 passed, 0 failures/errors/skipped** — UiGatewayPropertiesTest, UiKafkaAdminConfigurationTest (6 значений, включая 1 ns), UiSchedulingIsolationTest, UiSseGatewayTest и UiTransportIntegrationTest (9 сценариев с настоящим изолированным Kafka). Проверены cleanup своей группы, stop/start, конфликт instance-id, rebalance/restart и восстановление fanout. Maven BUILD SUCCESS 22.10.2026 21:17:09 +03:00.

Команды повторной проверки:

```powershell
cd ui
npm test
node node_modules/typescript/bin/tsc --project tsconfig.app.json --noEmit --incremental false
node node_modules/typescript/bin/tsc --project tsconfig.node.json --noEmit --tsBuildInfoFile node_modules/.tmp/notification-11-7-fixes-node.tsbuildinfo
node node_modules/typescript/bin/tsc --project e2e/tsconfig.json --noEmit
node node_modules/vite/bin/vite.js build --config vite.config.ts
cd ..
mvn -o -pl server -am '-Dtest=UiKafkaAdminConfigurationTest,UiGatewayPropertiesTest,UiSseGatewayTest,UiSchedulingIsolationTest,UiTransportIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' -l server/target/notification-11-7-review-backend-fixes.log test-compile surefire:test
mvn -o -pl server -am '-Dtest=NotificationBrowserAcceptance' '-Dsurefire.failIfNoSpecifiedTests=false' -l server/target/notification-11-7-review-fixes-browser-maven.log test-compile surefire:test
```

Финальный browser-прогон на свежем production bundle: **8/8 passed** (desktop/mobile, 5.5 min), NotificationBrowserAcceptance — **1/1 passed**, Maven BUILD SUCCESS 22.10.2026 21:23:12 +03:00. Проверены live/ACL/rollback/dedup/reconnect, смена аккаунта/logout, fallback при SSE 404 и редкая контрольная сверка при остановленном relay и живом heartbeat. Контрольные сценарии прошли за 2.4 min desktop и 2.2 min mobile; черновик сохранён.

Проверен `index-CuFRa4pF.js`; SHA-256 index.html в ui/dist и server/target/classes/static совпадает: `0D852A19A7D03EB85371FDC7DC525E90E3444E9FDE2C874DAEBC73C5EC090F25`. Временный manifest с credentials удалён. SHA-256 пользовательского ui/tsconfig.app.tsbuildinfo остался `FCBC3B3FED3138EC5758BE924FC98F0D6A1E593EB5868D4589203ACD3D617887`.

Логи исправлений: `server/target/notification-11-7-review-fixes-ui.log`, `server/target/notification-11-7-review-backend-fixes.log`, `server/target/notification-11-7-review-fixes-browser-maven.log`, `server/target/notification-browser.log`. Тесты не отключались; миграций и коммитов нет. Все шесть замечаний закрыты кодом и зелёными регрессиями. 11.7 повторно закрыт, 11.8 не начат.
