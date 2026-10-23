# Мобильные уведомления — интеграционный контракт 11.8

Реализованы серверные регистрации, атомарные delivery intents, DB worker и общий `PushProvider` contract. Реальные FCM/APNs adapters и Android/iOS приложение в этот этап не входят. Тестовый provider используется тестами, а не подменяет рабочую доставку. Общие API/семантика — [NOTIFICATION_CONTRACTS.md](NOTIFICATION_CONTRACTS.md); web UI — [NOTIFICATION_CENTER.md](NOTIFICATION_CENTER.md).

## Регистрация установки

Native-клиент создаёт случайный `installationId` UUID и CSPRNG proof минимум 32 байта (base64url, 43–128 символов), сохраняет их в защищённом хранилище установки и сериализует её изменения. Публичный DTO содержит версии/состояние, но не endpoint и не proof.

`PUT /api/push/installations/{id}` принимает `expectedRegistrationVersion`, `platform` (`ANDROID`/`IOS`), `provider` (`FCM`/`APNS`), `appId`, `environment` (`PRODUCTION`/`SANDBOX`), `registrationReference`, `installationSecret`, `enabled` и `reportedPermission` (`UNKNOWN`/`GRANTED`/`DENIED`/`PROVISIONAL`). Создание требует версии 0; успешный ответ возвращает DTO и ETag версии. Существующая установка требует правильный proof и точную версию. Текущий JWT задаёт владельца; PUT новой account session с proof/версией — явный rebind, повышающий bindingVersion и отменяющий старые intents.

`DELETE /api/push/installations/{id}` требует текущего владельца, `X-Installation-Secret` и `If-Match: "registrationVersion"`. Выполняется soft disable с увеличением версии. Поздний logout прежней account session не должен отвязывать новый binding. Клиент отбрасывает результаты старых сессий.

При `409 INSTALLATION_CONFLICT` нельзя автоматически отправлять старое тело с новой версией. Полученный после проверки proof ETag используется только новым явным действием текущей сессии. Это относится и к потерянному ответу первого PUT: повтор версии 0 конфликтует, а не создаёт новую установку. Совпавший активный endpoint другой установки не переносит её владельца. APNS допускается только для IOS; app/provider/environment проходят серверный allowlist.

Endpoint шифруется AES-256-GCM случайным nonce и authenticated additional data. Ключ находится вне БД. В таблице остаются ciphertext, SHA-256 fingerprint endpoint и SHA-256 hash proof. DTO, `toString` и публичные ошибки не возвращают секреты.

## Намерение и отправка

В транзакции новой записи центра `PushIntentWriter` создаёт строки `notificationdelivery` для текущих активных enabled-установок с `GRANTED`/`PROVISIONAL`. Это снимок установок в виде самих intents, без дополнительной snapshot-таблицы. Уникальность — notification/installation/binding/channel. Новая установка или новый binding позднее не создают intents для старого notification.

Worker забирает due-задание короткой транзакцией `FOR UPDATE SKIP LOCKED`, повышает claimVersion и ставит lease. Внешний вызов идёт вне транзакции в ограниченном executor. Перед ним проверяются текущий claim, остаток TTL, доступность notification, непрочитанность/alertActive, ACL, binding, permission/activity и endpoint version. Новый endpoint той же установки используется последующей допустимой попыткой. Обработка INVALID_ENDPOINT сначала блокирует регистрацию, затем delivery, в том же порядке, что logout/rebind. При ротации во время provider call старый ответ переводит прежний логический delivery в RETRY на актуальном endpoint с исходным expiresAt (либо в EXPIRED при исчерпании TTL), сохраняя новую регистрацию. Смена владельца, disable или DENIED отзывают прежние задания; claimVersion блокирует применение позднего результата.

`PushProvider.Request` содержит `notificationId`, schemaVersion=1, app/environment/platform, provider registration reference и оставшийся TTL. Текст сообщения, имя клиента и другие частные поля в provider payload не добавляются. Native-клиент получает актуальное содержимое только через authenticated REST. ACCEPTED означает принятие provider, а не показ на устройстве. Lease и внешний повтор не обеспечивают exactly-once.

Worker сохраняет terminal/retry outcome условно по claimVersion. Базовый retry начинается с секунды и экспоненциально растёт до минуты; jitter ±20% применяется к backoff с общим пределом минуты. Provider hint задаёт минимум ожидания в пределах оставшегося TTL. Просроченное задание становится EXPIRED. `push-enabled=false` приостанавливает отправку: intents продолжают фиксироваться атомарно, исходный TTL не продлевается.

## Конфигурация сервера

| Свойство | Значение/назначение |
|---|---|
| `clientbus.notifications.push-enabled` | false по умолчанию; включает worker |
| `clientbus.notifications.push.endpoint-key` | Base64 стандартного алфавита, ровно 32 случайных байта после декодирования; хранить вне БД |
| `clientbus.notifications.push.allowed-apps` | Список `{app-id, provider, environment}`; обязателен для регистраций, при включении worker все provider должны иметь adapter |
| `clientbus.notifications.push.provider-threads` | 2 по умолчанию, диапазон 1–8 |
| `clientbus.notifications.push.provider-timeout` | PT5S по умолчанию, минимум 1 мс, максимум PT20S и строго меньше lease |
| `clientbus.notifications.push-poll-ms` | 1000 по умолчанию; отдельный scheduler worker |
| `clientbus.notifications.lease` / `push-batch-size` | PT30S / 50 по умолчанию |
| `clientbus.notifications.installation-activity` / `delivery-retention` | 30 дней / семь дней по умолчанию |

При отсутствии ключа запись endpoint отклоняется безопасной ошибкой; включение worker без ключа/allowlist/adapters не проходит startup. Реальные adapters должны быть отдельными bean `PushProvider` без собственной повторной очереди: lease/retry/TTL/policy уже принадлежат worker.

## Foreground, tap и восстановление клиента

Web bridge уже принимает `window.dispatchEvent(new CustomEvent('clientbus:foreground-notification', {detail: {notificationId}}))`. Он использует тот же detail/eligibility/dedup, что SSE. Это точка интеграции, а не установленный SDK.

Native-клиент использует тот же `notificationId`, list/count/detail/read/read-all REST и типизированную цель. Foreground SSE и foreground push разделяют ограниченный аккаунтный dedup (48 часов/4096 ID). При resume/cold start приложение сначала восстанавливает authentication, проверяет detail/ACL, получает список/счётчик и только затем открывает доступную цель. Фоновый OS push имеет свой TTL; SSE `alertsAfter` не отменяет уже созданные серверные intents. Недоставленный push не мешает последующей REST-сверке.

## Обязательная приёмка перед native-релизом

- Реальные FCM/APNs credentials, app ID/environment, provider ошибки и token refresh; секреты не попадают в логи/аналитику.
- Android/iOS foreground/background/terminated и force-stop с учётом ограничений ОС; разрешение, отказ и provisional-поведение.
- Offline, потеря push, resume, cold start по нажатию и восстановление через REST.
- Несколько устройств, reinstallation, смена аккаунта, logout offline, поздние ответы старой session и конфликт версии.
- Дубликаты SSE/push, недоступная или удалённая цель после tap, отзыв membership/assignment до отправки и до открытия.
- Retry/lease takeover/поздний provider result, старый INVALID_ENDPOINT после token rotation, остаточный TTL и отсутствие частных данных в payload.

Пробуждение при любом состоянии ОС, показ каждой отправки и межвкладочный exactly-once не заявляются. Успех серверных тестов не закрывает эту native-приёмку.
