# API-тесты СМП - отчёт

**Модуль:** 5 - API-тестирование REST-сервисов.
**Объект:** СМП (11 сервисов, PostgreSQL, RabbitMQ, единая точка входа Gateway).
**Стек тестов:** Java 21, Maven, JUnit 5, REST-assured 5.4.0, Hamcrest, Awaitility 4.2.2, JSON Schema Validator 5.4.0, Jackson.
**Модуль тестов:** [`services/api-tests`](../../services/api-tests) (профиль Maven `api-tests`, не входит в профиль `all`).
**Объём:** 26 тестовых классов, 129 тестовых методов, 309 проверок (выполнений с учётом строк параметризации), полный прогон около минуты.
**Источники контракта:** [`docs/api-spec.md`](../api-spec.md), контроллеры и DTO сервисов, маршруты Gateway [`services/gateway/src/main/resources/application.yml`](../../services/gateway/src/main/resources/application.yml); дополнительно `docs/api/openapi.yaml` и `tz/*.md`.
**Правило:** ожидаемое значение в тесте берётся из кода (код - источник истины), расхождение с документацией фиксируется в разделе 4 как дефект или известное ограничение. Тесты на дефектное поведение помечены `@Tag("known-defect")` и ссылкой `D-xx` в имени.

### Структура модуля

| Пакет | Содержимое |
|---|---|
| `support` | `ApiConfig` (адреса, переопределяются `-Dgateway.url=...`), `Specs` (`RequestSpecification` на каждый сервис), `TestData` (уникальные STAN/PAN/UUID/merchantId/IP), `CardFactory` (карта с известным состоянием через API), `TransactionSteps`, `Waits` (границы ожиданий), `Patterns` (форматы RRN, authCode, UUID) |
| `support.builders` | билдеры тел: `AuthorizationRequestBuilder`, `CardRequestBuilder`, `TransactionLogBuilder` |
| `gateway` | маршрутизация и принадлежность ошибок, rate limit, circuit breaker (503), кеш `X-Cache`, OpenAPI-маршруты и actuator |
| `transactions` | валидация, одобрение, бизнес-отказы, поиск, сквозная цепочка, асинхронные эффекты |
| `cards` | создание, чтение/изменение/удаление, список, генерация, bulk-update |
| `internal` | внутренние эндпоинты цепочки: route, authorize, rollback, log, reserve/rollback CMS, bin-lookup |
| `dashboard`, `simulator`, `health` | дашборд, симуляторы, health |
| `src/test/resources/schemas` | JSON-схемы (draft-04) ответов |

---

## 1. Покрытие

Источники: **S** - `docs/api-spec.md`, **C** - контроллер в коде, **G** - маршрут Gateway (или собственный эндпоинт Gateway), **I** - внутренний, вызывается напрямую.
Статус: **покрыт** - все достижимые через API коды; **частично** - код ответа есть в коде, но достижим только при отказе зависимости (вне области, см. раздел 5).

### Через Gateway

| Эндпоинт | Источники | Проверяемые статус-коды | Кол-во проверок | Статус |
|---|---|---|---:|---|
| `GET /health` (Gateway) | S, C, G | 200 (503 `degraded` проверен вручную остановкой CMS, D-06) | 1 | частично |
| `POST /api/transactions` | S, C, G -> Switch | 200 (одобрение и все отказы), 400 (валидация Gateway), 415 (ответ Switch), 429 (rate limit) | 66 | частично (503) |
| `GET /api/transactions/search` | S, C, G -> Logger | 200, 400 | 43 | покрыт |
| `GET /api/transactions/export` | C, G | 200 (`text/csv`, `Content-Disposition`), 400 (D-35) | 3 | покрыт |
| `GET /api/dashboard/stats` | S, C, G | 200 | 2 | покрыт |
| `GET /api/dashboard/recent` | S, C, G | 200, 400 | 6 | покрыт |
| `GET /api/dashboard/charts` | C, G | 200, 400 | 5 | покрыт |
| `POST /api/cards` | S, C, G | 201, 400, 404, 500 (напрямую в CMS) | 28 | покрыт |
| `POST /api/cards/generate` | S, C, G | 201, 400, 404 | 7 | покрыт |
| `GET /api/cards/{pan}` | S, C, G | 200, 400, 404, 503 (circuit breaker Gateway после трёх 500 от CMS, `Retry-After: 10`, восстановление); заголовок `X-Cache` | 8 | покрыт |
| `GET /api/cards` | C, G | 200, 400, 413, 500 (напрямую) | 29 | покрыт |
| `PATCH /api/cards/{pan}` | C, G | 200, 400, 404, 500 (напрямую) | 8 | покрыт |
| `DELETE /api/cards/{pan}` | C, G | 204, 404 | 1 | покрыт |
| `POST /api/cards/bulk-update` | C, G | 200, 400 | 6 | покрыт |
| `POST /api/simulator/terminal/run` | S, C, G | 200, 400 | 9 | покрыт |
| `POST /api/simulator/terminal/stop` | C, G | 200 | 1 | покрыт |
| `POST /api/simulator/terminal/start-continuous` | C, G | 200, 400, 422 (повторный запуск во время работы) | 3 | покрыт |
| `POST /api/simulator/merchant/run` | S, C, G | 200, 400, 404 | 9 | покрыт |
| `GET /switch-docs`, `/logger-docs`, `/cards-docs`, `/merchant-docs`, `/terminal-docs` | G | 200 | 5 | покрыт |
| `GET /actuator/health`, `/actuator/prometheus` | G (Gateway) | 200 | 2 | покрыт |
| Таблица маршрутов (неизвестный путь, внутренние пути, неверный метод, незамаршрутизированные эндпоинты) | G | 404 (Gateway), 404/405 (Logger) | 11 | покрыт |

### Внутренние эндпоинты цепочки авторизации и логирования

| Эндпоинт | Источники | Проверяемые статус-коды | Кол-во проверок | Статус |
|---|---|---|---:|---|
| `POST /api/internal/route` (Switch) | S, C, I | 200, 400 | 3 | покрыт |
| `POST /api/internal/authorize` (Authorization) | S, C, I | 200, 400, 403, 404, 422 (503 не проверяется) | 10 | частично |
| `POST /api/internal/rollback` (Authorization) | C, I | 200, 400, 404, 409 (503 не проверяется) | 5 | частично |
| `POST /api/internal/log` (Logger) | S, C, I | 201, 200, 400, 409 (500, 503 не проверяются) | 16 | частично |
| `POST /api/cards/{pan}/reserve` (CMS) | C, I | 200, 400, 402, 404, 409 | 6 | покрыт |
| `POST /api/cards/{pan}/rollback` (CMS) | C, I | 200, 404, 409 | 3 | покрыт |
| `GET /api/bin/{bin}` (Bin Lookup) | C, I | 200, 404, 500 | 7 | покрыт |
| `POST /api/simulator/merchant/fee` (Merchant, вызывает Switch) | C, I | 200 (комиссия транзакции симулятора равна `acquiringFee` в журнале), 400, 404 | 6 | покрыт |

`/api/bins`, `GET /api/simulator/merchants` и `/api/notifications` не входят в область требования 1: они не маршрутизируются Gateway и не участвуют в цепочке авторизации и логирования, поэтому проверено только то, что Gateway их не отдаёт.

### Расхождения между тремя источниками перечня эндпоинтов

| Эндпоинт | api-spec | Код | Маршрут Gateway |
|---|---|---|---|
| `WS /ws/transactions` | есть (базовый URL Gateway) | есть в Logger | **нет**: через Gateway 404, у Logger напрямую 101 (D-34) |
| `GET /api/cards`, `PATCH`/`DELETE /api/cards/{pan}`, `POST /api/cards/bulk-update`, reserve/rollback | нет | есть | попадают под `/api/cards/**` |
| `GET /api/transactions/export`, `GET /api/dashboard/charts` | нет | есть | есть |
| `POST /api/internal/rollback` | нет | есть | нет (внутренний) |
| `/api/simulator/terminal/start`, `/status` | нет (есть в openapi.yaml) | вместо них `start-continuous`, `stop` | `/api/simulator/terminal/**` |
| `GET /api/simulator/merchants` | нет (есть в openapi.yaml) | есть | **нет**: предикат `/api/simulator/merchant/**` не совпадает с `merchants` |
| `/api/bins`, `/api/notifications`, `/api/bin/{bin}` | нет | есть | нет |
| `/*-docs`, `/actuator/*` | нет | Gateway | есть |

---

## 2. Бизнес-отказы

Алгоритм: `AuthServiceImpl.authorize` + `DeclineOutcome`; ответы Switch: `AuthorizationResponse.unknownBin/authUnavailable/systemError`.
Принадлежность отказа видна по телу: отказ Authorization повторяет `mti` запроса (`"0100"`) и измеряет `processingTimeMs`; отказ, сформированный Switch, имеет `mti = "0110"` и `processingTimeMs = 0`. По этим полям различаются два `CARD_NOT_FOUND / 14`.

| Исход | Код | Причина | Путь достижения | Способ подготовки данных | Статус |
|---|---|---|---|---|---|
| Одобрено | 00 | - | данные | ACTIVE карта, баланс и лимиты выше суммы | покрыт |
| Карта не найдена (Authorization) | 14 | `CARD_NOT_FOUND` | данные: BIN маршрутизируется, PAN не существует | уникальный PAN `4000000xxxxxxxxx` | покрыт |
| Карта не найдена (Switch) | 14 | `CARD_NOT_FOUND` | данные: BIN не в таблице маршрутизации | PAN `499999xxxxxxxxxx` | покрыт |
| Карта не найдена (удалена) | 14 | `CARD_NOT_FOUND` | состояние: `DELETE /api/cards/{pan}` | создание + удаление через API | покрыт |
| Карта заблокирована | 05 | `CARD_BLOCKED` | состояние | `PATCH status=BLOCKED` | покрыт |
| Карта неактивна | 05 | `CARD_INACTIVE` | состояние | `PATCH status=INACTIVE` | покрыт |
| Срок истёк (статус) | 54 | `CARD_EXPIRED` | состояние | `PATCH status=EXPIRED` | покрыт |
| Срок истёк (дата) | 54 | `CARD_EXPIRED` | данные: `transmissionDateTime` позже конца месяца `expiryDate` | дата из ответа создания карты + 1 месяц; граница ON: последняя секунда месяца одобряется | покрыт |
| Недостаточно средств (запрос) | 51 | `INSUFFICIENT_FUNDS` | данные: `amount = balance + 1` | баланс 50 000; граница ON `amount = balance` одобряется, баланс 0 | покрыт |
| Недостаточно средств (состояние) | 51 | `INSUFFICIENT_FUNDS` | состояние: баланс уменьшен после создания | `PATCH availableBalance=999`, сумма 1 000 | покрыт |
| Превышен лимит (запрос) | 61 | `EXCEEDS_AMOUNT_LIMIT` | данные: `amount = dailyLimit + 1` | `dailyLimit = 10 000` | покрыт |
| Превышен дневной лимит (накопление) | 61 | `EXCEEDS_AMOUNT_LIMIT` | состояние: расход дня исчерпан одобренной суммой = лимиту | две транзакции с одной датой | покрыт |
| Превышен месячный лимит (накопление) | 61 | `EXCEEDS_AMOUNT_LIMIT` | состояние: две даты одного месяца | `dailyLimit 10 000`, `monthlyLimit 15 000`, 10 000 + 5 001; граница ON 10 000 + 5 000 одобряется | покрыт |
| Authorization недоступен (по ответу Switch) | 05 | `Authorization service unavailable` | данные: `stan` из 5 цифр проходит Gateway, Authorization отклоняет его валидацией | без подготовки | покрыт (D-07) |
| Откат: уже откатано | 05 | `ALREADY_ROLLED_BACK` | состояние: повторный `rollback` | одобрение + откат | покрыт |
| Откат: транзакция не найдена | 14 | `TRANSACTION_NOT_FOUND` | данные: RRN без резерва | уникальный RRN | покрыт |
| CMS недоступен (авторизация и откат) | 96 | `SERVICE_UNAVAILABLE` | отказ зависимости: CMS не отвечает, 500 или 503 | остановка контейнера CMS | проверено вручную (D-03), автотест вне области (раздел 5) |
| Резерв не удался | 96 | `RESERVATION_FAILED` | карта изменилась между чтением и резервом (CMS отвечает 409) | только гонкой параллельных запросов | вне области |
| Откат не удался | 96 | `ROLLBACK_FAILED` | неожиданный статус CMS на rollback | CMS таких статусов не возвращает | вне области |
| БД лимитов недоступна | 96 | `DB_UNAVAILABLE` | отказ зависимости | требует остановки PostgreSQL | вне области |
| Неизвестная причина | 05 | `UNKNOWN_REASON` | статус карты `null` или вне перечня | недостижимо через API | вне области |
| Сбой публикации в журнал | 96 | `System error` | RabbitMQ недоступен, Switch откатывает резерв | требует остановки брокера | вне области (модуль 6) |

### Почему два пути различаются

Через данные: с картой всё в порядке, отказ вызывает сам запрос (слишком большая сумма или дата после срока действия). Через состояние: такой же запрос раньше проходил, а теперь нет, потому что изменилась карта: её заблокировали, уменьшили баланс или уже потратили дневной лимит.

Проверять нужно оба пути. Для `CARD_EXPIRED` это вообще разный код: в одном случае проверяется статус карты, в другом сравнивается срок действия с датой транзакции (после такого отказа карта остаётся `ACTIVE`, тест это тоже проверяет). Для нехватки средств и лимитов проверка одна, но если сломается обновление баланса или расхода, это заметит только тест через состояние.

Ещё тесты фиксируют порядок проверок: статус проверяется раньше средств (BLOCKED + сумма больше баланса - `CARD_BLOCKED`), средства раньше лимитов (сумма больше баланса и лимита -> 51, не 61, D-02).

---

## 3. Валидация

### `POST /api/transactions` (Gateway, `TransactionRequestValidator`)

| Поле | Правило | Классы | Границы | Кол-во проверок |
|---|---|---|---|---:|
| все 11 обязательных (`mti`, `stan`, `pan`, `processingCode`, `amount`, `currencyCode`, `transmissionDateTime`, `terminalId`, `merchantId`, `mcc`, `acquirerId`) | обязательность: `Field 'x' is required` | отсутствует; явный `null`; пустая строка; только пробелы | - | 15 |
| `mti` | равно `0100` | другой 4-значный тип (`0110`); другая длина (`100`) | - | 2 |
| `pan` | ровно 16 цифр | 15 цифр; 17 цифр; нецифровой символ | 15 / 16 / 17 | 3 (+ 16 цифр в валидных) |
| `amount` | `> 0` | 0; отрицательное | 0 (OFF), -0.01, 0.01 (ON), 1 | 2 + 2 |
| `currencyCode` | длина 3 | 2 символа; 4 символа; буквы допустимы | 2 / 3 / 4 | 2 + 1 |
| `mcc` | ровно 4 цифры | 3; 5; буква | `0000`, `9999` валидны | 3 + 2 |
| тело | читается как JSON-объект | битый JSON; пустое тело; массив; неверный тип `amount`; дата не ISO 8601; литерал `null` | - | 6 |
| необязательные `terminalType`, `issuerId` | могут отсутствовать | отсутствуют | - | 2 |
| `stan` | длина и формат Gateway не проверяет | 5 цифр (D-07); 6 букв | - | 2 |

Каждый отказ проверяется по телу: `error = VALIDATION_ERROR`, точный `message` (из него следует поле и правило), `serviceName = gateway`, `retryAfterMs = null`, JSON-схема `ErrorResponse`.

### Card Management

| Поле | Правило | Классы | Границы | Кол-во проверок |
|---|---|---|---|---:|
| `bin` (create, generate, list) | 6 цифр (`@Bin`) | буква; 5; 7; 2 цифры; отсутствует; неизвестный BIN (404) | 5 / 6 / 7 | 7 |
| `cardholderName` | `^[A-Z\s\-\.']+$`, не пусто | строчные; цифра; отсутствует | 1 символ; апостроф, точка, дефис валидны | 6 |
| `currencyCode` | 3 цифры | 2; 4; буквы; отсутствует | 2 / 3 / 4 | 4 |
| `dailyLimit`, `monthlyLimit` | `>= 0`; при PATCH `daily <= monthly` | -1; `daily > monthly` (PATCH 400, create 201 - D-13) | 0 валиден; `daily = monthly` валиден | 6 |
| `initialBalance`, `availableBalance` | ограничений нет | -1 принимается (D-13) | 0 | 3 |
| `pan` (path) | 16 цифр | 15; 17; буква; Луна не проверяется | 15 / 16 / 17 | 4 |
| `count` (generate) | 1..10000 | 0; 10001 | 1 (ON) | 3 |
| `bins` (generate) | непустой список BIN | пустой; неверный BIN | - | 2 |
| `limit`, `offset`, `issuerId`, `status` (list) | `limit` 1..10000, `offset >= 0`, `issuerId` `[A-Z0-9]{1,10}` | 0; -1; 10001 (413); строчные; 11 символов; неизвестный статус (500, D-11) | `limit` 1 и 10000 | 9 |
| `startDate`, `endDate` (list) | `startDate <= createdAt <= endDate` | окно своих карт; дата после всех карт; `startDate > endDate` | `endDate` = сохранённый `createdAt` (ON), на 1 мкс раньше (OFF) | 4 |
| `rrn`, `amount` (reserve) | 12 цифр; `> 0` | 11 цифр | - | 1 |
| `pans`/`bins`/`status` (bulk-update) | ровно один селектор, статус обязателен | нет селектора; оба; без статуса; PAN 3 цифры | - | 4 |

### Transaction Logger

| Поле | Правило | Классы | Границы | Кол-во проверок |
|---|---|---|---|---:|
| тело `/api/internal/log` (`id`, `stan`, `pan`, `amount`, `currencyCode`, `mcc`, `merchantId`, `createdAt`, `status`) | `@NotNull`, `\d{6}`, 16 цифр, `> 0`, `\d{3}`, `\d{4}`, `<= 15`, enum | по одному нарушению на поле | `amount = 0`, `merchantId` 16 символов | 9 |
| `limit`, `offset` (search) | 1..500, `>= 0` | 0; 501; -1 | 1, 500; `offset` = конец выборки в тесте пагинации | 5 |
| `status`, `dateFrom`, `dateTo`, `limit` (search) | тип параметра | неизвестный enum; enum в нижнем регистре; дата 13-го месяца; не ISO-дата; не число | - | 5 |
| `dateFrom <= dateTo` (search), `from <= to` (charts) | `@ValidDateRange` | обратный диапазон (400 с пустым сообщением, D-08) | - | 2 |
| `limit` (recent) | 1..500 | 0; -5; 501 | 1, 500 | 5 |
| `granularity` (charts) | `hour` или `day` | `week` | - | 3 |

### Симуляторы и Authorization

| Поле | Правило | Классы | Кол-во проверок |
|---|---|---|---:|
| terminal `count`, `tps`, `scenario` | `>= 1`, `>= 1`, enum из 5 значений | 0; 0; отсутствует (`tps` не в спецификации, D-25); неизвестный сценарий | 4 |
| merchant `count`, `scenario`, `tps`, `mccCodes` | `>= 1`, обязателен, `>= 1`, существующий MCC | 0; отсутствует; 0; неизвестный MCC (404) | 4 |
| terminal start-continuous `tps`, `transactionType` | `>= 1`, обязателен | 0; отсутствует | 2 |
| merchant fee | `pan`, `stan`, `terminalId`, `transmissionDateTime` обязательны | по одному отсутствующему полю на запрос | 4 |
| Authorization `issuerId`, `stan`, `amount`, `rrn` | `@NotBlank`, `@ExactSize(6)`, `@PositiveOrZero`, `@Rrn` | отсутствует; 5 цифр; 0 (D-16); 5 цифр | 4 |

---

## 4. Расхождения контракта и реализации

В последнем столбце: дефект (ошибка в коде), ограничение (так работает, принимаем как есть) или документация (код верный, неточность в документе), плюс насколько это важно для того, кто пользуется API.

| # | Декларировано | Наблюдается | Источник декларации | Классификация |
|---|---|---|---|---|
| D-01 | `AuthorizationResponse.mti = "0110"` | ответы Authorization (одобрение и его отказы) содержат `mti = "0100"` (копия запроса); `"0110"` только у отказов самого Switch | api-spec, модель AuthorizationResponse | дефект, средняя: потребитель не может опираться на mti ответа |
| D-02 | лимиты (шаги 4-5) проверяются раньше баланса (шаг 6) | баланс проверяется первым: сумма выше и баланса, и лимита даёт 51 | tz/04, «строго в этом порядке» | дефект, низкая: меняется только код отказа |
| D-03 | CMS недоступен -> 05 `ISSUER_TIMEOUT` | 96 `SERVICE_UNAVAILABLE`; проверено остановкой контейнера CMS: через Gateway HTTP 200 с телом отказа, напрямую Authorization HTTP 503 | tz/04 п.5 | документация, средняя |
| D-04 | RRN = `YDDDHHmmss` + seq | RRN = цифра года + день года + 8 цифр последовательности БД | tz/04 п.6 | документация, низкая: 12 цифр и уникальность соблюдены |
| D-05 | срок сравнивается с текущей датой | срок сравнивается с `transmissionDateTime` из запроса: клиент может «оживить» просроченную карту прошлой датой | tz/04 шаг 3 | дефект, средняя |
| D-06 | `/health` -> 200 | при недоступном сервисе 503 `degraded`; проверено остановкой CMS: кроме `cardManagement` недоступными помечены и работающие `switch`, `authorization` (их health зависит от CMS), что мешает найти источник сбоя | api-spec §1 | документация, низкая |
| D-07 | `stan` String(6) | Gateway не проверяет длину; Authorization отвечает 400; Switch трижды повторяет запрос (около 3 с) и возвращает 05 `Authorization service unavailable` | api-spec AuthorizationRequest | дефект, высокая: ошибка клиента выдаётся за сбой сервиса и нагружает circuit breaker |
| D-08 | ошибка валидации называет нарушенное правило | `dateFrom > dateTo` (search) и `from > to` (charts) -> 400 с пустым `message`: обработчик собирает только ошибки полей | требование ясного тела ошибки; GlobalExceptionHandler Logger | дефект, средняя |
| D-09 | ответ отражает текущее состояние карты | после авторизации `GET /api/cards/{pan}` через Gateway до 5 минут отдаёт старый баланс (`X-Cache: HIT`): резерв идёт в CMS мимо Gateway и не инвалидирует кеш | ожидаемая согласованность чтения; ResponseCaching | дефект, высокая |
| D-10 | лимиты при создании не обязательны (нет `@NotNull`, спецификация не помечает) | без лимитов -> 400 `OutOfRetriesException ... PanCollisionException`: нарушение NOT NULL выдаётся за коллизию PAN | CreateCardRequest, api-spec §2 | дефект, средняя |
| D-11 | некорректный ввод -> 400 | CMS отвечает 500 на нечитаемый JSON (create), неизвестный статус в PATCH и в списке, пустое тело `/api/bins` | общий контракт ошибок | дефект, средняя; тесты идут напрямую в CMS, чтобы 5xx не открывали circuit breaker Gateway |
| D-12 | суммы в минорных единицах (целые) | дробная сумма проходит Gateway (`> 0`) и одобряется с RRN, но `available_balance NUMERIC(19)` округляет: 1000 - 0.4 = 1000, резерва нет | api-spec «Правила» | дефект, высокая: бесплатные одобрения |
| D-13 | баланс и лимиты неотрицательны; `daily <= monthly` | отрицательный `initialBalance` и `availableBalance` (PATCH) принимаются; `daily > monthly` принимается при создании, хотя PATCH это запрещает | tz/05, practice-2 TC-19 | дефект, средняя |
| D-14 | из тела ошибки следует поле | CMS отдаёт только текст первого нарушения без имени поля (`must not be blank`, `The length must be exactly 3 symbols`) | требование 4 задания | дефект, низкая |
| D-15 | `issuerId` из 6-10 символов `[A-Z0-9]` | `@IssuerId` без `@Constraint`: `POST /api/bins` принимает `issuerId = "x"` (проверено вручную, `/api/bins` вне области) | IssuerIdValidator | дефект, низкая |
| D-16 | `amount` проверяется на входе | напрямую в Authorization `amount = 0` проходит `@PositiveOrZero`, CMS отклоняет резерв (400), Authorization отвечает 404 `CARD_NOT_FOUND` | AuthorizationRequest, ReserveRequest | дефект, средняя (через Gateway недостижимо) |
| D-17 | статусы отказов описаны в `@ApiResponses` | `EXCEEDS_AMOUNT_LIMIT` возвращается как 400 (ветка `default`), в аннотациях не описан | AuthControllerImpl | документация, низкая |
| D-18 | Reversal снимает запись из `limit_usage` | откат возвращает деньги, но не дневной расход: та же сумма после отката отклоняется с 61 | tz/04 «Reversal» | дефект, средняя |
| D-19 | - | частичный откат закрывает резерв: остаток вернуть нельзя (409) | Reservation.startRollback | ограничение, низкая: Switch всегда откатывает полную сумму |
| D-20 | `stan` 6 цифр (TransactionRequest) | через очередь сохраняется `stan = "ABCDEF"` (payload `@RabbitListener` не валидируется), тот же текст через HTTP `/api/internal/log` -> 400 | TransactionRequest | дефект, средняя |
| D-21 | маршруты Gateway соответствуют эндпоинтам | `GET/PUT/DELETE /api/transactions` -> 404 от Logger вместо 405; `/api/simulator/merchants`, `/api/bins`, `/api/notifications` не маршрутизируются; `text/plain` проходит валидацию и даёт 415 от Switch с внутренним путём `/api/internal/route` | application.yml Gateway, api-spec | дефект, низкая (утечка внутреннего пути - средняя) |
| D-22 | `expiryDate` String(4) `MMYY` (`"1228"`) | `"2029-11"` (YearMonth) | api-spec модель Card | документация, средняя: ломает разбор у потребителя |
| D-23 | `transactionsPerMinute` дробное (12.3) | целое (`long`) | api-spec §5 | документация, низкая |
| D-24 | `POST /api/cards`: ошибка 409 при существующем PAN | PAN генерирует сервер, 409 недостижим; неизвестный BIN -> 404 (не описан) | api-spec §2 | документация, низкая |
| D-25 | `/run` терминала: `count`, `scenario`; openapi: `/start`, `/status` | обязателен `tps >= 1`; вместо `/start`, `/status` есть `/start-continuous`, `/stop` | api-spec §4, openapi.yaml | документация, средняя: запрос из примера спецификации получает 400 |
| D-26 | коды 12, 30, 41, 43 | не используются; используется 96, которого нет в таблице | api-spec «Коды ответов» | документация, низкая |
| D-27 | перечень эндпоинтов | в коде есть export, charts, список карт, PATCH, DELETE, bulk-update, reserve/rollback, internal rollback, bins (раздел 1) | api-spec | документация, низкая |
| D-28 | одинаковый текст 503 с понятным потребителю именем сервиса | открытый circuit breaker отвечает `cardManagement service is temporarily unavailable` (внутреннее имя маршрута, строчная буква; подтверждено тестом), `DownstreamErrorFilter` при недоступности сервиса пишет `CardManagement service is temporarily unavailable` (проверено остановкой CMS): одна ситуация, два текста | Gateway | дефект, низкая |
| D-29 | лимит на клиента | ключ лимита берётся из `X-Forwarded-For` без доверенного прокси: клиент обходит лимит, подставляя заголовок (тесты этим изолируют бакеты) | ClientIpResolver (комментарий в коде) | ограничение, средняя (безопасность) |
| D-30 | GET по PAN с неверной контрольной цифрой -> 400 | Луна не проверяется: 404 | practice-2 TC-21 | документация (тест-дизайн), низкая |
| D-31 | порядок результатов поиска не описан | поиск сортирует по `createdAt` по убыванию (сортировка по умолчанию `OffsetBasedPageRequest`); тест пагинации проверяет порядок и отсутствие пересечения страниц | api-spec §3, TransactionService.search | документация, низкая |
| D-32 | размер страницы по умолчанию | `GET /api/cards` без `limit` отдаёт 10 (`defaultValue = "10"`), а `app.card-service.defaults.page-limit = 50` не применяется | CardController, application.properties | дефект, низкая |
| D-33 | `currencyCode` числовой (`"643"`), `stan` цифровой | Gateway проверяет только длину: `"ABC"` и `"ABCDEF"` проходят | api-spec | ограничение, низкая |
| D-34 | `WS /ws/transactions` на базовом URL Gateway | через Gateway 404, сокет есть только у Logger (`101` напрямую) | api-spec §6 | дефект, средняя |
| D-35 | export: «параметры limit/offset не применяются» | не применяются, но проверяются: `limit=0` даёт 400, хотя выгрузка этот параметр игнорирует | `@Operation` TransactionController.export | ограничение, низкая |
| D-36 | `createdAt` карты - одно значение | ответ `POST /api/cards` содержит время с наносекундами, в БД оно округляется до микросекунд: последующий `GET` отдаёт другое значение, а фильтр `endDate`, равный `createdAt` из ответа создания, может не включить саму карту | Card, api-spec §2 | дефект, низкая |
| D-37 | из тела ошибки следует поле (требование 4) | 400 симулятора терминала содержит сырой текст `MethodArgumentNotValidException` с сигнатурой метода контроллера и именами классов; поле видно только внутри фразы `on field 'tps'` | GlobalExceptionHandler симулятора терминала | дефект, низкая (утечка внутренних имён) |

Если тест ожидает дефектное поведение, расхождение обязательно есть в этой таблице, а тест помечен `known-defect`.

---

## 5. Обоснование достаточности

### Какие классы данных я выделил

| Что проверяется | Классы |
|---|---|
| Поля запроса транзакции | поле есть, отсутствует, `null`, пустое, из пробелов; неверная длина (на единицу больше и меньше нормы); недопустимые символы; для `amount` ноль, отрицательное и минимальное положительное; нечитаемое тело |
| Поля, которые Gateway не проверяет (`stan`, `currencyCode` из букв) | отдельно посмотрел, что с ними происходит дальше в цепочке |
| Состояние карты | каждый статус, удалённая и несуществующая карта; срок до конца месяца и после; баланс меньше, равен и больше суммы; дневной и месячный лимит ниже, на границе и выше |
| Маршрутизация | BIN есть в таблице, нет в таблице, PAN короче BIN |
| Повторная доставка | новое сообщение; то же самое; то же с другим `createdAt` или масштабом числа; тот же id с другими данными |
| Поиск и список карт | каждый фильтр отдельно; комбинации по попарной таблице L9; пустая выборка; противоречивые фильтры; границы `limit` и `offset`; неверные типы |
| Асинхронность | запись появляется (сквозной тест) и не появляется (отклонённый запрос, откат) |

### Почему этого достаточно

Список эндпоинтов я собрал из спецификации, контроллеров и маршрутов Gateway и сверил с OpenAPI, который отдаёт сам Gateway. Статус-коды и причины отказов брал прямо из кода: обработчиков ошибок, `AuthControllerImpl` и `DeclineOutcome`. Всё, что можно получить запросом, покрыто; остальное перечислено в разделах 1 и 2 с объяснением. В каждом негативном тесте ровно одно нарушение. Мутации из раздела 6 показывают, что тесты замечают изменения и в Gateway, и в Authorization, и в Logger.

### Что не покрыто и почему
- Ответы при отказе зависимостей (503 Gateway и Authorization, `degraded` в `/health`, сбой RabbitMQ). Чтобы их получить, нужно останавливать контейнеры; это тема модуля 6. Отказ CMS я один раз проверил вручную, остановив контейнер, результат описан в разделе 4. Единственный 503, который получается обычными запросами (открытый circuit breaker), покрыт тестом.
- `RESERVATION_FAILED`, `ROLLBACK_FAILED`, `UNKNOWN_REASON`: через API не воспроизводятся (раздел 2).
- `WS /ws/transactions`: это не HTTP-контракт, проверено только отсутствие маршрута (D-34).
- `start-continuous` запускается с `tps = 1` на невалидных PAN и сразу останавливается, поэтому другим тестам не мешает.
- `/api/bins` (CMS), `GET /api/simulator/merchants`, `/api/notifications` (Notification Service): не маршрутизируются Gateway и не участвуют в цепочке авторизации и логирования, поэтому вне требования 1; проверено только их отсутствие в таблице маршрутов. Данные уведомлений поступают из RabbitMQ (модуль 6).
- Точная граница rate limit: число пропущенных запросов зависит от скорости стенда, поэтому проверяется только сам факт ограничения и восстановление.

### Изоляция

Каждый тест создаёт свои карты, STAN, RRN, UUID, merchantId и адрес клиента; константных идентификаторов, общих между тестами, нет. Начальное состояние (баланс, лимиты, статус) задаётся явно через API. Единственная общая фикстура - `TransactionSearchTest` (жизненный цикл PER_CLASS): данные создаются классом и только читаются, все запросы ограничены уникальным merchantId. Тесты выполняются последовательно, поэтому оконные фильтры (`startDate` в списке карт) видят только свои данные. Ответы с 5xx вызываются напрямую в CMS, чтобы не открыть circuit breaker Gateway. Баланс читается напрямую из CMS, мимо кеша Gateway (D-09).

### Таймауты ожиданий (`support/Waits`)

Switch публикует транзакцию в RabbitMQ до ответа клиенту, Logger сохраняет её из очереди. Локально запись появляется в поиске за доли секунды.
- Появление: `atMost` 10 с с опросом раз в 250 мс. Запас взят под медленный CI; если за 10 секунд записи нет, она потеряна.
- Отсутствие: окно `during` 3 с (в несколько раз больше обычной задержки), `atMost` 5 с, опрос раз в 500 мс. В каждом таком тесте есть контрольная запись, которая должна появиться.
- Rate limit восстанавливается за 1 с (`Retry-After: 1`), ждём до 2 с. Circuit breaker открывается на 10 с, ждём до 15 с.

---

## 6. Доказательства работоспособности проверок

Методика: изменение вносилось в код сервиса (или в конфигурацию, или в ожидание), образ пересобирался `docker compose build <service>`, контейнер пересоздавался, запускался соответствующий класс тестов. После проверки исходник возвращался `git checkout`, а исходный образ - тегом `:orig`, сохранённым до мутаций.

| Тест | Внесённое изменение | Результат |
|---|---|---|
| `TransactionLogRedeliveryTest` (мутация дедупликации) | `TransactionService.store`: поиск существующей записи по id заменён на `Optional.empty()` - дедупликация отключена | 6 из 16 красные: `identicalRedeliveryReturnsExistingRecord`, 3 строки `equivalentRedeliveryIsAccepted` (`Expected status code <200> but was <201>`), 2 строки `conflictingRedeliveryIsRejected` (`Expected status code <409> but was <201>`) |
| `BusinessDeclineTest$InsufficientFunds.amountEqualToBalanceIsApproved` | `AuthServiceImpl`: условие нехватки средств `> 0` заменено на `>= 0` (сдвиг границы) | красный: `Expected: APPROVED, Actual: DECLINED`; остальные тесты отказов зелёные - мутация затронула только границу |
| `GatewayRateLimitTest` | Gateway перезапущен с лимитом 100000 запросов в секунду | `burstAboveCapacityIsLimited` красный. А вот `bucketIsPerClientAndRecovers` сначала остался зелёным: он не проверял, что лимит вообще сработал. Добавил это условие, после чего с мутацией красные оба теста |
| `TransactionValidationTest.formatViolationIsRejected` (mcc) | `TransactionRequestValidator`: удалена проверка `mcc` на 4 цифры | 3 строки красные (`541`, `54111`, `54A1`): `Expected status code <400> but was <200>` |
| Тесты со схемой `authorization-response.json` | из схемы удалено свойство `processingTimeMs` (при `additionalProperties: false`) | 15 из 19 тестов `TransactionApprovalTest` и `BusinessDeclineTest` красные (остальные схему не проверяют) |

---

## 7. Порядок запуска

**Локально** (нужны Docker и JDK 21):

```bash
cp .env.example .env
docker compose up -d
```

Дождаться, пока `docker compose ps` покажет все контейнеры `healthy`, затем:

```bash
make api-test
```

или без make:

```bash
mvn -f services/pom.xml -Papi-tests -pl api-tests test
```

Отдельный класс: `mvn -f services/pom.xml -Papi-tests -pl api-tests test -Dtest=BusinessDeclineTest`. Адреса сервисов переопределяются системными свойствами (`-Dgateway.url=...`, `-Dcards.url=...`, см. `support/ApiConfig`) или переменными `API_TESTS_*_URL`. Отчёты: `services/api-tests/target/surefire-reports`.

**Стабильность:** три прогона подряд на одном стенде без очистки БД, все 309 тестов зелёные каждый раз.

**CI:**
- [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml), джоба `api-tests-compile`: на каждый push - checkstyle и компиляция модуля (без стенда).
- [`.github/workflows/api-tests.yml`](../../.github/workflows/api-tests.yml): автоматически на push и pull request, если изменились сервисы, тесты или `docker-compose.yaml`, а также вручную (`workflow_dispatch`). Шаги: сборка образов, `docker compose up -d`, ожидание `healthy` и `status: ok` у Gateway, `mvn -Papi-tests -pl api-tests test`, выгрузка surefire-отчётов, логи при падении, `docker compose down -v`.
