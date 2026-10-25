# Практика 3 (модуль 3): Пирамида тестирования - тест-кейсы и анализ

Архитектурные диаграммы (часть а) находятся в директории [`architecture/`](architecture/)

## Часть (б) - Новые тест-кейсы 

Принцип выбора уровня: **самый низкий уровень, который адекватно проверяет сценарий**
Новые кейсы закрывают пробелы практики 2: маршрутизацию в Switch, сетевые контракты (Gateway, Switch, Bin Lookup), транзакционный Outbox, механику очередей (DLX/DLQ) и сквозные E2E-сценарии 

Всего 15 кейсов: **unit** - 4, **api** - 4, **integration** - 4, **e2e** - 3

| ID | Связанное требование | Уровень пирамиды | Вид | Предусловие | Шаги | Ожидаемый результат | Источник |
|---|---|---|---|---|---|---|---|
| TC-NEW-01 | ТЗ Switch, п.2: маршрутизация по таблице BIN (`POST /api/internal/route`) | unit | позитивный | `RoutingService` инициализирован с таблицей `switch.bin-routing` из `application.yml` | Вызвать `routingService.getIssuerIdByPan(pan)` для PAN с BIN `400000`, `400001`, `400002`, `400003`, `400004` | Возвращаются соответствующие идентификаторы эмитентов `ISS001`..`ISS005` | класс эквивалентности (известные BIN из таблицы) |
| TC-NEW-02 | ТЗ Switch, п.3-4: инициирование отката (reversal) при неполучении publisher-confirm от брокера | unit | негативный | `RouteService` с моками: `AuthorizationClient.authorize` возвращает `APPROVED` с `rrn`, `MerchantAcquirerClient` возвращает комиссию, `LoggerClient.log` возвращает `false` | Вызвать `routeService.route(request)` | Вызван `authorizationClient.rollback(routedRequest, rrn)` один раз; метод возвращает `DECLINED` с `responseCode = "96"` | сценарий (ветка `!logged && APPROVED` в `RouteService`, А4.3) |
| TC-NEW-03 | ТЗ Card-Management: запрет повторного отката уже отмененного резерва | unit | негативный | Объект `Reservation` находится в статусе `ROLLED_BACK` | Вызвать `reservation.startRollback(amount)` | Выброшено `RollbackAlreadySatisfiedException`; повторный `ReservationRollback` не создается | состояние (`ReservationStatus = ROLLED_BACK`, диаграмма А6.2) |
| TC-NEW-04 | ТЗ Card-Management, п.2: проверка соотношения дневного и месячного лимитов при PATCH | unit | негативный | Объект `Card` с `dailyLimit = 100000`, `monthlyLimit = 300000` | 1) Вызвать `card.withData` с `monthlyLimit = 100000`;<br>2) Вызвать `card.withData` с `monthlyLimit = 99999` | 1) Карта обновлена;<br>2) Выброшено `IllegalArgumentException` (дневной лимит больше месячного) | граничное значение (`monthlyLimit = dailyLimit` ON / `dailyLimit - 1` OFF) |
| TC-NEW-05 | ТЗ Gateway, п.4: ограничение частоты запросов (Rate Limit 100 req/s) | api | негативный | Gateway запущен, Switch заменен заглушкой | С одного IP отправить подряд 101 валидный `POST /api/transactions` за 1 секунду | Первые 100 запросов проходят; 101-й получает HTTP 429 (`error = "RATE_LIMIT_EXCEEDED"`, `retryAfterMs = 1000`) | граничное значение (100 запросов ON / 101-й OFF) |
| TC-NEW-06 | ТЗ Gateway, п.5: обработка недоступности downstream-сервиса; ТЗ 03, п.4: неизвестный BIN | api | негативный | 1) Gateway запущен, Switch остановлен;<br>2) Switch запущен, Authorization заменен заглушкой | 1) Отправить валидный `POST /api/transactions` в Gateway;<br>2) Отправить `POST /api/internal/route` в Switch с BIN `499999` | 1) HTTP 503 (`SERVICE_UNAVAILABLE`, `serviceName = "switch"`);<br>2) HTTP 200 (`DECLINED`, код `14`, `CARD_NOT_FOUND`), заглушка Authorization не вызывалась | контракт (`DownstreamErrorFilter` и `RouteService`) |
| TC-NEW-07 | ТЗ Authorization, п.2: возврат HTTP 403 при отказе по статусу карты | api | негативный | Authorization запущен с БД; CMS заменен заглушкой, возвращающей на `GET /api/cards/{pan}` карту со статусом `BLOCKED` | Отправить `POST /api/internal/authorize` с этим PAN | HTTP 403 Forbidden; тело: `status = "DECLINED"`, `responseCode = "05"`, `declineReason = "CARD_BLOCKED"`; запрос `POST /reserve` на заглушку не поступал | контракт (`AuthControllerImpl`, диаграмма А4.2) |
| TC-NEW-08 | ТЗ Card-Management, п.5: контракт резервирования при нехватке баланса и неактивном статусе | api | негативный | Card-Management запущен с БД; карта А (`ACTIVE`, баланс `500.00`), карта Б (`INACTIVE`, баланс `5000.00`) | 1) `POST /api/cards/{panA}/reserve` на сумму `600.00`;<br>2) `POST /api/cards/{panB}/reserve` на сумму `100.00` | 1) HTTP 402 (`InsufficientFundsException`), баланс `500.00`;<br>2) HTTP 409 (`IllegalStateException` в `GlobalExceptionHandler`) | контракт (`CardController`, диаграммы А3.2, А6.1) |
| TC-NEW-09 | ТЗ Card-Management, п.5: сохранение резерва, списания и события Outbox в одной транзакции БД | integration | позитивный | Card-Management с PostgreSQL; планировщик `OutboxProcessor` отключен; карта `ACTIVE` с балансом `100000` | Вызвать `POST /api/cards/{pan}/reserve` с `amount = 40000`, `rrn = "000000000001"`; прочитать таблицы БД | HTTP 200; в `reservations` строка `RESERVED`; в `cards` `available_balance = 60000`; в `outbox_event` строка со статусом `PENDING` | состояние БД (три таблицы в одной транзакции, А3.2) |
| TC-NEW-10 | ТЗ Card-Management: публикация из `outbox_event` в RabbitMQ и доставка в Notification Service | integration | позитивный | Card-Management, Notification Service, RabbitMQ и PostgreSQL запущены; карта `ACTIVE` | Выполнить `PATCH /api/cards/{pan}` со `status = "BLOCKED"`; опросить БД через 2 секунды | Запись в `outbox_event` перешла из `PENDING` в `PROCESSED`; в таблице `card_notifications` появилась запись с типом `CardServicePatchEvent` | состояние БД (`outbox_event` -> `card_notifications`, А5) |
| TC-NEW-11 | 3 попытки доставки, перевод в DLQ и очистка по TTL 60s | integration | негативный | Transaction Logger, RabbitMQ и PostgreSQL запущены; очереди `transaction-log` и `transaction-log-dlq` пусты | Опубликовать в `smp.transactions` (ключ `transaction.log`) невалидный JSON, вызывающий ошибку в `TransactionLogListener` | После 3 попыток доставки сообщение переходит через `smp.transactions.dlx` в `transaction-log-dlq`; через 60 с очередь `transaction-log-dlq` пуста | сценарий (отказ consumer, топология А5) |
| TC-NEW-12 | ТЗ Card-Management: перевод события Outbox в статус `FAILED` после 3 ошибок публикации | integration | негативный | Card-Management с PostgreSQL запущены; RabbitMQ остановлен; карта `ACTIVE` | Выполнить `PATCH /api/cards/{pan}` (`status = "BLOCKED"`); подождать 4 секунды (3 цикла `OutboxProcessor` по 1000 ms) | `PATCH` возвращает 200, статус карты в БД обновлен; запись в `outbox_event` после `retryCount = 3` переходит в статус `FAILED` | состояние БД (сбой брокера в Outbox, А3.2, А5) |
| TC-NEW-13 | Сквозной сценарий Happy Path и доставка в Transaction Logger и WebSocket (eventual consistency) | e2e | позитивный | Весь стенд запущен; создана карта `ACTIVE` (BIN `400000`, баланс `1000.00`, лимиты `5000.00`); подключен клиент к `/ws/transactions` | 1) Отправить `POST /api/transactions` через Gateway на `400.00`;<br>2) Опрашивать `GET /api/transactions/search?pan=...` до 5 с и проверить WebSocket;<br>3) Запросить `GET /api/cards/{pan}` | 1) Сразу приходит HTTP 200 (`APPROVED`, код `00`);<br>2) В течение 5 с запись с тем же `rrn` появляется в БД Logger и приходит по WebSocket;<br>3) Баланс карты равен `600.00` | сценарий (Happy Path, диаграмма А4.1) |
| TC-NEW-14 | Сквозной сценарий Reversal: откат резервирования при таймауте/недоступности RabbitMQ | e2e | негативный | Весь стенд запущен; карта `ACTIVE` с балансом `1000.00`; RabbitMQ остановлен | 1) Отправить `POST /api/transactions` на `400.00`;<br>2) Проверить `GET /api/cards/{pan}`;<br>3) Запустить RabbitMQ и проверить `GET /api/transactions/search?pan=...` | 1) Через ~2 с возвращается HTTP 200 (`DECLINED`, `responseCode = "96"`);<br>2) Резерв в статусе `ROLLED_BACK`, баланс карты снова `1000.00`;<br>3) В журнале Logger записи о транзакции нет | сценарий (Reversal, диаграммы А4.3, А6.2) |
| TC-NEW-15 | Сквозной сценарий переходов состояния карты (`ACTIVE -> BLOCKED -> ACTIVE`) | e2e | позитивный | Весь стенд запущен; создана карта `ACTIVE` с балансом `1000.00` | 1) `PATCH /api/cards/{pan}` (`status = "BLOCKED"`);<br>2) `POST /api/transactions` на `200.00`;<br>3) `PATCH /api/cards/{pan}` (`status = "ACTIVE"`);<br>4) `POST /api/transactions` на `200.00` | На шаге 2 ответ `DECLINED` (`05`, `CARD_BLOCKED`), баланс `1000.00`; на шаге 4 ответ `APPROVED` (`00`), баланс `800.00`; обе транзакции записаны в Logger | сценарий (переходы состояний, диаграмма А6.1) |

---

## Часть (в) - Распределение тест-кейсов практики 2 по уровням пирамиды

Все 24 тест-кейса (`TC-01`..`TC-24`) из второго модуля проанализированы через компонентные схемы А3.1 и А3.2. Для каждого кейса определен минимальный уровень пирамиды, на котором физически исполняется проверяемая логика.

| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| TC-01 | Успешная транзакция по активной карте | Одобрение в Authorization + реальное списание баланса в CMS + увеличение расхода лимитов в БД | integration | Ожидаемый результат требует проверки записи в таблицу `limit_usage` (PostgreSQL) и создания резерва в соседнем сервисе CMS; для сквозного E2E здесь не задействованы Gateway, Switch и Logger |
| TC-02 | Отклонение транзакции по неактивной карте | Ветка проверки статуса `INACTIVE` в `AuthServiceImpl.authorize()` -> `CARD_INACTIVE` | unit | Чистая бизнес-логика выбора исхода отказа (`DeclineOutcome`) в Java-коде при замоканном `CardManagementClient`; обращения к БД нет |
| TC-03 | Отклонение транзакции по заблокированной карте | Ветка проверки статуса `BLOCKED` в `AuthServiceImpl.authorize()` -> `CARD_BLOCKED` (код `05`) | unit | Изолированная проверка ветвления `switch` по статусу карты в сервисе без сетевых вызовов и БД |
| TC-04 | Отклонение транзакции по карте со статусом EXPIRED | Ветка проверки статуса `EXPIRED` в `AuthServiceImpl.authorize()` -> код `54` | unit | Проверка возврата кода `54` по полю `status` модели карты при моке HTTP-клиента |
| TC-05 | Карта не найдена в системе | Перехват `CardNotFoundException` в `AuthServiceImpl` -> возврат `DECLINED`, код `14` | unit | Достаточно замокать выброс `CardNotFoundException` из `CardManagementClient` и проверить формирование ответа `CARD_NOT_FOUND` |
| TC-06 | Истек срок действия карты | Сравнение `expiryDate.atEndOfMonth()` с датой транзакции в `AuthServiceImpl` -> код `54` | unit | Логика сравнения дат (`YearMonth`) целиком находится в Java-методе, внешние сервисы и БД не требуются |
| TC-07 | Транзакция в последний месяц действия | Одновременная проверка границы срока (текущий месяц) и границ баланса и лимитов (списание в ноль) | integration | В кейсе сумма равна дневному и месячному лимиту (`1000.00`), а условие `<=` для лимитов выполняется внутри нативного SQL-запроса `upsertLimitUsage` в PostgreSQL |
| TC-08 | Сумма операции в точности равна дневному лимиту | Граница ON дневного лимита (`1500.00`) и обновление расхода в `limit_usage` | integration | Проверка и списание дневного лимита зашиты в SQL-запрос `INSERT INTO limit_usage ... ON CONFLICT DO UPDATE WHERE ...`; с моком репозитория условие SQL не проверится |
| TC-09 | Превышение дневного лимита на 1 копейку | Граница OFF дневного лимита (`1500.01` при лимите `1500.00`) -> отказ с кодом `61` | integration | Решение об отказе принимает SQL-запрос в PostgreSQL (возвращает 0 обновленных строк при нарушении `WHERE`), нужна реальная БД |
| TC-10 | Превышение месячного лимита при достаточном дневном | Граница OFF остатка месячного лимита с учетом предыдущих трат -> код `61` | integration | Сумма трат за месяц считается SQL-подзапросом `SUM(daily_amount)` в таблице `limit_usage` в PostgreSQL |
| TC-11 | Списание доступного баланса ровно в ноль | Граница ON доступного баланса (`amount == availableBalance`) -> `APPROVED` | unit | Сравнение `request.amount().compareTo(cardResponse.availableBalance()) > 0` выполняется в Java-коде `AuthServiceImpl` |
| TC-12 | Недостаточно средств на балансе | Граница OFF баланса (превышение на 1 копейку) -> отказ с кодом `51` | unit | Проверка баланса в `AuthServiceImpl` идет до вызова репозитория лимитов и резервации, тестируется на моках |
| TC-13 | Недоступность сервиса Card Management | Обработка сетевого отказа / таймаута при вызове CMS из Authorization | api | Проверяется поведение HTTP-клиента `CardManagementClientImpl` и маппинг ошибки при помощи HTTP-заглушки (WireMock) |
| TC-14 | Успешная авторизация при недоступности Bin Lookup | Штатная деградация (fallback) при таймауте/ошибке сервиса `bin-lookup` | api | Проверка HTTP-взаимодействия `BinLookupClient` с заглушкой, имитирующей таймаут, и успешного ответа `/api/internal/authorize` |
| TC-15 | Проверка идемпотентности по RRN | Защита от повторного резервирования и двойного списания по одному и тому же `RRN` | integration | Контроль уникальности `RRN` и идемпотентности резерва опирается на таблицу `reservations` и sequence `rrn_seq` в PostgreSQL |
| TC-16 | Создание карты с генерацией валидного PAN | Контракт `POST /api/cards`: статус 201, поля ответа (`ACTIVE`, `expiryDate`, 16-значный PAN) | api | Проверяется HTTP-контракт контроллера `CardController` и корректность JSON-ответа при создании карты |
| TC-17 | Мягкое удаление карты | Перевод в статус `DELETED` при `DELETE /api/cards/{pan}` и скрытие карты из `GET`-запросов | integration | Скрытие удаленной карты реализуется на уровне БД через аннотацию Hibernate `@SQLRestriction("status <> 'DELETED'")` на `CardEntity` |
| TC-18 | Валидация BIN при создании карты | Отсечение нечислового BIN (`"4000A0"`) | unit | Правило `^\d{6}$` изолировано в классе `BinValidator` (аннотация `@Bin`), проверяется прямым вызовом валидатора без поднятия HTTP |
| TC-19 | Валидация initialBalance при создании карты | Реакция эндпоинта `POST /api/cards` на отрицательный `initialBalance = -1000` | api | В DTO `CreateCardRequest` отсутствует аннотация валидации над `initialBalance`, поэтому поведение проверяется через HTTP-вызов контроллера |
| TC-20 | Валидация cardholderName при создании карты | Отсечение пустой строки `cardholderName = ""` | unit | Ограничение задано стандартной аннотацией `@NotBlank` на `CreateCardRequest`, тестируется через Jakarta `Validator` в памяти |
| TC-21 | Валидация алгоритма Луна при запросе карты | Реакция эндпоинта `GET /api/cards/{pan}` на PAN с неверной контрольной цифрой Луна | api | Проверяется входной HTTP-контракт контроллера при передаче path-параметра `pan` |
| TC-22 | Генерация пула тестовых карт | Генерация 100 карт с валидным алгоритмом Луна и распределением статусов 95% / 3% / 2% | unit | Арифметика распределения статусов в `CardGeneratorService.generate()` и генерация номеров в `LuhnValidator` работают в памяти при замоканном сохранении |
| TC-23 | Валидация отрицательного лимита в пагинации | Контракт `GET /api/cards?limit=-1` -> возврат HTTP 400 Bad Request | api | Проверяется валидация query-параметра на уровне веб-слоя (`CardController`) и формирование ответа ошибки |
| TC-24 | Валидация длины PAN при запросе карты | Отсечение PAN длиной меньше 16 цифр | unit | Регулярное выражение `^\d{16}$` находится в классе `PanValidator` (аннотация `@Pan`), проверяется изолированно без запуска сервера |

---

## Часть (г) - Анализ пропорций пирамиды

### 1. Сводная таблица пропорций

Сводка старых кейсов из практики 2 (часть в) и новых кейсов (часть б):

| Уровень | Старые | Новые | Итого | Доля |
|---|---:|---:|---:|---:|
| unit | 11 | 4 | 15 | 38.5% |
| api / integration | 13 | 8 | 21 | 53.8% |
| e2e | 0 | 3 | 3 | 7.7% |
| **Итого** | **24** | **15** | **39** | **100%** |

Детализация среднего слоя (`api / integration`):

| Под-уровень | Старые | Новые | Итого | Доля от всех (39) |
|---|---:|---:|---:|---:|
| api | 6 | 4 | 10 | 25.6% |
| integration | 7 | 4 | 11 | 28.2% |
| **api + integration** | **13** | **8** | **21** | **53.8%** |

### 2. Почему получилась пропорция 38.5% / 53.8% / 7.7% 

Вместо пирамиды 70/20/10 получилась фигура с широким средним слоем, где `api + integration` составляют 53.8%. Такой результат мог получиться по 3 причинам:

1. **Проверка лимитов вынесена в SQL и механизмы БД**
   В сервисе Authorization проверка дневного и месячного лимитов (`TC-07`, `TC-08`, `TC-09`, `TC-10`) реализована не в Java-коде, а внутри нативного SQL-запроса `upsertLimitUsage` в `LimitUsageRepository`. Если написать на эти кейсы unit-тесты с моками репозитория, тест будет проверять сам мок, а не условие `:amount <= :dailyLimit`. Точно так же фильтрация удаленных карт (`TC-17`) держится на аннотации Hibernate `@SQLRestriction("status <> 'DELETED'")`, а атомарность резерва и записи события в `outbox_event` (`TC-NEW-09`) требует проверки реальной транзакции PostgreSQL
2. **Высокая плотность межсервисных контрактов**
   На диаграмме контейнеров (А2) система разбита на независимые сервисы, общающиеся по HTTP и AMQP. На стыках сервисов возникают нетривиальные контрактные правила: например, `AuthControllerImpl` возвращает на заблокированную карту статус **HTTP 403**, а клиент в Switch (`AuthorizationClient`) специально не бросает исключение на 403 и читает бизнес-отказ из тела ответа (`TC-NEW-07`). Аналогично требуют проверки таймауты и фолбэки к `bin-lookup` (`TC-14`) и `card-management` (`TC-13`), а также механика ретраев и DLQ в RabbitMQ (`TC-NEW-10`..`12`). Unit-тесты с моками эти межсервисные швы не видят
3. **Узкая верхушка сквозных сценариев**
   В практике 2 все тесты обращались напрямую к эндпоинтам `Authorization` и `Card-Management`, поэтому старых E2E-кейсов было 0. В части (б) добавлено 3 сквозных сценария (`TC-NEW-13`..`15`), которые нельзя проверить на более низком уровне

### 3. Анализ рисков 

- **Есть ли риск антипаттерна Ice-cream cone?** Нет, доля тяжелых E2E-тестов составляет всего 7.7% (3 кейса из 39). Все комбинаторные и граничные проверки опущены на нижние уровни
- **Есть ли дефицит контрактных проверок?** Нет, 10 api-тестов  покрывают входные фильтры Gateway, маршрутизацию Switch, вызовы Authorization -> CMS / Bin Lookup и REST-эндпоинты управления картами
- **Слабое место и управление рисками** доля `unit` (38.5%) ниже классических 70%, а доля `integration` (28.2%) достаточно высока. Чтобы интеграционные тесты с PostgreSQL и RabbitMQ не замедляли сборку, их нужно реализовывать как узкие тесты слоя персистентности с переиспользуемым контейнером БД