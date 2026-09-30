# Таблицы

Синхронизировано с `WalletDbHelper` / `WalletContract`, `DATABASE_VERSION = 72`.
Общая логика («кошельки и категории — одна таблица», наследование через общий
первичный ключ) разобрана в [`docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md).

## Колонки синхронизации (v72)

Все 9 таблиц ниже (`icon`, `account`, `account_operating`, `currency`,
`currency_pair`, `currency_pair_rate`, `currency_conversion`, `operation`,
`operation_link`) получили одинаковый набор из четырёх колонок
(servers/TODO_mobile_sync.md, Фаза 3) — не повторяется в каждой из
табличек ниже отдельно, чтобы не разъезжаться при правках:

| Поле | Тип | Описание |
| --- | --- | --- |
| `uuid` | TEXT NOT NULL DEFAULT `(lower(hex(randomblob(16))))` | Идентификатор строки для синка с сервером — генерируется по умолчанию при вставке. На апгрейде уже существующей базы (v68-71 → v72) DEFAULT не мог сработать (SQLite не разрешает выражение в `ALTER TABLE ADD COLUMN`, только константу) — существующие строки получают `uuid` отдельным проходом, см. `WalletDbHelper.backfillUuid` |
| `dirty` | INTEGER NOT NULL DEFAULT 1 | Офлайн-outbox: `1` — строка ещё не отправлена на сервер. Сбрасывает в `0` только сам синк-движок; любое другое изменение форсит обратно в `1` через триггер (см. `../triggers/README.md`, `trg_sync_dirty.sql`) — так гарантия не зависит от того, через какой именно код-путь пришла запись |
| `deleted_at` | INTEGER | Момент мягкого удаления (epoch millis, `System.currentTimeMillis()`); `NULL` — строка активна. Tombstone едет на сервер как обычное изменение — жёсткого `DELETE` для этих таблиц больше нет (кроме «Удалить все операции», см. ниже) |
| `updated_by_device` | TEXT | Диагностика — устройство, применившее последнее изменение; не участвует ни в одном решении о конфликте, ровно как `updated_at` на сервере |

Представления (`../view/`) фильтруют `deleted_at IS NULL` — см.
[`../view/README.md`](../view/README.md).

**Осознанно не тронуто**: «Удалить все операции» (`MoreActivity.onDeleteAllOperations`)
остаётся настоящим локальным `DELETE`, без tombstone и без отправки на сервер —
это инструмент «начать заново на этом устройстве», а не «удалить историю у всех».

## `icon`

Иконки хранятся в базе как BLOB (пользователь добавляет свои из галереи), а не как
ресурсы приложения.

| Поле | Тип | Описание |
| --- | --- | --- |
| `_id` | INTEGER PK AUTOINCREMENT | |
| `name` | INT NOT NULL UNIQUE | Числовой идентификатор/подпись иконки — historically объявлен INT, а не TEXT; смысла строки не несёт |
| `image` | BLOB NOT NULL | Изображение WEBP 256×256 (см. ARCHITECTURE.md, раздел «Иконки») |
| `icon_type` | INT NOT NULL | `0` — категория, `1` — флаг (`Icon.IconType`); всё, кроме `1`, трактуется как категория |

Удаление запрещено внешним ключом, а не кодом: `account.icon_id`
(`fk_account_icon_id`) и `currency.icon_id` (`fk_currency_icon_id`) ссылаются на
`icon._id`, и при включённом `PRAGMA foreign_keys = ON` (см.
`WalletDbHelper.getWritableDatabase`) `DELETE`, нарушающий эти FK, падает с
`SQLiteConstraintException`. `IconEditDialog.deleteIcon` просто ловит это
исключение и показывает сообщение — отдельного `SELECT COUNT(*)` перед удалением
в коде больше нет.

## `account`

Единая таблица для кошельков и категорий расходов/доходов. Строка становится
кошельком, если для того же `_id` есть строка в `account_operating`.

| Поле | Тип | Описание |
| --- | --- | --- |
| `_id` | INTEGER PK AUTOINCREMENT | |
| `name` | TEXT NOT NULL UNIQUE | |
| `comment` | TEXT NOT NULL | |
| `icon_id` | INT NOT NULL, FK → `icon._id` | Код всегда подставляет «неизвестную» иконку, если пользователь не выбрал свою (`AddWalletActivity`/`AddCategoryActivity`) — NULL и не допускается схемой, и не встречался бы в реальных данных |
| `is_archived` | INT DEFAULT 0 | Флаг архивации. **Признак только для интерфейса**: архивный счёт не предлагается при выборе счёта для операции (`AccountPickerActivity`; в режиме справочника он, наоборот, виден — иначе архив не снять), иконка серая в списке счетов, счёт исключён из попапа «валюты в использовании». В расчётах он остаётся наравне с остальными — и в отчётах, и в карточке остатков: иначе архивация молча меняла бы итоги за прошлые периоды. Не дошёл фильтр до `MultiPickerActivity` (выбор счетов в фильтре операций), см. [`docs/TODO.md`](../../docs/TODO.md) |
| `system_kind` | INT NOT NULL DEFAULT 0 | v75. `0` — обычная категория, `1` — `Transfer`, `2` — `Transfer commission` (`AccountItem.SystemKind`, `fromValue` — безопасный дефолт `0` на незнакомое значение). Заменяет прежнее распознавание системности по `name` |
| `kind` | INT NOT NULL DEFAULT 0 | v76. `0` — категория, `1` — кошелёк (`AccountItem.Kind`). Дублирует явным полем то, что раньше определялось только наличием строки в `account_operating` — см. раздел `account_operating` ниже. Проставляется один раз в момент создания строки (`WalletDbCRUD.InsertAccount`/`InsertOperatingAccount`, `WalletFillData`, `WalletImportData`) и дальше не меняется — конвертации категории в кошелёк и обратно в коде нет |

Две строки системные — категории `Transfer` (через неё идут ноги валютного перевода)
и `Transfer commission` (категория комиссии по умолчанию). С v75 признак явный —
`system_kind` (`AccountItem.SystemKind`), а не имя: `WalletDbCRUD.DeleteAccount` и
`AccountItem.isSystem()`/`isSystemCommission()` читают именно его, поэтому
переименование системной категории больше не снимает защиту от удаления/
переименования. Сравнение по имени (`AccountItem.isSystemName`) осталось только в
двух местах — миграция v75 (`WalletDbHelper.onUpgrade`) и сидирование новой базы
(`WalletFillData.stampSystemCategories`) — оба переводят старое неявное состояние
(имя) в новое явное (`system_kind`) и новых сравнений добавлять не должны. Системные
категории переживают и кнопку «Удалить все операции» (`MoreActivity`, фильтр по
`system_kind = 0`, то есть удаляется всё, КРОМЕ системных). Любую другую категорию
`WalletDbCRUD.DeleteAccount` удалит, только если по ней нет ни одной операции, иначе
отказывает (`DeleteAccountResult.hasOperations`) — а если проверку обойти, `DELETE`
упрётся во внешние ключи `operation.acc_fr_id`/`acc_to_id` при включённом
`PRAGMA foreign_keys = ON`. Убрать использованную категорию можно будет только
слиянием — см. [`docs/TODO.md`](../../docs/TODO.md).

## `account_operating`

Расширение `account` для кошельков: строка есть — счёт операционный (есть валюта и
баланс), строки нет — это категория (с v76 то же самое говорит `account.kind` —
см. выше). У категории своей валюты нет и быть не может:
валюта операции по категории определяется второй стороной проводки — операционным
счётом (кошельком), см. `v_operation.currency_id` в
[`../view/README.md`](../view/README.md).

| Поле | Тип | Описание |
| --- | --- | --- |
| `_id` | INTEGER PK | Не автоинкремент — всегда равен `account._id`, которому расширяет запись |
| `currency_id` | INTEGER NOT NULL, FK → `currency._id` | |

## `currency`

| Поле | Тип | Описание |
| --- | --- | --- |
| `_id` | INTEGER PK AUTOINCREMENT | |
| `name` | TEXT NOT NULL UNIQUE | |
| `iso_code` | TEXT NOT NULL UNIQUE | |
| `icon_id` | INTEGER NOT NULL, FK → `icon._id` | Иконка-флаг |
| `symbol` | TEXT NOT NULL | Символ для отображения сумм (`$`, `€`, …) |
| `precision` | INTEGER NOT NULL DEFAULT 2 | Знаков после запятой в суммах этой валюты; добавлена миграцией v69. Определяет и вывод (`AdapterUtils.getFormat`), и `InputFilter` в полях суммы |

## `currency_pair`

Валютная пара с направлением «база → квота» и собственной точностью курса.

| Поле | Тип | Описание |
| --- | --- | --- |
| `_id` | INTEGER PK AUTOINCREMENT | |
| `symbol` | TEXT NOT NULL UNIQUE | |
| `base_currency_id` | INTEGER NOT NULL, FK → `currency._id` | |
| `quote_currency_id` | INTEGER NOT NULL, FK → `currency._id` | |
| `precision` | INTEGER NOT NULL DEFAULT 5 | Знаков после запятой в курсе пары; добавлена миграцией v70 |

## `currency_pair_rate`

Курс пары на дату. `insert or replace` при загрузке — повторная загрузка того же
периода безопасна.

| Поле | Тип | Описание |
| --- | --- | --- |
| `currency_pair_id` | INTEGER, FK → `currency_pair._id` | Часть составного PK |
| `date` | DATETIME | Часть составного PK |
| `rate` | DECIMAL(12,5) | Курс `quote` за 1 `base` |

## `currency_conversion`

Задумана как таблица ручных переопределений маршрута пересчёта одной валюты в
другую, когда прямой валютной пары нет. **В текущей версии приложения ни один код-путь
не пишет в эту таблицу** (`WalletDbCRUD`, `WalletFillData`, `WalletImportData` её не
касаются) — она создаётся и остаётся пустой. Вьюха `v_currency_conversion` всегда
резолвит курс через `currency_pair` (`COALESCE` откатывается на пары), так что
присутствие этой таблицы в схеме сейчас ни на что не влияет.

| Поле | Тип | Описание |
| --- | --- | --- |
| `from_currency_id` | INTEGER, FK → `currency._id` | Часть составного PK |
| `to_currency_id` | INTEGER, FK → `currency._id` | Часть составного PK |
| `num` | INTEGER | Часть составного PK — номер альтернативного маршрута пересчёта (задумывался для нескольких путей между одной парой валют) |
| `currency_pair_id` | INTEGER, FK → `currency_pair._id` | |
| `is_reverse` | INTEGER | Признак «пара развёрнута» |

## `operation`

Единственная таблица транзакций. Любая операция — перевод между двумя `account`:
расход это «кошелёк → категория», доход «категория → кошелёк», перевод
«кошелёк → кошелёк». Операция «категория → категория» отсекается на уровне
приложения (валидация, загрузка, адаптер) — в базе такому ничто не мешает.

| Поле | Тип | Описание |
| --- | --- | --- |
| `_id` | INTEGER PK AUTOINCREMENT | |
| `date` | DATETIME NOT NULL | |
| `acc_fr_id` | INTEGER NOT NULL, FK → `account._id` | Счёт-источник |
| `acc_to_id` | INTEGER NOT NULL, FK → `account._id` | Счёт-получатель |
| `amount` | DECIMAL(15,2) NOT NULL | Всегда положительное число; знак/цвет расхода — только на экране, по направлению `acc_fr`/`acc_to` |
| `comment` | TEXT NOT NULL | Свободный текст, в UI многострочный (до 3 строк видимых, без ограничения в хранении); пустая строка допустима |

## `operation_link`

Связь проводок, которые пользователь завёл одной операцией, а база хранит
несколькими. Появилась в v71, потому что у `operation` одна колонка `amount`, а
переводу между кошельками в разных валютах нужны две суммы — значит и две
проводки. Второй случай — комиссия за перевод: она уходит на свою категорию, то
есть тоже отдельной проводкой.

Что связывается:

- перевод между **разновалютными** кошельками — две ноги через системную
  категорию `Transfer` (кошелёк → `Transfer` и `Transfer` → кошелёк);
- **комиссия** за перевод — проводка с кошелька выбранной стороны на категорию
  комиссии (по умолчанию `Transfer commission`). Связывается в обоих случаях,
  в том числе когда валюты одинаковые и сам перевод — одна проводка.

| Поле | Тип | Описание |
| --- | --- | --- |
| `main_id` | INTEGER NOT NULL, FK → `operation._id` ON DELETE CASCADE | Часть составного PK. **Всегда проводка списания с кошелька-источника**: для валютного перевода — первая нога, для одновалютного — сам перевод. По ней находится вся операция целиком |
| `link_id` | INTEGER NOT NULL, FK → `operation._id` ON DELETE CASCADE | Часть составного PK. Вторая нога или комиссия |
| `type` | INTEGER NOT NULL DEFAULT 0 | v77. `0` — нога валютного перевода, `1` — комиссия (`OperationItem.LinkType`). Явная роль связки — заменяет прежний вывод по `system_kind` счёта-источника связанной проводки (а до него — по случайному совпадению id), см. `WalletDbLoader.AttachLinksTo`/`TransactionActivity.loadLinkedParts` |

`ON DELETE CASCADE` снимает только саму строку связи вместе с удалённой
проводкой — связанные проводки при этом остаются. Само удаление операций
всегда мягкое (`WalletDbCRUD.DeleteOperation`), так что этот момент сегодня
не наступает; удаление всей группы (или выбранных её частей) делает
`TransactionActivity.showDeleteGroupDialog()`/`deleteOperation(int[])`.

Пишет связи `TransactionActivity.saveTransfer()`/`saveEdit()`/`updateCommission()`.
Читает — `WalletDbCRUD.GetLinkedOperations` (id + `type` каждой связанной
проводки): лента схлопывает их в одну карточку (`WalletDbLoader.AttachLinksTo`
→ `OperationItem.LinkedLeg`/`LinkedCommission`), экран правки раскладывает
обратно (`TransactionActivity.loadLinkedParts`). Импортёр
(`tools/moneylover/db.py`) таблицу создаёт, но не заполняет — у операций,
приехавших импортом, связей нет, см. [`docs/TODO_transfers.md`](../../docs/TODO_transfers.md).

---

## Черновики, не входящие в текущую схему

Ниже — файлы, чьи `CREATE` не выполняются `WalletDbHelper` и на которые не
ссылается ни один Java-запрос (проверено `grep` по всему `app/src`). Оставлены как
исторический контекст, а не как актуальная документация.

### `account_balance.sql`

Заготовка материализованного кэша баланса по месяцам (пара с `triggers/trg_operation.sql`).
FK в файле ссылается на несуществующую таблицу `operating_account` (правильное имя —
`account_operating`) — дополнительное подтверждение, что это неактуальный черновик.
Актуальный расчёт баланса — на лету, см. `../select/GetBalanceToDate.sql` и
`WalletDbLoader.LoadBalancesPerWallet`.

### `triggers/trg_operation.sql`

Триггер, который бы обслуживал `account_balance` при вставке в `operation`. Не
создаётся приложением.
