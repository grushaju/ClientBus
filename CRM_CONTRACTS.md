# CRM: контракты и хранение этапа 12.2

Дата: 27.10.2026; исправления двух ревью и уточнения по повторному чтению каталога/графиков внесены 28.10.2026. Основания: [Stage 12.txt](Stage%2012.txt), [CRM_SEMANTICS.md](CRM_SEMANTICS.md), [CRM_DIKIDI_PROTOCOL.md](CRM_DIKIDI_PROTOCOL.md).

Статус: спецификация 12.2 зафиксирована с исправлением восьми согласованных замечаний. По согласованному плану 12.3 реализованы модули, подключение DIKIDI, ручная привязка проекта, чтение мастеров, сопоставления Employee и четыре таблицы connection/project/master/syncstate. В 12.4.1 добавлены service/masterservice, согласованный R01-каталог, наблюдаемость и локальное чтение услуг. Проверки перечислены в [CRM_SETUP.md](CRM_SETUP.md). Остальные модели, REST чтения и команды ниже остаются спецификацией подэтапов 12.4.2–12.6. A/W-проверки протокола сохраняют фактические статусы; непроверенные команды DIKIDI не включены.

## 1. Модули

| Модуль | Ответственность |
|---|---|
| `crm/api` | Провайдерный SPI, нормализованные модели, запросы и результаты; без Spring MVC, JPA и зависимости от server |
| `crm/dikidi` | Реализация SPI, HTTP, сессии, CSRF, разбор JSON/HTML, единицы и внешние коды |
| `server` | Registry провайдеров, защищённые секреты, прикладные сценарии, persistence, права, синхронизация, операции, REST и outbox |
| `common` | Публичные DTO и перечисления CRM для backend/UI; не транспортный контракт DIKIDI |
| `ui` | Формы подключения, просмотр, сопоставления, операции и состояние данных |

Зависимости: `server -> crm/api`, `server -> crm/dikidi`, `crm/dikidi -> crm/api`; `crm/api` не зависит от server/common. Публичные DTO преобразуются прикладным слоем. DIKIDI не получает JPA-сущности, Employee, SecurityContext, EntityManager или репозитории.

Registry выбирает провайдер по коду. Авторизация/HTTP DIKIDI изолируются внутри адаптера для будущего переиспользования, но общий connector framework и чатовый SPI в этапе 12 не извлекаются.

### Типы CRM и доступные реализации

По уточнению пользователя вводятся два enum в `common`, с разным назначением:

| Enum | Значения | Назначение |
|---|---|---|
| `CrmType` | `DIKIDI`, `AMO`, `BITRIX` | Известные типы CRM; используется в публичном поле provider и в persistence |
| `AvailableCrmType` | `DIKIDI` | Реализованные типы, разрешённые для подключения в текущей версии ClientBus |

`AvailableCrmType` явно ссылается на соответствующий `CrmType`: `DIKIDI(CrmType.DIKIDI)` с методом `getCrmType()`. Полный перечень типов не считается перечнем реализованных адаптеров. При реализации 12.3 AMO и BITRIX не получают фиктивных адаптеров или доступности через одно присутствие в `CrmType`.

В JSON и колонке provider сохраняется строковый код `CrmType`, без ordinal и без отдельного DB enum. `AvailableCrmType` не дублируется в таблицах. Зависимости модулей сохраняются: `crm/api` не зависит от common; стабильный код провайдера SPI преобразуется в `CrmType` явно в server.

Список для UI формируется сервером из `AvailableCrmType`, а не из всех `CrmType`; в 12.3 он содержит только DIKIDI. BE самостоятельно проверяет этот перечень перед созданием подключения, сохранением секрета или внешним запросом. Известный тип AMO/BITRIX возвращает `409 CRM_UNSUPPORTED`; неизвестный код — `400 INVALID_REQUEST`. Registry проверяет соответствие разрешённых типов зарегистрированным адаптерам и не принимает дубли одного кода.

Доступность типа отделена от `CrmCapability` конкретного проекта: наличие DIKIDI в `AvailableCrmType` не включает непроверенные создание, перенос или отмену визита.

## 2. Минимальный SPI

`CrmProvider` содержит код провайдера и три порта: `CrmConnectionPort`, `CrmReadPort`, `CrmAppointmentCommandPort`. Разделение выражает разные гарантии чтения и изменения; универсальный CRUD произвольных сущностей не вводится.

| Порт | Операции |
|---|---|
| `CrmConnectionPort` | `verifyConnection(access)`, `inspectProject(access, externalProjectId)`, `listProjects(access, page)` |
| `CrmReadPort` | `readMasters(scope, page)`, `readServices(scope, page)`, `readOffers(scope, page)`, `readClients(scope, query, page)` |
| `CrmReadPort` | `readAppointments(scope, query, page)`, `readClientHistory(scope, clientReference, query, page)`, `readWorkIntervals(scope, query, page)` |
| `CrmReadPort` | `readAvailability(scope, request)`, `readFinancialContext(scope, externalAppointmentId)` |
| `CrmAppointmentCommandPort` | `create(scope, command)`, `move(scope, command)`, `cancel(scope, command)`, `reconcile(scope, operationReference)` |

Реализованный SPI 12.4.1 использует `readCatalog(access, externalProjectId)` → `CrmCatalogSnapshot`: masters/services/offers, observedAt, completeness и scope. DIKIDI получает все три коллекции одним R01; отдельное чтение мастеров сохранено для совместимости. Будущие постраничные методы в таблице выше пока остаются спецификацией.

`access` — внутренний доступ к авторизационным данным конкретного подключения и их версии. Он не сериализуется в REST/логи; секрет не попадает в обычный `toString`. `scope` содержит этот доступ, внешний project ID и неизменяемый ContextSnapshot: локальный projectId, provider, externalProjectId, connectionId, credentialVersion, contextVersion, zoneRevision. Бизнес-права пользователя проверяет server, а доступ учётной записи к проекту — адаптер. Вызов не меняет глобальный «текущий проект» общей сессии; последовательность страниц использует один снимок контекста.

Проект можно подключить через `inspectProject` по явно введённому ID, если `listProjects` не подтверждён. Командный порт не требует поддержки всех трёх команд: доступность каждой проверяется отдельно до обращения к провайдеру. `reconcile` может вернуть UNKNOWN; наличие метода не обещает найденный в DIKIDI lookup.

В DIKIDI-адаптере `inspectProject`/подготовка чтения после нового входа включает GET `/ru/owner/journal/?company={project}` в том же cookie-контексте до R01. Прямой R01 до инициализации, вернувший HTTP 200 с ACCESS_DENIED, не считается достаточным доказательством отзыва прав или истечения сессии. Это внутренний шаг адаптера без нового публичного метода; последующий ответ R01 всё равно проверяется по схеме, бизнес-ошибке и scope.

`verifyConnection` без выбранного проекта проверяет общую авторизацию, но не обещает права на произвольный проект. Проверка проекта, в том числе при verify уже привязанного подключения, сначала инициализирует этот контекст; только затем оценивает доступ. В повторном исследовании такой порядок устранил ACCESS_DENIED свежей сессии, поэтому ошибка до bootstrap не переводится автоматически в FORBIDDEN/REAUTH_REQUIRED.

### Возможности

Коды: `LIST_PROJECTS`, `READ_MASTERS`, `READ_SERVICES`, `READ_CLIENTS`, `READ_APPOINTMENTS`, `READ_CLIENT_HISTORY`, `READ_WORK_INTERVALS`, `READ_AVAILABILITY`, `READ_FINANCIAL_CONTEXT`, `CREATE_APPOINTMENT`, `MOVE_APPOINTMENT`, `CANCEL_APPOINTMENT`.

`CrmCapability` содержит `code`, `availability`, безопасный `reasonCode`, `checkedAt`. Возможности зависят от адаптера, подключения, проекта и проверенной семантики. `availability`:

| Значение | Значение для вызывающего кода |
|---|---|
| AVAILABLE | Метод разрешён для текущего контекста; результат всё равно сообщает полноту |
| UNVERIFIED | Поддержка/семантика ещё не доказана; вызов не выполняется |
| UNSUPPORTED | Подтверждено отсутствие поддержки; не используется вместо «не исследовано» |
| FORBIDDEN | У учётной записи провайдера нет нужного доступа |
| REAUTH_REQUIRED | Требуется восстановление внешней авторизации |
| CONFIGURATION_REQUIRED | Не хватает обязательной настройки, например timezone для команды |
| TEMPORARILY_UNAVAILABLE | Временный сбой или ограничение внешнего сервиса |

При старте DIKIDI команды создания/переноса/отмены и перечисление проектов остаются UNVERIFIED до соответствующих проверок. Исследованные чтения могут включаться после контрактных и интеграционных проверок адаптера; AVAILABLE не означает полную историю, поддержку группового визита или любого финансового документа.

### Результаты и ошибки

Чтение возвращает типизированный `CrmReadResult<T>`: элементы, фактический scope, `observedAt`, покрытие, `nextCursor`, продолжение MORE/END/UNKNOWN и предупреждения. Покрытие содержит набор, фильтр и период с полнотой COMPLETE/PARTIAL/UNKNOWN. END означает установленное окончание именно этого запроса; отсутствие курсора или HTTP 200 само по себе не является END.

Адаптер помечает присутствующие поля и полноту коллекций внутренними метаданными. Это позволяет отличать «не запрошено» от явного NULL/пустого полного списка и не затирать данные частичной проекцией. Такие метаданные не становятся свободным JSON-патчем публичного API.

Отказ — `CrmFailure` с кодом, безопасным сообщением, возможным `retryAfter` и признаком известности внешнего эффекта. Коды: `AUTH_REQUIRED`, `ACCESS_DENIED`, `CAPABILITY_UNVERIFIED`, `UNSUPPORTED`, `CONFIGURATION_REQUIRED`, `RATE_LIMITED`, `TEMPORARY_FAILURE`, `INVALID_RESPONSE`, `CONTEXT_MISMATCH`, `CONFLICT`, `OUTCOME_UNKNOWN`.

HTTP-код и truthy `error` не определяют результат DIKIDI. Проверяются семантика ответа, требуемые секции, проект и идентичность объекта; `error={code:0,message:null}` не является ошибкой. Сбой порции не превращается в пустой успешный результат. HTML/JS остаётся внутри адаптера.

## 3. Нормализованные модели

| Модель | Существенные поля |
|---|---|
| `CrmProjectInfo` | externalId, displayName, currency, zoneId, zoneSource, capabilities |
| `CrmMaster` | externalId, displayName, доступные контакты/аватар; без Employee |
| `CrmClient` | externalId, identityKind, displayName, исходные/нормализованные контакты; без ClientAccount |
| `CrmService` | externalId, название, категория, доступные базовые параметры |
| `CrmServiceOffer` | externalMasterId, externalServiceId, price, currency, durationSeconds, offerState, подтверждённые атрибуты провайдера |
| `CrmWorkInterval` | мастер, локальный интервал, установленный вид, внешний ref при наличии |
| `CrmAppointment` | externalId, kind, clientReference при доказанной индивидуальной форме, интервалы, услуги, мастера, состояния, исходные коды и доступные действия |
| `CrmAppointmentService` | позиция, внешний ref строки при наличии, externalServiceId, исторические название/длительность/цена |
| `CrmAppointmentMaster` | позиция, externalMasterId, историческое имя, собственный интервал/цена/длительность при наличии |
| `CrmAvailability` | точные условия: проект, список услуг, master scope, дата/период и контекст записи; доступные локальные начала либо подтверждённые интервалы, observedAt, фактическое покрытие и полнота |
| `CrmFinancialContext` | внешняя ссылка на продажу, её строки, итоги, платежи, наблюдение и полнота |

Цена предложения и цена исторического визита не подменяют друг друга. Неустановленная связь конкретной услуги с мастером не вычисляется декартовым произведением списков. Групповой визит не сводится к первому клиенту.

`offerState` — структурированное состояние предложения с известными признаками active/online; отсутствующий признак остаётся неизвестным. Цена/валюта/длительность и ограниченные нормализованные атрибуты провайдера, например `floating`, не подменяются наличием слотов. Непроверенный общий enum доступности не вводится. Ранее использованное название `availability` у предложения/таблицы/DTO заменено на `offerState` именно для отделения от `CrmAvailability`.

R01 DIKIDI определяет каталог и связи мастер–услуга. R11/R13/R14/R17 отражают доступность под конкретные условия; пустой результат не меняет offerState и не удаляет мастер/услугу/предложение. Совпадение owner service ID, online `id` и `company_service_id` наблюдалось у трёх услуг текущего проекта, но адаптер не переносит это предположение на неподтверждённые области ID.

У `CrmAvailability` master scope различает конкретного мастера и поиск среди мастеров; ответ сохраняет refs мастеров каждого результата и точный список запрошенных услуг. Запрос сочетания услуг не заменяется объединением/пересечением результатов отдельных запросов. Если источник вернул только начало, end остаётся неизвестным: вычисленная по каталогу длительность не становится подтверждённым интервалом доступности. Внутреннее представление явно различает начала и интервалы.

Запрошенные условия и фактическое покрытие ответа не смешиваются. Ближайшие даты R11/R14 могут выходить за запрошенный другим endpoint период и не доказывают полноту этого периода. Lookahead-даты сохраняются как наблюдавшиеся результаты с собственными условиями; не превращаются в COMPLETE для календаря. Пустая порция может означать отсутствие слотов лишь в пределах доказанной семантики/полноты запроса, а не отсутствие рабочего графика или предложения.

R17 `/ajax/newrecord/to_master_get_datetime/` подтверждён как чтение с `master_id`, повторяемым `services_id[]` и датой. Его `timeworks`, доступные начала и `dates_true` имеют разное назначение: рабочие интервалы могут присутствовать при пустых слотах. Вложенный график не заменяет полный снимок SCHEDULE без доказанного покрытия. `id`/`server_from` смены не дают соответственно доказательства стабильного ключа/часового пояса; R18 `get_times` остаётся только S-источником и не считается выполненным чтением.

`kind`: APPOINTMENT, RESERVATION, BLOCK, UNKNOWN. Отсутствие клиента не определяет kind: анонимный визит может быть APPOINTMENT. Вид устанавливается по доказанным признакам источника, включая контекст `is_time_reservation`, а не по удобному значению по умолчанию. Объекты с устойчивым ID сохраняются в crm.appointment с этим видом; служебные интервалы без устойчивого ID — снимком занятости в crm.workinterval с соответствующим kind, без выдуманного глобального externalId. Рабочие смены остаются интервалами графика.

История Client по умолчанию содержит только APPOINTMENT; резервы, блоки и UNKNOWN доступны как занятость/объекты для уточнения без подстановки Client. Команды move/cancel визита разрешены только для подтверждённого APPOINTMENT и его собственных доступных действий. Импорт hold не подтверждает создание визита; локальные этапы резервирования команды хранятся в crm.operation. Смена RESERVATION → APPOINTMENT допускается по явному подтверждению провайдера.

`clientReference` содержит `identityKind` и `externalId`; ключ CRM-клиента включает оба значения. Тип идентичности определяется доказанным контекстом endpoint/карточки, а не только строкой ID. Для ещё не установленного системного типа DIKIDI адаптер возвращает неполноту/недоступность соответствующего сценария и не подставляет тип проектного клиента. Online-ID и owner-ID мастеров/услуг также не отождествляются без доказанной связи; адаптер не использует неподтверждённую трансляцию для команды.

Запросы периодов используют полуоткрытые интервалы `[from, to)` в локальном времени проекта; адаптер переводит их в inclusive/иные границы конкретного endpoint. Длительности — секунды. Отсутствующий zoneId не заменяется часовым поясом процесса. `Instant` вычисляется только при однозначном времени и известном timezone.

Денежные значения — BigDecimal; публичные суммы — десятичные строки. У известной суммы с неизвестной валютой валюта остаётся NULL и показывается как неизвестная. Преобразование в float и неявное округление запрещены. Необработанное значение сохраняется как ограниченное диагностическое поле модели при ошибке нормализации, без публикации сырого тела ответа.

## 4. Persistence

Все новые таблицы находятся в схеме `crm` той же БД. Общий Hibernate `default_schema=dbo` сохраняется. У каждой CRM-сущности явно указан `@Table(schema = "crm", name = "...")`; для используемых таблиц связей/коллекций схема также задаётся явно. Нативный SQL указывает схему. JPA-сущности находятся в `server.persistence.entity.crm`, репозитории — в server.

У сущностей локальный `id` UUID, `createdat`, `updatedat`; у изменяемых сущностей — `version` для оптимистической блокировки. Версии, ревизии и поколения — монотонные BIGINT в соответствующей области. Внешние ID хранятся строками без преобразования в локальные PK. Длинное исходное имя не обрезается до ограничений общей карточки Client. Ниже перечислены значимые колонки; все физические имена, в том числе FK/индексы/ограничения, не содержат `_`.

| Entity / таблица | Основные поля и связи | Уникальность / основной индекс |
|---|---|---|
| CrmConnectionEntity / `crm.connection` | organizationid → dbo.organization, provider, login, secretciphertext, secretkeyid, credentialversion, status, lastcheckedat, lasterrorcode | Индекс organizationid/provider |
| CrmProjectEntity / `crm.project` | organizationid → dbo.organization, connectionid → crm.connection, workspaceid → dbo.workspace, provider, externalid, displayname, currency, zoneid, zonesource, zonerevision, contextversion, enabled | UNIQUE organizationid/provider/externalid; частичный UNIQUE workspaceid только для DIKIDI |
| CrmMasterEntity / `crm.master` | projectid, externalid, displayname, profile, employeeid → dbo.employee NULL, mappingupdatedat, mappingupdatedby → dbo.employee NULL, mappingpreviousid NULL, mappingreason NULL, lastobservedat | UNIQUE projectid/externalid |
| CrmClientEntity / `crm.client` | projectid, identitykind, externalid, displayname, contacts, normalizedphone, profile, clientid → dbo.client NULL, mappingupdatedat, mappingupdatedby → dbo.employee NULL, mappingpreviousid NULL, mappingreason NULL, lastobservedat | UNIQUE projectid/identitykind/externalid; индексы clientid и projectid/normalizedphone |
| CrmServiceEntity / `crm.service` | projectid, externalid, displayname, category, attributes, lastobservedat | UNIQUE projectid/externalid |
| CrmMasterServiceEntity / `crm.masterservice` | projectid, masterid, serviceid, price, currency, durationseconds, offerstate, attributes, lastobservedat | UNIQUE projectid/masterid/serviceid для подтверждённой модели одного предложения пары |
| CrmWorkIntervalEntity / `crm.workinterval` | projectid, masterid, startlocal, endlocal, kind, externalref NULL, attributes, lastobservedat | Индекс projectid/masterid/startlocal |
| CrmAppointmentEntity / `crm.appointment` | projectid, externalid, kind, crmclientid NULL, clientsnapshot, startlocal, endlocal, zoneid NULL, derivedzonerevision NULL, startat/endat NULL, states, providerstate, comment, sourceattributes, observationstate, lastobservedgeneration, observationcheckedat, missinggeneration NULL, financialsnapshot, financialobservedat, financialgeneration, financialcompleteness, lastobservedat | UNIQUE projectid/externalid; индексы projectid/startlocal и crmclientid/startlocal |
| CrmAppointmentServiceEntity / `crm.appointmentservice` | appointmentid, position, serviceid NULL, externalserviceid NULL, externallineid NULL, displayname, durationseconds NULL, price NULL, currency NULL, attributes | UNIQUE appointmentid/position |
| CrmAppointmentMasterEntity / `crm.appointmentmaster` | appointmentid, position, masterid NULL, externalmasterid, displayname, startlocal/endlocal NULL, durationseconds NULL, price NULL, currency NULL | UNIQUE appointmentid/position |
| CrmSyncStateEntity / `crm.syncstate` | projectid, statekind, writedomain, dataset, scopekey, scope, generation, claimtoken NULL, claimuntil NULL, connectionid, credentialversion, contextversion, zonerevision, status, lastattemptat, lastsuccessat, successfulcoverage, attemptcoverage, cursor, lasterrorcode | UNIQUE projectid/dataset/scopekey; служебные строки CONTROL и строки запросов SCOPE |
| CrmOperationEntity / `crm.operation` | projectid, initiatoremployeeid → dbo.employee, idempotencykey, requesthash, type, targetappointmentid NULL, expectedversion NULL, connectionid, credentialversion, contextversion, zonerevision, payload, status, effectstartedat NULL, externalreference NULL, result NULL, errorcode NULL | UNIQUE projectid/initiatoremployeeid/idempotencykey; индексы status/updatedat и projectid/initiatoremployeeid/status |

`profile`, `contacts`, `attributes`, `offerstate`, `clientsnapshot`, `providerstate`, `sourceattributes`, `financialsnapshot`, scope/coverage и нормализованный payload/result — ограниченные JSONB-документы с определённой моделью. Это не произвольный архив HTML, cookies или секретов. Основные фильтры, внешняя идентичность и связи не прячутся только в JSON. `states` в таблице выше обозначает отдельные строковые колонки appointmentstate, confirmationstate, attendancestate, paymentstate, а не один общий статус.

Суммы в реляционных полях — NUMERIC(19,4), валюта — строковый ISO-код при наличии. Непредставимое без потери значение даёт ошибку нормализации; оно не округляется молча. Локальные дата/время и `Instant` имеют разные колонки. `sourceattributes` сохраняет только нужные нормализованные метаданные источника.

### Ограничения и жизненный цикл

- Примеры имён: `uqprojectidentity`, `uqprojectdikidiworkspace`, `uqclientexternal`, `uqappointmentexternal`, `uqoperationkey`, `ixappointmentperiod`, `fkprojectworkspace`. В физических именах нет подчёркиваний.
- Обычный FK гарантирует существование объекта. Принадлежность подключения, проекта, Workspace и целей маппинга одной Organization проверяется server в транзакции; существующий FK не объявляется проверкой арендатора.
- Мастера/услуги/клиенты, указанные внутри визита и предложения, проверяются на принадлежность тому же проекту. Если полная карточка ещё не загружена, внешний ref и исторический снимок сохраняются без ложной ссылки на объект другого проекта.
- Client/Employee при импорте не создаются автоматически. Ссылки маппингов и поля их изменения не обновляются загрузкой профиля; обновление полной сущности устаревшим объектом не должно затереть ручную связь.
- Маппинги встроены в `crm.project`, `crm.master`, `crm.client`; универсальная таблица соответствий не нужна. Секрет хранится один раз у подключения; ключ шифрования находится вне БД. Cookies/CSRF в эти таблицы не сохраняются.
- Отключение не удаляет историю и не освобождает уникальную привязку для молчаливой замены проекта. Смена connectionid допустима только после проверки того же внешнего проекта и Organization.
- Строки услуг/мастеров визита без стабильного line ID атомарно заменяются как позиции подтверждённого полного снимка визита. Это не удаление визита. Неполная коллекция не заменяет полную.
- График без стабильных ID обновляется атомарно по подтверждённому мастеру/периоду; сбой порции сохраняет прежний снимок. Для визитов отсутствие в периоде никогда не заменяет явный статус отмены/удаления.
- Повторяющиеся даты смен сохраняются как отдельные наблюдённые интервалы; из совпадения дней недели не создаётся внешнее рекуррентное правило. Нулевые перерывы/пустые ресурсы не подтверждают семантику непустых случаев.
- Резервный ключ уникальности masterservice относится к исследованной модели предложения пары. Если источник показывает несколько вариантов одной пары, адаптер не схлопывает их: такая форма требует расширения ключа/модели перед включением.

Новые таблицы создаёт Hibernate `ddl-auto=update`. В 12.3 необходимо обеспечить существование схемы `crm` до DDL таблиц и проверить права на неё. Текущий bootstrap создаёт только dbo/system; общий default_schema, существующие миграции и dbo-таблицы не меняются ради переключения схемы. Проверяются пустая БД и уже работающая БД. Межсхемные FK направлены на существующие Organization/Workspace/Employee/Client; бизнес-таблицы не переносятся.

Ограничение одного проекта DIKIDI на Workspace задаётся частичным уникальным индексом PostgreSQL, включая отключённые проекты:

```sql
CREATE UNIQUE INDEX IF NOT EXISTS uqprojectdikidiworkspace
    ON crm.project (workspaceid)
    WHERE provider = 'DIKIDI';
```

Индекс ограничивает только строки, удовлетворяющие предикату; это соответствует [семантике partial unique index PostgreSQL](https://www.postgresql.org/docs/18/indexes-partial.html). Общий UNIQUE(workspaceid,provider) не вводится. Стандартное JPA UniqueConstraint не подменяет этот DDL. В 12.3 идемпотентная инициализация создаёт/проверяет индекс после создания таблиц Hibernate и до готовности CRM REST/воркеров; проверяются определение индекса и отсутствие конфликтующих данных, а не только его имя. При несоответствии CRM не активируется; автоматического удаления дублей или переноса Workspace нет. Файлы миграций не добавляются.

### Удаление существующих сущностей

CRM-проекции и журнал операций не каскадируются при удалении объекта dbo. Допустимые действия выполняются транзакционно с блокировкой удаляемой цели и повторной проверкой прав, чтобы конкурентное создание link не обошло проверку.

| Удаляемая цель существующего API | Правило CRM |
|---|---|
| dbo.client | SUPER_ADMIN может снять необязательные crm.client.clientid во всех затронутых проектах своей Organization и удалить Client, если нет других ограничений core. Внешние карточки, визиты и снимки остаются; mappingupdatedby/at фиксируют инициатора снятия, публикуются события затронутых Workspace и клиента |
| dbo.employee как текущий target crm.master.employeeid | При отсутствии исторических авторских ссылок текущие маппинги можно снять перед удалением, сохранив внешних мастеров и метаданные изменения |
| dbo.employee как mappingupdatedby или operation.initiatoremployeeid | RESTRICT: понятный 409 CRM_RESOURCE_IN_USE; исторический автор не обнуляется. Вместо удаления используется существующее отключение Employee.user.enabled (dbo.users.isenabled=false), профиль Employee сохраняется |
| dbo.workspace с любым crm.project | RESTRICT и 409 CRM_RESOURCE_IN_USE, в том числе для отключённого проекта; история и границы доступа не переносятся автоматически |
| CRM connection/project | Физическое удаление с историей не предоставляется; используется отключение с сохранением ссылок |

Nullable у mappingupdatedby означает отсутствие известного автора, а не разрешение стирать уже записанного автора. Снятие target-мэппинга и сохранение автора изменения — разные операции. mappingpreviousid сохраняет UUID прежней цели как значение без FK, mappingreason — LINK/RELINK/UNLINK/TARGET_DELETED; вместе с инициатором и временем это метаданные последнего изменения связи, не обещание полного журнала всех изменений. Действующие ограничения удаления сообщений/аккаунтов и запрет SUPER_ADMIN удалить себя сохраняются.

Защита существующих DELETE-маршрутов и её регрессия добавляются до включения соответствующих FK: WorkspaceService/EmployeeService — уже в 12.3 с проектами и маппингами мастеров; ClientService — в 12.4 с client links; проверка ссылки operation.initiatoremployeeid — в 12.6. Необработанная FK-ошибка не считается публичным контрактом. Неудача любой core-проверки откатывает снятие CRM-маппингов и события вместе с удалением.

### Контекст задач и интерпретация времени

`project.contextversion` увеличивается при смене connectionid, включении/отключении проекта и изменении настройки timezone. `connection.credentialversion` увеличивается при изменении login/secret и разрешения доступа (disable/enable); проверки здоровья сами по себе её не меняют. Поэтому одинаковые credentialversion двух разных подключений не делают их взаимозаменяемыми.

Каждая задача/страница и операция сохраняет ContextSnapshot до I/O. Применение ответа проверяет текущее соответствие connectionid/credentialversion/contextversion и доменного поколения; после смены контекста старый ответ не меняет проекции, покрытие или возможности. Продолжение пагинации не переключается на новое подключение незаметно.

REQUESTED с устаревшим контекстом отклоняется до эффекта с CRM_CONTEXT_CHANGED. Повторная проверка/новая команда выполняются явно по актуальным условиям. IN_FLIGHT/UNKNOWN сохраняет исходный контекст и не переносится на новый доступ для повторного изменения. Безопасная сверка может использовать новый подтверждённый доступ к тому же проекту; её контекст сохраняется в результате, а исходные refs и payload не переписываются.

Первое назначение или исправление timezone увеличивает `zonerevision` вместе с contextversion. Исходные startlocal/endlocal не меняются. Вычисленные startat/endat и сохранённый zoneid актуальны только при совпадении derivedzonerevision с project.zonerevision и неизменности исходных local-полей. Старые значения сразу подавляются в DTO и не используются для команд, даже если физический пересчёт ещё не завершён.

Пересчёт идёт порциями с условной записью по актуальной ревизии зоны и версии локальных значений; при новой коррекции предыдущий результат отбрасывается. Неоднозначное/несуществующее локальное время остаётся без UTC с причиной. Пересчёт не обновляет lastobservedat, финансовую свежесть или покрытие провайдера. Доступность по старой зоне инвалидируется, ещё не начатые команды со старым контекстом отклоняются. Новая команда создания использует текущую проверенную зону; команды над визитом требуют актуального времени этого визита. Смена фактической зоны проекта во времени и миграция исторических трактовок не подменяются обычным исправлением настройки.

### Единый порядок записи и наблюдаемость

Порядок записи определяется доменом данных проекта, а не отдельным фильтром/периодом. Домен APPOINTMENTS общий для APPOINTMENTS, CLIENT_HISTORY, чтения одного объекта и проекций после сверки команды. FINANCE владеет financialsnapshot, financialcompleteness, financialobservedat, financialgeneration, paymentstate и финансовой сводкой. Каталог, клиенты и график используют свои домены CATALOG, CLIENTS, SCHEDULE.

В crm.syncstate выделяется одна служебная строка CONTROL на проект/домен: dataset имеет внутреннее значение `WRITER:` + имя домена, scopekey равен `CONTROL`. Публичные строки SCOPE сохраняют обычные dataset/scopekey и собственное покрытие; служебная строка не показывается как набор провайдера. Новая таблица очереди/версий для этого не создаётся.

Воркер атомарно получает claimtoken/claimuntil и увеличивает общее поколение до исходящего чтения. Полный цикл чтения, включая страницы, сериализован в пределах проекта/домена; сетевой I/O выполняется вне DB-транзакции. Применение порции проверяет действующий claim, поколение и ContextSnapshot; срок claim проверяется по времени БД. Истёкшая аренда чтения допускает новый безопасный запрос с новым поколением; старый воркер уже не применяет ответ. Время прихода ответа и обычный JPA version не заменяют эту проверку.

В реализованном 12.3 настройка подключения и проверка/применение worker-контекста блокируют connection перед project; снимок сверяется после получения блокировок и refresh. Страница мастеров и её dataState читаются одной транзакцией под SHARE-lock проекта, чтобы старые строки не получали generation/CURRENT от следующей загрузки. Участники ручного сопоставления блокируются по employeeid в стабильном порядке через PostgreSQL `FOR NO KEY UPDATE`: это защищает от DELETE и совместимо с `KEY SHARE` внешнего ключа автора аудита.

Маски присутствующих полей и полноты коллекций сохраняются. Владельцы полей определены явно: авторитетные интервалы/статусы/коллекции берутся из подтверждённой detail/journal-проекции; ограниченная HTML-история дополняет отсутствующие сведения, но не затирает подтверждённую detail-проекцию спорными подписями/нулевыми шаблонами. Если связь/приоритет источников не доказаны, поле остаётся неопределённым и запрашивается авторитетное чтение.

APPOINTMENTS/CLIENT_HISTORY не обновляют финансовые поля даже при наличии payment в R02. Они могут объединить запрос на отдельное свежее чтение FINANCE. Финансовый воркер получает собственное поколение до I/O и заново читает актуальные totals R02 и доступные F01–F03; старое payment-evidence не получает новое поколение только потому, что дошло до финансовой очереди позже. Свежесть финснимка и полнота его секций независимы от календаря.

Подтверждение/неопределённость внешней команды инвалидирует затронутые доменные чтения и проекцию цели; ответ команды не применяется как безусловный старый полный снимок. До свежей сверки UI видит необходимость обновления. Внешние изменения других пользователей остаются предметом последующей синхронизации, а не гарантией внешнего CAS.

`observationstate`: OBSERVED, NOT_OBSERVED, UNKNOWN. Валидное положительное наблюдение сохраняет lastobservedat/lastobservedgeneration и OBSERVED, не обещая полноту всех полей. Только после проверки всех страниц полного запроса его точные условия могут пометить прежние подходящие, но не встреченные объекты NOT_OBSERVED с observationcheckedat/missinggeneration. Проверяется, что объект всё ещё соответствует scope и не обновлён более новым поколением. Частичная/ошибочная выборка и финансовое чтение не дают отрицательного наблюдения.

NOT_OBSERVED означает необходимость сверки, не CANCELLED/DELETED. Строка остаётся видимой с lastObservedAt и requiresVerification; это имеет приоритет над CURRENT диапазона. Команды по такой строке недоступны до положительной авторитетной проверки. Если lookup не подтверждён, неопределённость сохраняется. Контролируемые локальные инвалидации после команды также показываются как UNKNOWN/требующие сверки, без изменения бизнес-статуса по догадке.

### Финансовый снимок

`financialsnapshot` содержит `schemaVersion`, `saleReference`, `lines`, `totals`, `payments`, `warnings`. Строка продажи разделяет свой externalId и externalServiceId, исходную цену, скидку в процентах/сумме и итог. Итоги разделяют cost/paid/debt; платёж содержит собственный ref, дату и фактические компоненты метода/кассы. Каждая известная сумма имеет валюту либо явное отсутствие валюты.

Неизвестное поле остаётся NULL/неизвестным по контракту, а не нулём. Полнота снимка относится отдельно к строкам, итогам и платежам. Невозможность получить список продаж не стирает уже подтверждённую продажу конкретного визита. Новые таблицы продаж, оплат, касс или склада в этапе 12 не создаются.

## 5. Публичные DTO

Все resource ID в публичном API — локальные UUID; внешний ID возвращается отдельным `externalId`. Сервер определяет Organization из пользователя и проверенных ресурсов, не доверяет organizationId из тела. Секреты, cookie, CSRF, HTML и внутренние provider payload не возвращаются.

| DTO | Поля |
|---|---|
| CrmConnectionDto | id, provider, loginHint, credentialConfigured, status, lastCheckedAt, безопасный errorCode, version |
| CrmProjectDto | id, connectionId, workspaceId, provider, externalId, displayName, currency, zoneId, zoneSource, zoneRevision, contextVersion, enabled, connectionState, capabilities, version |
| CrmMasterDto | id, projectId, externalId, displayName, employeeId NULL, mapping metadata, version |
| CrmClientDto | id, projectId, externalId, identityKind, displayName, контакты, clientId NULL, mapping metadata, version |
| CrmServiceDto / CrmServiceOfferDto | локальные ID услуги/мастера, внешние refs, название, цена/валюта, durationSeconds, offerState и подтверждённые нормализованные атрибуты; без вывода о свободных слотах |
| CrmAvailabilityDto | projectId, точные serviceIds и master scope, дата/период и контекст запроса, локальные начала либо подтверждённые интервалы с мастерами, observedAt, dataState и фактическое покрытие; неизвестный end не вычисляется |
| CrmAppointmentDto | id, projectId, workspaceId, externalId, kind, crmClientId/clientId NULL, display-снимки клиента, localStart/localEnd, zoneId, zoneRevision, derivedZoneRevision NULL, startAt/endAt NULL, services[], masters[], отдельные states, observationState, lastObservedAt, requiresVerification, actions, финансовая сводка с собственным dataState, dataState, version |
| CrmFinancialContextDto | нормализованный снимок конкретного визита, dataState и полнота его секций |
| CrmDataStateDto | lastAttemptAt, lastSuccessAt, coverage[], freshness, connectionState, lastErrorCode, warnings |
| CrmPageDto<T> | items, nextCursor, hasMore, dataState; hasMore относится к локальной странице, dataState — к полноте источника |
| CrmOperationDto | id, projectId, type, appointmentId NULL, initiatorEmployeeId, idempotencyKey, contextVersion, zoneRevision, status, createdAt/updatedAt, errorCode NULL, безопасный результат/ссылки |

`freshness`: NEVER_LOADED, CURRENT, STALE. Текущая свежесть определяется политикой обновления данного набора; COMPLETE относится к известному покрытию и не означает бессрочную актуальность. `total` не публикуется как полный счётчик источника, если полнота неизвестна.

В DTO визита zoneId/zoneRevision отражают текущую интерпретацию проекта; UTC-поля публикуются только при актуальной derivedZoneRevision. При несовпадении они равны NULL с причиной TIMEZONE_RECALC_REQUIRED; сохранённые старые UTC не выдаются как актуальные. Неопределённое наблюдение объекта и неактуальная интерпретация времени отражаются независимо от свежести диапазона. `actions` содержит доступность move/cancel и reasonCode: это пересечение прав пользователя, возможностей проекта, kind, актуальности наблюдения/времени, отсутствия конфликтующей незавершённой операции и подтверждённых can_edit/can_cancel конкретного объекта. UI не восстанавливает эти разрешения только из project capabilities.

Состояния попытки синхронизации: QUEUED, RUNNING, SUCCEEDED, PARTIAL, FAILED. Покрытие хранится отдельно от состояния исполнения. `lastSuccessAt` относится к последней принятой валидной порции; только доказанное покрытие этой порции может обновить successfulcoverage. Сбой другой страницы не добавляет её период в покрытие, а `attemptcoverage` и ошибка отражают незавершённую загрузку. Время наблюдения отдельных объектов не подменяет полноту набора.

Публичные курсоры локальных списков привязаны к проекту, фильтру и стабильной сортировке. Их перенос в другой scope не даёт доступ к данным: выполняется обычная проверка прав, несовпадающий курсор отклоняется. Исходные provider-курсоры не используются как разрешение доступа.

## 6. REST

Маршруты ниже — контракт реализации, не уже работающие endpoints. Списки локальных данных читают БД. Обновление источника запускается явно/планировщиком; GET списка не запускает неограниченный импорт.

### Подключение и привязка

| Метод и маршрут | Вход / результат | Доступ |
|---|---|---|
| GET `/api/crm/types` | Список доступных для подключения `CrmType`, полученный из `AvailableCrmType`; в 12.3 `["DIKIDI"]` | SUPER_ADMIN |
| GET/POST `/api/crm/connections` | Список / provider, login, secret → ConnectionDto, создание 201 | SUPER_ADMIN |
| GET/PATCH `/api/crm/connections/{connectionId}` | Чтение / явное изменение логина, секрета или enabled с expectedVersion | SUPER_ADMIN |
| POST `/api/crm/connections/{connectionId}/verify` | Проверка авторизации, без команд визитов | SUPER_ADMIN |
| GET `/api/crm/connections/{connectionId}/projects` | Перечисление только при LIST_PROJECTS | SUPER_ADMIN |
| POST `/api/crm/connections/{connectionId}/project-checks` | externalProjectId → проверенные метаданные доступного проекта | SUPER_ADMIN |
| GET `/api/crm/workspaces/{workspaceId}/projects` | Список привязок с необязательным фильтром provider; для DIKIDI 0 или 1 элемент | Доступ к Workspace |
| POST `/api/crm/workspaces/{workspaceId}/projects` | connectionId, externalProjectId, optional zoneId → привязка 201 | SUPER_ADMIN |
| PATCH `/api/crm/projects/{projectId}` | expectedVersion, enabled, явный zoneId override; внешний ID/Workspace не подменяются | SUPER_ADMIN |
| PUT `/api/crm/projects/{projectId}/connection` | connectionId, expectedVersion → явная смена доступа после проверки того же внешнего проекта | SUPER_ADMIN |

Для подключения отсутствие secret в PATCH сохраняет секрет; явный новый непустой secret меняет его и credentialversion. Пустой secret отклоняется. Удаление секрета не совмещается неявно с отключением. При смене login требуется явно указать секрет для нового доступа. PATCH не изменяет provider/Organization.

Состояния подключения: CONFIGURED, CONNECTED, REAUTH_REQUIRED, DEGRADED, DISABLED. PATCH enabled=false переводит его в DISABLED; enabled=true — в CONFIGURED до повторной проверки; оба изменения повышают credentialversion, чтобы прежнее разрешение доступа не восстановилось после disable/enable. Проверка DISABLED-подключения не включает его обратно скрытым побочным эффектом. Статусы авторизации обновляются только для актуальной credentialversion.

Повторная привязка того же проекта к тому же Workspace возвращает существующую связь после проверки доступа; конфликт другой связи — 409. Смена учётных данных не создаёт проект повторно. Запрос с другим connectionId для существующего проекта требует явного подтверждения смены доступа и актуальной версии проекта; это не скрытый эффект повторного POST привязки.

### Просмотр и сопоставления

| Метод и маршрут | Результат / вход |
|---|---|
| GET `/api/crm/projects/{projectId}/masters` | Страница мастеров |
| GET `/api/crm/projects/{projectId}/clients` | Страница внешних клиентов; поиск по имени/телефону/состоянию связи |
| GET `/api/crm/projects/{projectId}/services` | Каталог и доступные предложения; фильтры мастера/услуги |
| GET `/api/crm/projects/{projectId}/work-intervals` | Интервалы выбранного периода/мастеров с покрытием |
| GET `/api/crm/projects/{projectId}/appointments` | Визиты периода; фильтры внешнего клиента/Client/мастера/состояний |
| GET `/api/crm/appointments/{appointmentId}` | Визит и разрешённые детали |
| GET `/api/crm/appointments/{appointmentId}/financial-context` | Сохранённый финансовый снимок и его собственное состояние |
| GET `/api/clients/{clientId}/crm/appointments` | Объединённая история только доступных Workspace, с покрытием по проектам |
| GET `/api/crm/clients/{crmClientId}/client-candidates` | Кандидаты Client текущей Organization; автоматической связи нет |
| PUT `/api/crm/clients/{crmClientId}/client-link` | clientId, expectedVersion → подтверждение/замена связи по матрице прав |
| DELETE `/api/crm/clients/{crmClientId}/client-link` | expectedVersion → снятие связи руководителем |
| POST `/api/crm/clients/{crmClientId}/client` | Подтверждённые firstName/lastName/phoneList и expectedVersion → Client + связь, 201 |
| PUT/DELETE `/api/crm/masters/{crmMasterId}/employee-link` | employeeId при PUT, expectedVersion → сопоставление/снятие руководителем |

Все маршруты проверяют ресурс и его Workspace; путь Client не отменяет фильтрацию визитов. Передача projectId/Workspace фильтром не расширяет права. Первый link доступен участнику Workspace; замена/снятие — supervisor/SUPER_ADMIN. Создание Client и link выполняются одной транзакцией: проигравший конкурентный запрос не оставляет лишнюю общую карточку. Для DELETE версия передаётся явно в query, а не угадывается сервером.

### Обновление, доступность и команды

| Метод и маршрут | Вход / результат |
|---|---|
| POST `/api/crm/projects/{projectId}/refresh` | Разрешённый dataset, период/клиент/визит при необходимости → состояние scope, 202; одинаковые активные scope объединяются |
| GET `/api/crm/projects/{projectId}/sync-state` | Состояние разрешённых наборов/периодов |
| POST `/api/crm/projects/{projectId}/availability` | Выбранные услуги, мастер при необходимости, период → актуальная доступность провайдера; этот запрос не резервирует время |
| POST `/api/crm/projects/{projectId}/appointments` | Idempotency-Key, contextVersion, выбранный внешний CRM-клиент через локальный ID, услуги/мастер, localStart, настройки визита → OperationDto |
| POST `/api/crm/appointments/{appointmentId}/move` | Idempotency-Key, contextVersion, expectedVersion, целевое localStart/мастер → OperationDto |
| POST `/api/crm/appointments/{appointmentId}/cancel` | Idempotency-Key, contextVersion, expectedVersion, причина при поддержке → OperationDto |
| GET `/api/crm/operations/{operationId}` | Текущее состояние своей/доступной операции |
| GET `/api/crm/projects/{projectId}/operations` | Страница операций; по умолчанию свои, фильтр active включает REQUESTED/IN_FLIGHT/UNKNOWN. Чтение операций других сотрудников — только supervisor/SUPER_ADMIN |
| GET `/api/crm/projects/{projectId}/operations/by-key/{idempotencyKey}` | Поиск принятой операции текущего инициатора без известного operationId; не запускает внешнее действие |
| POST `/api/crm/operations/{operationId}/reconcile` | Только безопасная сверка существующей неопределённой операции, без повторного изменения |

Refresh-наборы спецификации: MASTERS, SERVICES, CLIENTS, WORK_INTERVALS, APPOINTMENTS, CLIENT_HISTORY, FINANCIAL_CONTEXT. В 12.4.1 реализован CATALOG; MASTERS и SERVICES — его совместимые алиасы с одним WRITER:CATALOG, без независимой записи пересекающихся коллекций. Остальные наборы пока возвращают CRM_CAPABILITY_UNVERIFIED. FINANCIAL_CONTEXT требует конкретный appointmentId; CLIENT_HISTORY — crmClientId. Пользовательские диапазоны и число запросов ограничиваются server. Эффективные лимиты фиксируются и проверяются в 12.3/12.4; они не объявляются уже найденными лимитами DIKIDI.

Локальный GET services возвращает до 100 услуг с предложениями и dataState CATALOG. Фильтры masterId/serviceId — локальные UUID этого проекта; includeNotObserved=true включает сохранённые исторические строки. Cursor привязан к проекту, фильтрам и ревизии каталога; изменение ревизии требует повторного чтения с первой страницы (409 CRM_CONTEXT_CHANGED). Полный каталог помечает отсутствующие строки NOT_OBSERVED, сохраняя ID, Employee links и историю; частичный ответ не меняет наблюдаемость отсутствующих строк. Отсутствие цены/длительности в новой строке ответа сохраняет последнее значение с предупреждением, явный NULL очищает его. Неизвестная категория не выдумывается из публичного online-каталога. Цена конкретного предложения — десятичная строка, durationSeconds — секунды; доступность слотов из них не выводится. Старое успешное MASTERS-наблюдение 12.3 не подтверждает загрузку услуг.

GET masters также возвращает до 100 строк; cursor версии 3 привязан к проекту, поиску, фильтрам и ревизии каталога и продолжает чтение после последнего мастера по externalId. Номер страницы и предел 5000 локальных строк не используются: сохранённая история может быть больше одного внешнего ответа. Старые cursor версий 1/2 требуют начать чтение заново с CRM_CONTEXT_CHANGED. UI читает первую страницу и догружает остальные кнопкой «Показать ещё мастеров»; при смене ревизии начинает заново, а при временном сбое следующей страницы сохраняет уже показанные строки. Поздние ответы/ошибки прежнего чтения не применяются к обновлённому списку.

Если в базе 12.3 сохранён успешный MASTERS, а первый новый CATALOG ещё не принят, dataState мастеров сохраняет прежние lastSuccessAt/completeness со свежестью STALE и состоянием/ошибкой новой попытки. Это не переносит успешность на услуги: CATALOG/SERVICES остаются NEVER_LOADED до собственного принятого результата. После успешного чтения каталога используется его наблюдение.

Команды требуют действующей возможности, прав Workspace и подходящего timezone. Для нового клиента, ещё не существующего в DIKIDI, автоматическое создание внешней карточки пока не обещается: без доказанного сценария такая команда отклоняется с CONFIGURATION_REQUIRED/UNVERIFIED. Общий Client сам по себе не является внешним CRM-клиентом.

Запрос бронирования использует актуальное предложение мастера, но цена не вычисляется по float и не подменяется клиентом произвольной суммой. Правило финального подтверждения цены/длительности уточняется доказанным протоколом команды; незаметное изменение условий недопустимо. Перенос сохраняет прочие поля, услуги и финансовую связь; неподдержанное изменение мастера не включается автоматически вместе с изменением времени.

## 7. Идемпотентность и состояния операции

Ключ Idempotency-Key — UUID, обязательный для каждой команды изменения. Его область: проект, инициатор и ключ. Один ключ с тем же нормализованным запросом возвращает существующую операцию после проверки прав; с другим содержимым — 409. Совпадение ключа у разных инициаторов не обещает дедупликацию их разных команд.

До первого POST UI сохраняет ключ в устойчивом хранилище незавершённых действий, разделённом по Organization/инициатору/проекту; после ответа связывает его с operationId. Не сохраняются секреты подключения, HTTP-тела провайдера и персональные данные. При reload/потере ответа сначала выполняется by-key lookup или чтение активных операций. Повтор POST допустим только с прежним ключом и тем же запросом; если исходное тело больше недоступно, lookup не подменяется новым POST с новым ключом.

Ответ by-key 404 не доказывает, что ещё выполняющийся первый POST никогда не будет принят, и сам по себе не освобождает ключ для слепого повторения действия. UNKNOWN считается активной операцией. UI не заменяет ключ из-за timeout, закрытия панели, logout или reload; другой пользователь не видит ключи прежнего инициатора. После получения терминального результата указатель UI можно убрать, но серверный ключ остаётся связанным с операцией и не используется для нового намерения. Нетерминальные операции не очищаются фоновым retention.

При повторе с прежним ключом server сначала проверяет текущую авторизацию ClientBus/доступ к операции и совпадение нормализованного запроса. Если операция уже существует, возвращает её до проверок текущих возможностей/контекста новой команды: смена подключения или timezone не должна мешать безопасно прочитать уже принятый результат. Это не разрешает повторить внешний запрос.

Операция фиксируется до внешнего изменения вместе с ContextSnapshot. Повторная команда для того же существующего визита проверяет ожидаемую локальную версию и незавершённые операции; несовместимые изменения сериализуются. Локальная версия не объявляется версией DIKIDI и не устраняет внешнюю конкуренцию. Worker получает право начать эффект единственным атомарным переходом REQUESTED → IN_FLIGHT с проверкой версии и контекста; проигравший конкурентный worker не делает I/O. Восстановление задач после сбоя не выдаёт повторное право выполнить этот переход для уже начатого эффекта.

| Состояние | Значение и переходы |
|---|---|
| REQUESTED | Принята локально; до внешнего эффекта можно отклонить по правам, настройкам или конфликту |
| IN_FLIGHT | Намерение начать изменяющий запрос записано отдельным durable checkpoint до сетевого I/O |
| CONFIRMED | Есть однозначное подтверждение внешнего целевого состояния |
| REJECTED | Доказан отказ без требуемого эффекта; timeout не является таким доказательством |
| UNKNOWN | Запрос мог изменить источник; разрешена только сверка, слепой повтор запрещён |

Переходы: REQUESTED → IN_FLIGHT/REJECTED; IN_FLIGHT → CONFIRMED/REJECTED/UNKNOWN; UNKNOWN → CONFIRMED/REJECTED только по достаточному доказательству. Оставшийся IN_FLIGHT после аварии обрабатывается как UNKNOWN, а не автоматически отправляется снова. Если ответ резерва потерян, новый резерв не создаётся ради «восстановления».

Checkpoint не откатывается вместе с ошибкой следующего сетевого запроса. Возврат 202 означает локальное принятие, не подтверждённую запись. Повтор запроса возвращает 202 для незавершённой операции и 200 для терминальной, с тем же ID. Если исходная команда не прошла допуск, внешний вызов не производится.

`externalreference` содержит только необходимые подтверждённые refs объекта/резерва/ключа; не cookie/CSRF. При неопределённом результате UI показывает состояние и проверку в источнике. Пока нет однозначного способа сверки для конкретной команды, её возможность не включается; ручная догадка не переводит UNKNOWN в CONFIRMED. Наличие одноимённого метода стороннего SDK не закрывает это условие.

## 8. HTTP-ошибки и доступ

Сохраняется существующая форма ошибки ClientBus: `{ "code": "...", "message": "..." }`. Детали внешнего ответа, пароль и персональные поля не копируются в сообщение.

| HTTP | Код / ситуация |
|---|---|
| 400 | INVALID_REQUEST: неверные параметры, период, пустой секрет или несовпадающий курсор |
| 401 | Только отсутствие/истечение авторизации самого ClientBus |
| 403 | ACCESS_DENIED: нет текущих прав ClientBus на действие/ресурс |
| 404 | NOT_FOUND: разрешённый ресурс или привязка не найдены; детали чужих ресурсов не раскрываются |
| 409 | CRM_VERSION_CONFLICT, CRM_MAPPING_CONFLICT, CRM_PROJECT_BINDING_CONFLICT, CRM_IDEMPOTENCY_CONFLICT, CRM_OPERATION_CONFLICT |
| 409 | CRM_CONTEXT_CHANGED для ещё не начатого действия со старой привязкой/зоной; CRM_RESOURCE_IN_USE для явно запрещённого удаления связанной сущности |
| 409 | CRM_CAPABILITY_UNVERIFIED, CRM_UNSUPPORTED, CRM_CONFIGURATION_REQUIRED, CRM_REAUTH_REQUIRED, CRM_PROVIDER_FORBIDDEN при недоступном допуске внешнего действия |
| 429 | CRM_RATE_LIMITED с Retry-After при известном времени |
| 502 | CRM_INVALID_RESPONSE / CRM_CONTEXT_MISMATCH при прямой проверке источника |
| 503 | CRM_TEMPORARILY_UNAVAILABLE при прямом безопасном обращении |
| 503 | CRM_STORAGE_NOT_READY до проверки схемы crm и её обязательных индексов |

Локальный GET может вернуть 200 с сохранёнными данными и STALE/REAUTH_REQUIRED, если действующие права ClientBus разрешают чтение. Состояние внешней сессии не вызывает 401 ClientBus. После принятия операции её ошибка/UNKNOWN читается через OperationDto; сетевой timeout после начала эффекта не маскируется обычным ответом «не удалось, повторите».

Матрица действий определена в [CRM_SEMANTICS.md](CRM_SEMANTICS.md). Право SUPER_ADMIN на управление CRM не меняет запрет отправки сообщений из ClientBus. Права проверяются по текущему состоянию БД, а не только по сохранённой UI-роли или прежнему участию в Workspace.

## 9. Outbox/SSE и UI

В 12.3 добавлено событие `crm.invalidated` в существующий UI outbox/SSE, whitelist `UiEventTypes`, OpenAPI-перечень и UI-обработчик. Scope — Workspace, аудитория — текущие пользователи с доступом к нему, включая SUPER_ADMIN своей Organization. Чатовый предикат «назначенный EMPLOYEE» для этой аудитории не используется.

В 12.3 используется существующий workspace-envelope: он инвалидирует CRM-панель текущего Workspace целиком. Дополнительные projectId/datasets/периоды в payload пока не введены; их детализация относится к нескольким наборам данных 12.4. В событии нет секрета, полного профиля, суммы платежа или документа продажи. Изменение БД и запись outbox атомарны; публикация не происходит до commit. Импорт порции даёт агрегированное событие, а не событие для каждой строки и не персональные уведомления.

UI перечитывает только затронутые представления с coalescing и текущим lifetime. Смена Workspace/учётной записи, отзыв доступа и поздний ответ прежнего запроса не возвращают старые данные в новый контекст. После потери SSE выполняется ограниченная повторная загрузка текущего экрана, а не полная синхронизация всех проектов.

### 9.1. UI-дополнение к 12.4.1

02.11.2026 пользователь принял [HTML-макет](ui/mockups/12-crm-settings.html) и его включение в текущий подпункт «Каталог». [Ролевые сценарии и критерии](ui/mockups/12-crm-ergonomics-review.md) фиксируют целевое поведение. Перенос в React/API завершён и проверен 03.11.2026. Ниже зафиксированы реализованные дополнения.

- Аккаунты Organization настраивает SUPER_ADMIN в Settings; проекты привязываются в WorkspaceDetails. Рабочий раздел CRM показывает услуги/мастеров всем разрешённым читателям. Режим связей мастер–Employee доступен только SUPER_ADMIN/супервизору текущего Workspace; обычный EMPLOYEE не видит административных контролов и показателей связи.
- Название аккаунта из макета требует дополнения `CrmConnectionDto`, create/update requests и `crm.connection`: реализован необязательный `displayName` до 80 символов, физическая колонка `displayname`, без миграций. У существующих аккаунтов допустим NULL с отображением провайдера и loginHint. Это подпись, не идентификатор и не секрет; переименование проверяет expectedVersion, не меняет credentialVersion, состояние внешнего входа или свежесть каталогов. Поле сохраняется в текущем коде.
- Поиск услуг/мастеров/сотрудников и фильтры должны учитывать весь разрешённый набор, а не только загруженную страницу. GET masters принимает q/unlinked/includeNotObserved, GET services — q/masterId/serviceId/includeNotObserved. q ограничен 160 символами; поиск регистронезависим, символы SQL LIKE трактуются буквально. Фильтры входят в scope курсора; страницы возвращают revision. Предложение услуги содержит masterDisplayName. Сохраняются серверные ограничения страниц, ревизии каталога, явная догрузка, наблюдаемость и dataState. Счётчики не выдают длину загруженной порции за полный объём данных.
- Автосохранение и отмена связи используют существующие versioned PUT/DELETE Employee-link. Отмена — новое условное изменение с версией после успешного сохранения; она не должна перезаписывать более позднюю правку. Ошибки и пустые состояния различаются, фокус сохраняет рабочую позицию. Выбор кандидатов использует действующую AccessPolicy, а не захардкоженную фильтрацию демонстрационного HTML.
- Нажатие «Обновить» на рабочем экране должно иметь явную семантику загрузки CATALOG; перечитывание локальных GET после invalidation не считается новым чтением DIKIDI. Сохраняются различия доступа к провайдеру, последней успешной загрузки и полноты набора. UI не выводит CURRENT из успешной проверки аккаунта и не переносит наблюдение мастеров на ещё не загруженные услуги.
- Переходы и возврат после восстановления адресуют конкретные account/project/Workspace. Истёкшая авторизация, выключенный аккаунт и выключенное обновление проекта требуют разных действий; server повторно проверяет права и версии. Отключение проекта сохраняет привязку и историю и не освобождает DIKIDI-ограничение Workspace.
- При переносе сохраняются отмена устаревших запросов и защита от поздних ответов. Дополнительно проверяются отзыв прав при догрузке страницы, сохранение догруженных страниц при фоновом перечитывании той же ревизии и очистка прежней ошибки после успешного восстановления 409.

Прикладная приёмка завершена отдельно от 33 сценариев HTML; проверены: ролевые UI/browser-проверки, каталог с реальной пагинацией, восстановление/версии/ошибки, релевантные BE-тесты для дополнений контракта и согласованная сборка UI/server. Демонстрационные AMO/BITRIX, большой каталог и переключатель ошибки сохранения в приложение не переносятся как продуктовые возможности. Расписание, визиты, финансы и клиентский контекст остаются в соответствующих последующих подпунктах.

## 10. Проверка спецификации и этапы реализации

| Сценарий | Ожидаемое поведение |
|---|---|
| Известные типы DIKIDI/AMO/BITRIX, доступен только DIKIDI | UI получает только DIKIDI; AMO/BITRIX отклоняются BE с CRM_UNSUPPORTED до сохранения секрета/обращения к провайдеру; неизвестный код даёт INVALID_REQUEST |
| Повторный импорт клиента/визита | Тот же внешний ключ проекта, без дублей; ручной link сохраняется |
| Смена login/secret/connection для того же проекта | Тот же project UUID, Workspace, данные и links; прежняя сессия не используется |
| Другой проект с теми же строками client/master ID | Отдельные записи; связи и запросы не смешиваются |
| Телефон совпал у нескольких Client | Кандидаты без автоматического объединения |
| Имя CRM не разделено на имя/фамилию | Исходное имя сохранено; поля нового Client подтверждаются явно |
| Одна услуга у двух мастеров / старый визит | Сохранены разные предложения и исторические значения |
| Повторное чтение: M1–S1, M1–S2, M2–S2, M2–S3 | Два мастера, три услуги, четыре предложения; S2 не дублируется. Одинаковые 10 000 RUB / 60 минут у S2 не выдаются за проверку различающихся условий |
| R01 содержит активную online-услугу S1, R11/R13/R14 не дали слотов | Предложение и маппинг мастера сохранены; отсутствие слотов не превращается в inactive/offline |
| По S2 и S3 отдельно есть начало, а по сочетанию S2+S3 результат пуст | Сочетание имеет самостоятельный результат; отдельные начала не дают подтверждённую совместную запись и не создают выдуманный end |
| R11 содержит 2 декабря, график/R13 запрошены до 30 ноября | Ответы сохраняют собственные scope/coverage; дата вне периода не доказывает ошибку графика и не расширяет COMPLETE календаря |
| В графике 11 повторяющихся смен двух мастеров, перерывы нулевые | Сохраняются конкретные интервалы; не создаётся weekly-rule и не объявляются проверенными непустые перерывы/ресурсы |
| R17: у M1 рабочие интервалы 09:55–10:05, но нет слотов S1/S2; у M2 те же смены при S2 и S2+S3, но только S2 даёт начало 12:00 | График не подменяет доступность; совместная запись не выводится из отдельных услуг |
| Новая сессия: прямой R01 возвращает ACCESS_DENIED, после GET owner journal R01 успешен | Проверка проекта выполняет bootstrap до классификации прав; ранняя ошибка не объявляется отзывом доступа или истечением сессии |
| Сбой страницы / HTTP 200 с login/paywall/новой формой | PARTIAL/ошибка; прежние данные и покрытие не удаляются/не продвигаются |
| Пустой R05 по клиенту при существующих визитах | Не объявляется пустой полной историей; учитываются ограничения R05/R16 |
| Цена 5 000, скидка 1 500, оплата 3 500, долг 0 | Цена услуги, скидка продажи, итог и платёж не смешиваются |
| Нет timezone / неоднозначное локальное время | Локальное время сохраняется; UTC не угадывается, команда недоступна |
| Визит исчез из диапазона | Не объявляется отменённым/удалённым |
| Одновременный link/create Client | Одна подтверждённая связь, без лишнего Client от проигравшей транзакции |
| Повтор команды / авария после начала запроса | Возврат той же операции / UNKNOWN без слепого повтора |
| Доступ только к одному Workspace | Клиентская история и SSE не раскрывают другой проект |
| Историческое сообщение владельца DIKIDI | В этапе 13 employeeid остаётся NULL; SUPER_ADMIN не получает отправку |
| Пустая БД / существующая dbo | crm создаётся отдельно; default_schema и действующие таблицы не переносятся |
| Переключение A → B с одинаковой credentialversion / disable-enable | Разные connectionId/контекст отклоняют старый ответ; данные и покрытие не применяются |
| Календарь и история одного визита возвращаются в обратном порядке | Один APPOINTMENTS writer и fence не позволяют старому/менее авторитетному ответу затереть свежие поля |
| Старый R02 с payment приходит после финансового обновления | Старые суммы не получают новое поколение FINANCE и не меняют snapshot/paymentstate |
| Потерян первый ответ 202, затем reload | Сохранённый ключ находит прежнюю операцию; новый внешний запрос не создаётся |
| Полный период не содержит прежний визит | NOT_OBSERVED и requiresVerification; CURRENT диапазона не скрывает неопределённость строки |
| Частичная страница не содержит прежний визит | Отрицательное наблюдение не выводится |
| Удаление CRM-only Client / Employee-автора / Workspace с проектом | Транзакционное снятие допустимых links / явный 409 без удаления CRM-истории |
| Zone=NULL → задана зона / исправлена неверная зона | UTC скрыты до условного пересчёта по новой ревизии; local и lastObservedAt не меняются |
| Источник вернул APPOINTMENT, RESERVATION, BLOCK, UNKNOWN | История/команды фильтруются по виду; занятость остаётся видимой без выдуманного Client |
| Повторный DIKIDI-проект в Workspace / модель другого провайдера | Частичный индекс блокирует DIKIDI-дубль, не вводя общее правило для всех CRM |

12.3 реализует модули, подключение, секреты, schema/index bootstrap, проект и начальные сопоставления мастеров вместе с защитой DELETE Workspace/Employee и её регрессией. 12.4 — остальные проекции, синхронизацию и Client links вместе с защитой удаления Client; в 12.4.1 включено согласованное UI-дополнение каталога и ролевых экранов. 12.5 — оставшийся UI просмотра расписания, визитов и клиентского контекста. 12.6 — журнал, проверку удаления автора операции и доказанные команды. 12.7 — общую приёмку. Перед каждым подэтапом согласуется отдельный план. Коммиты, push и новые миграции без указания пользователя не выполняются.

## Прикладная приёмка UI 03.11.2026

Перенос согласованного макета завершён: 1035 тестов server, 21 тест адаптера, 420 UI-тестов и 12 browser-сценариев прошли. Проверены роли, поиск, версии, отмена связи, пагинация, восстановление доступа и фокус. Первый экран связей содержит три полные строки на desktop/tablet и одну на mobile (1366×768, 1024×768, 390×844). Browser использует API-фикстуры; новые живые вызовы DIKIDI не выполнялись. Логи: server/target/crm-ui-all-be.log, crm-ui-all-final.log, crm-ui-e2e-approved.log. Следующий подэтап 12.4.2 не начат.
Дополнительные реализованные маршруты:
- GET /api/crm/projects — локальные привязки Organization, только SUPER_ADMIN; не обнаружение внешних проектов.
- GET /api/crm/masters/{id} — карточка сохранённого мастера с проверкой доступа.
- GET /api/crm/projects/{id}/employee-candidates — кандидаты для руководителя текущего Workspace; каждый Employee проходит AccessPolicy, DTO содержит id/displayName/username/enabled.
- Создание/изменение аккаунта и проверка доступа публикуют organization crm.invalidated; каталог сохраняет workspace invalidation.
- Фоновое перечитывание той же ревизии сохраняет число страниц; 403/404 при догрузке очищает прежние строки, успешный перезапуск после 409 очищает ошибку.
Уточнение 03.11.2026: логин подключения уникален в пределах Organization и провайдера, включая отключённые аккаунты. DIKIDI-номер нормализуется (пробелы по краям и ведущий + удаляются). Создание или смена логина на занятый возвращает HTTP 409 CRM_CONNECTION_LOGIN_CONFLICT без изменения прежних учётных данных. Разные организации изолированы и могут подключить одинаковый логин. Ограничение crm.uqconnectionlogin описано в JPA и устанавливается Hibernate update без миграций; конкурентные изменения также сериализованы блокировкой Organization. Если в прежней базе уже есть дубликаты, они не удаляются и не объединяются автоматически: API не позволяет добавить новые, а установка ограничения БД требует предварительно устранить существующие дубли с сохранением нужных привязок.