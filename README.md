# СМП — Симулятор процессингового центра

**Образовательный проект курса «Тестирование ПО» (ИТМО / Lekton)**

---

## Концепция

`СМП` — это **Симулятор процессингового центра**: упрощённая модель реального процессинга на микросервисной архитектуре. Проект эмулирует путь банковской транзакции от POS-терминала до авторизации на стороне эмитента и обратно — внутри замкнутой системы «своих» карт.

> Легенда-шутка: расшифровку «Система медленных платежей» мы упоминаем один раз как внутреннюю шутку команды — официально СМП означает «Симулятор процессингового центра».

**Ключевые особенности объекта тестирования:**

- 11 микросервисов + PostgreSQL + RabbitMQ;
- гибридное взаимодействие: синхронный HTTP (авторизация, резервирование) + асинхронный RabbitMQ (логирование, уведомления);
- eventual consistency — запись в лог происходит не мгновенно, а после доставки через очередь;
- изоляция через API и очереди, а не через прямые обращения к чужой БД;
- только «свои» тестовые карты (никаких внешних BIN);
- все данные синтетические, ISO 8583 эмулируется упрощённым JSON;
- **все тесты удалены** — вы пишете их заново в течение семестра.

Архитектура и модель данных описаны в [`docs/architecture.md`](docs/architecture.md).

---

## Быстрый старт

```bash
# 1. Клонировать СВОЙ репозиторий (его выдаёт куратор), а не эталонный
git clone https://github.com/<org>/Practic<Имя><Фамилия>.git
cd Practic<Имя><Фамилия>

# 2. Скопировать .env (переменные окружения уже настроены на нужные порты)
cp .env.example .env
# Windows PowerShell: Copy-Item .env.example .env

# 3. Запустить все сервисы (11 сервисов + PostgreSQL + RabbitMQ)
docker compose up -d

# 4. Проверить, что всё работает
curl http://localhost:8080/health

# 5. RabbitMQ Management UI: http://localhost:15672 (логин smp / пароль smp)

# 6. Сгенерировать тестовые карты (через Gateway)
curl -X POST http://localhost:8080/api/cards/generate \
  -H "Content-Type: application/json" \
  -d '{"count": 100, "bins": ["400000","400001","400002","400003","400004"]}'

# 7. Запустить симулятор терминалов (50 транзакций, через Gateway)
curl -X POST http://localhost:8080/api/simulator/terminal/run \
  -H "Content-Type: application/json" \
  -d '{"count": 50, "scenario": "normal"}'

# 8. Открыть дашборд: http://localhost:3000
```

**Профиль мониторинга (`observability`):** Prometheus, Grafana, Loki, Promtail и autoscaler вынесены в отдельный compose-профиль и **по умолчанию выключены**. Базовый запуск `docker compose up -d` поднимает только 11 сервисов + PostgreSQL + RabbitMQ. Полный стек с мониторингом (понадобится в модуле 8):

```bash
docker compose --profile observability up -d
```

**Быстрая проверка одной командой:**

```bash
docker compose up -d && sleep 10 && ./scripts/smoke-test.sh
```

Вывод должен заканчиваться строкой `🎉 ALL CHECKS PASSED`.

> 📖 Подробная инструкция по Git-воркфлоу и сдаче артефактов — [`docs/submission-guide.md`](docs/submission-guide.md).

---

## Карта сервисов и портов

| Сервис | Порт | Назначение |
|--------|:---:|---|
| Gateway Service | 8080 | Единая точка входа REST API |
| Card Management | 8081 | Управление картами, генерация тестовых данных |
| Switch / Router | 8082 | Маршрутизация транзакций по BIN |
| Authorization | 8083 | Решение APPROVED/DECLINED, проверка лимитов |
| Terminal Simulator | 8085 | Эмулятор POS-терминалов |
| Merchant Simulator | 8084 | Эмулятор мерчантов + эквайрера |
| Transaction Logger | 8088 | Логирование транзакций + WebSocket |
| Bin Lookup | 8096 | Внешний API обогащения по BIN |
| Notification Service | 8097 | Уведомления о карточных событиях |
| Web Dashboard | 3000 | React SPA, визуализация |
| PostgreSQL | 5432 | БД (одна, но подключаются только 4 сервиса) |
| RabbitMQ (AMQP) | 5672 | Асинхронный брокер |
| RabbitMQ (Management UI) | 15672 | Веб-консоль очередей |

---

## Что тестируем по модулям 1–8

Каждый модуль добавляет свой уровень тестирования к одному и тому же объекту — СМП.

| Модуль | Что тестируем | Ключевые материалы |
|:---:|---|---|
| 1. Введение | Запуск СМП, health-check'и, smoke-проверки | [`docs/architecture.md`](docs/architecture.md), [`scripts/smoke-test.sh`](scripts/smoke-test.sh) |
| 2. Тест-дизайн | Классы эквивалентности, границы, decision table по требованиям | [`tz/04-authorization.md`](tz/04-authorization.md), [`tz/05-card-management.md`](tz/05-card-management.md) |
| 3. Пирамида + CI/CD | Уровни тестов, GitHub Actions, quality gates | [`docs/api-spec.md`](docs/api-spec.md), [`.github/workflows/ci.yml`](.github/workflows/ci.yml) |
| 4. Unit-тесты | JUnit 5, Mockito, покрытие бизнес-логики | [`services/`](services/), [`tz/02-gateway.md`](tz/02-gateway.md), [`tz/03-switch.md`](tz/03-switch.md) |
| 5. API-тесты | REST-контракты, статус-коды, decline-коды | [`docs/api-spec.md`](docs/api-spec.md), [`tz/02-gateway.md`](tz/02-gateway.md) |
| 6. Интеграционные тесты | БД в контейнерах, RabbitMQ, внешние сервисы | [`tz/04-authorization.md`](tz/04-authorization.md), [`tz/08-transaction-logger.md`](tz/08-transaction-logger.md) |
| 7. E2E | Сквозные бизнес-сценарии покупки/возврата/decline | [`docs/e2e-test-plan.md`](docs/e2e-test-plan.md) |
| 8. CI/CD + стратегия | Финальный пайплайн, Allure, coverage map | [`docs/submission-guide.md`](docs/submission-guide.md) |

Полный список технических заданий: [`tz/`](tz/). Сдача и проверка — [`docs/submission-guide.md`](docs/submission-guide.md).

---

## Структура репозитория

```text
Practic/
├── README.md                        # этот документ
├── docker-compose.yaml              # оркестрация всех сервисов
├── .env.example                     # шаблон переменных окружения (порты)
├── docs/
│   ├── architecture.md              # архитектура, модель данных, порты
│   ├── api-spec.md                  # OpenAPI-контракты
│   ├── e2e-test-plan.md             # E2E-сценарии и матрицы покрытия
│   ├── submission-guide.md          # как сдавать артефакты
│   └── checklists.md                # чек-листы само-приёмки
├── tz/                              # технические задания (9 ролей)
├── services/                        # исходный код 11 сервисов (без тестов)
├── scripts/
│   ├── smoke-test.sh                # авто-приёмка (Linux/Mac)
│   └── smoke-test.ps1               # авто-приёмка (Windows)
├── starters/                        # starter kits (Java/Go/Python/TypeScript)
└── infra/                           # prometheus, grafana, loki, promtail
```

---

## Контакты

**Куратор практики:** Андрей Попов (Lekton)
**Репозиторий:** https://github.com/LektonSoftwareTestingCourse/Practic
