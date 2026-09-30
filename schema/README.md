# Схема базы (wallet.db)

Снимок DDL в `table/`, `view/`, `triggers/` — читаемое зеркало того, что реально
создаёт `WalletDbHelper` (`app/src/main/java/.../Data/WalletDbHelper.java`)
и именует `WalletContract`. Источник истины — Java-код; файлы здесь синхронизированы
с ним по состоянию на **`DATABASE_VERSION = 72`**.

Общее описание слоёв, ER-диаграмму и разбор нетривиальных мест (расчёт балансов,
курс на дату, три состояния эквивалента) см. в [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md).
Здесь — построчная справка по таблицам и вьюхам.

## Структура папки

| Папка | Содержимое |
| --- | --- |
| `table/` | `CREATE TABLE` по каждой таблице — см. [table/README.md](table/README.md) |
| `view/` | `CREATE VIEW` по каждому представлению — см. [view/README.md](view/README.md) |
| `select/` | Именованные отчётные запросы с параметрами (баланс, доход, расход за период) |
| `triggers/` | `trg_sync_dirty` — настоящие триггеры (v72, см. [triggers/README.md](triggers/README.md)); `trg_operation` — черновик, в приложении не создаётся |

## v72 — синхронизация с сервером (servers/TODO_mobile_sync.md, Фаза 3)

9 синхронизируемых таблиц получили одинаковый набор из 4 колонок (`uuid`,
`dirty`, `deleted_at`, `updated_by_device`) — описаны один раз в
[table/README.md](table/README.md), раздел «Колонки синхронизации», не
повторяются в каждой таблице отдельно. Представления получили фильтр
`deleted_at IS NULL` — см. [view/README.md](view/README.md). Появились первые
настоящие триггеры этой схемы (`dirty=1` при любом `UPDATE`, из какого бы
кода-пути он ни пришёл) — см. [triggers/README.md](triggers/README.md).

## Важно при чтении файлов

Миграции `onUpgrade` в `WalletDbHelper` для баз версии **68 и выше** добавочные —
`ALTER TABLE` и `CREATE TABLE` для новых таблиц (v71 так добавляет `operation_link`,
v72 — все 4 колонки синка сразу на 8 таблиц одним ALTER-проходом на таблицу, плюс
`operation_link` отдельно — см. javadoc `WalletDbHelper.onUpgrade`).
Представления (`view/*.sql`) при этом **раньше не пересоздавались** при
инкрементальном апгрейде — только полным `DROP` + `onCreate` (обновление с версий
**старше 68**). **С v72 это перестало быть верным**: шаг `oldVersion < 72` явно
вызывает `CreateViews(db)` поверх уже существующей базы (сама она начинает каждый
блок с `DROP VIEW IF EXISTS`, поэтому безопасна для повторного вызова) — иначе
новый фильтр `deleted_at IS NULL` не долетел бы до устройств, обновляющихся
инкрементально с v68-71.

**Правило для правок:** любое изменение схемы в `WalletDbHelper`/`WalletContract`
должно попадать сюда в том же изменении — файл в `table/`, `view/` или `triggers/`,
строка в README соответствующей папки и версия в шапках. Эти файлы — зеркало кода,
и расходятся они молча.

Часть файлов в этой папке — черновики функциональности, которой в приложении нет:
`table/account_balance.sql`, `triggers/trg_operation.sql`,
`view/v_account_operating_balance.sql`, `view/v_currency_conversion_max_date_rate.sql`.
Каждый из них помечен предупреждением в начале файла; подробности — в README
соответствующей папки.
