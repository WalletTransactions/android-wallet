# Устройство приложения

Классический слоёный Android-проект без DI и без Room: экраны обращаются к
процессному кэшу-синглтону, а весь SQL сосредоточен в четырёх классах слоя данных.

## Слои

```mermaid
flowchart TB
    subgraph UI
        A["Activities<br/>Fragments"]
        B["Adapters<br/>RateChartView"]
    end
    subgraph Domain["Модель в памяти"]
        S["Singleton<br/>кэш справочников"]
        M["AccountList · CurrencyList<br/>CurrencyPairList · IconList<br/>OperationList"]
    end
    subgraph DataLayer["Слой данных"]
        H["WalletDbHelper<br/>схема и миграции"]
        L["WalletDbLoader<br/>чтение"]
        C["WalletDbCRUD<br/>запись"]
        F["WalletFillData<br/>WalletImportData"]
    end
    DB[("SQLite<br/>wallet.db")]

    A --> B
    A --> S
    B --> S
    S --> M
    A --> L
    A --> C
    S --> L
    L --> DB
    C --> DB
    F --> DB
    H --> DB
```

**UI** — `Activities/` и `Adapters/`. Наследуются от `AppCompatActivity`, тема
`AppTheme.NoActionBar` там, где нужен свой `Toolbar`. Единственное кастомное
представление — `Views/RateChartView` (график курса с перетаскиваемым выделением периода).

**Модель в памяти** — коллекции из `DataItems/`. Списки уведомляют подписчиков через
самодельный `onListChanged` (`List<ListChanged>`), без LiveData.

**Слой данных** — `Data/`:

| Класс | Ответственность |
| --- | --- |
| `WalletContract` | Имена таблиц и колонок, единый источник строковых литералов |
| `WalletDbHelper` | `onCreate` / `onUpgrade`, создание таблиц и представлений |
| `WalletDbLoader` | Все запросы на чтение, включая отчётные |
| `WalletDbCRUD` | Вставки, обновления, удаления, чистка битых операций |
| `WalletFillData` | Первичное наполнение справочников |
| `WalletImportData` | Импорт CSV |

## Кэш справочников

`Singleton.instance` — статический объект процесса. Держит долгоживущие
`SQLiteDatabase` (readable и writable) и лениво загружаемые справочники.

```java
Singleton.instance.getIconList(context);        // грузит один раз
Singleton.instance.reloadIconList(context);     // сбрасывает и перечитывает
```

Кэш надо инвалидировать вручную после записи в базу. `IconEditDialog` вызывает
`reloadIconList` после вставки, обновления и удаления, а `IconManagerActivity`
затем пересобирает адаптер целиком — при смене `icon_type` иконка переезжает
в другую группу, и `notifyDataSetChanged` этого не отразит.

`getReadableDatabase`, `getWritableDatabase` и `getDbHelper` — `synchronized`: базу
открывают и главный поток, и фоновые (`DbInitActivity`, воркеры WorkManager), а без
блокировки два потока могли завести по своему `WalletDbHelper`. Helper создаётся на
application context.

### Первое открытие базы — в фоне

`WalletDbHelper.onCreate` (схема + посев из `seed.json`, иконки перекодируются в PNG)
и `onUpgrade` выполняются там, где базу попросили первой. Раньше это был главный поток
в `MainActivity.onCreate` — на Huawei P20 Pro первый запуск ловил ANR (android-wallet#40,
openspec `fix-first-start-anr`). Теперь точки входа — `MainActivity` (иконка),
`AiActivity` и `StatementImportActivity` («Поделиться») — сразу после `super.onCreate`
зовут `DbInit.redirectIfNeeded`:

- база готова, если файл есть и `user_version ≥ DATABASE_VERSION` (заголовок читается
  `SQLiteDatabase.openDatabase(…, OPEN_READONLY)` без helper'а; после первого «да» —
  статический флаг);
- не готова — экран закрывается, открывается `DbInitActivity` «Готовим кошелёк…»:
  поток `db-init` открывает базу через `Singleton` (там и посев, и миграции), затем
  открывается исходный экран с его intent'ом из `DbInit.EXTRA_NEXT`. ClipData и флаги
  `FLAG_GRANT_*_URI_PERMISSION` переносятся на экран подготовки, иначе право на файл из
  «Поделиться» не доехало бы до «Выписки». Ошибка открытия — текст на том же экране.

Новая точка входа (exported activity), которая трогает базу в `onCreate`, должна
звать `DbInit.redirectIfNeeded` так же. Экраны, открытые изнутри приложения, база
уже готова к их запуску. На P20 Pro подготовка на чистой установке — около 30 с,
почти всё время — `Bitmap.compress` иконок посева.

`getCurrencyPairList` ведёт себя иначе: перечитывает базу при **каждом** вызове.
Поэтому `CurrencyPairList.clear()` обязателен внутри загрузчика, иначе список
дублируется. Расхождение с остальными геттерами намеренным не выглядит.

## Тексты и языки

openspec `android-i18n`, spec `ui-language`. Все тексты интерфейса — ресурсы:
`res/values/` — английский (базовый: любой язык телефона, кроме русского),
`res/values-ru/` — русский. Файлы по экранам: `strings.xml` (тексты layout/menu),
`strings_main.xml`, `strings_operation.xml`, `strings_accounts.xml`,
`strings_currency.xml`, `strings_recurring.xml`, `strings_settings.xml`,
`strings_import.xml`, `strings_sync.xml`, `strings_ai.xml`. Числа — `<plurals>`.
На Android 13+ язык приложения выбирается в системных настройках
(`res/xml/locales_config.xml`, `android:localeConfig`).

- Экраны берут строки через `getString`. Классы без экрана (журнал синка, отчёт
  импорта, `AiTexts`) — через `Texts`: контекст приложения ставит
  `WalletApplication.onCreate`.
- Не переводятся: логи, технические детали исключений (по-английски), SQL, данные в
  файлах экспорта и строки, уходящие на сервер, — такие литералы с кириллицей помечены
  `// not UI`.
- Системные категории хранятся и синкаются как `Transfer` / `Transfer commission`, а
  показываются `AccountItem.getDisplayName()` на языке интерфейса; `getName()` — для
  правки, поиска по имени и синка.
- `ci/check_hardcoded_text.sh` (GitHub CI и `ci/run_unit_tests.sh`) не пускает кириллицу
  в литералах Java и атрибутах layout/menu; `UiLanguageTest` проверяет, что в `values/`
  и `values-ru/` одни и те же ключи и подстановки.
- Robolectric-тесты идут на русском (`src/test/resources/robolectric.properties`,
  `qualifiers=ru`); английский — явным `@Config(qualifiers = "en")`.

## Общая шапка корневых экранов

Четыре таба нижней панели — «Операции» (`MainActivity`), «Кошельки»
(`AccountActivity`), «Отчёты» (`ReportActivity`), «Ещё» (`MoreActivity`) —
наследуются от `BaseTabActivity`. Экран сам знает только про свой список; всё
остальное в шапке общее и живёт в одном месте.

```
setContentView(...) → свои findViewById → initTabScreen(tabId, title) → reloadSharedAndTabData()
                                                                              │
                                        refreshSummaryCard() + refreshFilterBanner() + reloadTabData()
```

| Что | Где |
| --- | --- |
| Период, фильтр по счетам, валюта эквивалента | `PeriodFilterState` (один объект на процесс) |
| Заголовок, кнопки фильтра и валюты | `view_tab_title.xml` + `view_tab_actions.xml` (оба `<merge>`) |
| Пилюля периода, карточка состояния денег, строка фильтра | `view_period_summary.xml` |
| Нижняя панель и «+» | `view_bottom_nav.xml` + `BottomNav.setActiveTab` |
| Цифры карточки | `BaseTabActivity.refreshSummaryCard()` — одна реализация на все табы |

`PeriodFilterState` дублирует своё состояние в `AppSettings`
(SharedPreferences), поэтому период и фильтр переживают и переход между табами,
и перезапуск приложения. Переходы по панели идут с
`FLAG_ACTIVITY_REORDER_TO_FRONT`: уже открытый таб поднимается, а не создаётся
заново. Чтобы поднятый экран не показывал устаревшее, состояние считает правки
счётчиком `revision`, а `onResume` сравнивает его с тем, что экран отрисовал.

Экраны «вглубь» (операция, фильтр, пикеры, настройки, курсы) панель и шапку не
подключают и наследуются напрямую от `AppCompatActivity`. `AccountActivity` в
режиме пикера (`cAccountSelectedType`) вообще не ставит layout — `initTabScreen`
там не вызывается, и `BaseTabActivity` это учитывает.

Фильтр по счетам доходит только до ленты операций: `LoadOperations` принимает
`DataFilter`, а `GenerateReportGetBalanceToDate` его аргумент игнорирует, и
отчётные запросы дохода/расхода его не принимают вовсе. То есть при активном
фильтре список сужается, а цифры карточки остаются за весь период — так было и
до вынесения шапки в общий блок, но теперь это правится в одном месте.

## Схема базы

```mermaid
erDiagram
    icon {
        int _id PK
        text name
        blob image
        int icon_type
        int origin "v86, локальная"
    }
    currency {
        int _id PK
        text name
        text iso_code
        int icon_id FK
        text symbol
        int precision
        int show_in_equivalent "v85, синк"
        int is_shared "v85, локальная"
    }
    account {
        int _id PK
        text name
        text comment
        int icon_id FK
        int is_archived
    }
    account_operating {
        int _id PK "= account._id"
        int currency_id FK
    }
    account_asset {
        int _id PK "= account._id, v89"
        int asset_type "1 недвижимость, 2 авто, 3 прочее"
        int include_in_net_worth
        text comment
    }
    operation {
        int _id PK
        text date
        int acc_fr_id FK
        int acc_to_id FK
        real amount
        text comment
    }
    currency_pair {
        int _id PK
        text symbol
        int base_currency_id FK
        int quote_currency_id FK
        int precision
        int is_shared "v85, локальная"
    }
    currency_pair_rate {
        int _id PK
        int currency_pair_id FK
        text date
        real rate
    }
    currency_conversion {
        int _id PK
        int from_currency_id FK
        int to_currency_id FK
        int num
        int currency_pair_id FK
        int is_reverse
    }

    icon ||--o{ account : ""
    icon ||--o{ currency : ""
    account ||--o| account_operating : "расширяет"
    account_operating ||--o| account_asset : "актив"
    account ||--o{ operation : "acc_fr_id / acc_to_id"
    currency ||--o{ account_operating : ""
    currency ||--o{ currency_pair : "base / quote"
    currency_pair ||--o{ currency_pair_rate : ""
    currency_pair ||--o{ currency_conversion : ""
```

### Свои и общие валюты и пары (v85)

На сервере у `currency` и `currency_pair` есть общие строки (`ledger_id IS NULL`,
приходят в pull потоком `reference`) и свои (`ledger_id` леджера, поток `ledger`).
Сервер молча пропускает push чужой строки, поэтому телефон должен их различать:

- `is_shared` (`currency`, `currency_pair`) — только локальная колонка, в push не
  уходит. `SyncEngine.applyCurrency`/`applyCurrencyPair` пишут `1`, если в строке
  pull `ledger_id` пуст, иначе `0`. Строки, созданные на телефоне, — `0`. Общая пара
  не удаляется (`WalletDbCRUD.DeleteCurrencyPair` возвращает 0), форма общей валюты
  только для чтения (`CurrencyFormActivity`, `UpdateCurrency` с `is_shared = 0`).
- `show_in_equivalent` (`currency`, по умолчанию 1) — синкается как
  `currency.show_in_equivalent` (boolean). Push шлёт его всегда; pull без ключа
  (старый сервер) локальное значение не трогает. Читают его пикеры валюты
  эквивалента через `EquivalentCurrencies` (с флагом + текущая; если флаг снят у
  всех — весь список).

Миграция v85 только добавляет колонки (`ALTER TABLE … DEFAULT`), без `UPDATE`:
триггер `trg_<table>_dirty` отправил бы весь справочник в push. Вместо этого
`AppSettings.setReferenceSyncSeq(ctx, 0)` — следующий синк перекачивает поток
`reference`, и общие строки получают `is_shared = 1` (курсор `ledger` не трогается).
До этого синка все строки считаются своими: удаление общей пары локально возможно,
сервер такой tombstone пропустит, а повторный pull вернёт пару.

Удаление своей пары — мягкое, вместе с её живыми курсами, одной транзакцией
(тот же каскад, что `DELETE /api/currency-pairs/{uuid}` на сервере);
`currency_conversion` не трогается, эквивалент через пару становится неизвестным
(`v_currency_conversion` берёт только живые пары).

### Кошельки и категории — одна таблица

`account` хранит и кошельки, и статьи расходов. Кошелёк отличается наличием строки
в `account_operating` с тем же `_id` — там лежит валюта. Это наследование через
общий первичный ключ, а не отдельные таблицы.

Отсюда `AccountItem.isOperatingAccount()` и представления `v_account_operating`
(все счёта с валютой, у категорий она `NULL`) и `v_account_expanse`
(только те, у кого нет строки в `account_operating`).

Транзакция — всегда перевод между двумя `account`: расход это «кошелёк → категория»,
доход «категория → кошелёк», перевод «кошелёк → кошелёк». Операция
«категория → категория» бессмысленна и отсекается в трёх местах: валидацией в
`TransactionActivity`, пропуском при загрузке в `WalletDbLoader` и защитой от `null`
в `TransactionArrayAdapter`.

### Капитальные активы и «Переоценка» (v89)

Кошелёк может быть **капитальным активом** — квартира, машина, прочее (openspec
`capital-assets`). Признак — строка `account_asset` по образцу `account_operating`:
общий первичный ключ (`_id` = `account._id`, `uuid` = `account.uuid`), колонки
`asset_type` (1 — недвижимость, 2 — авто, 3 — прочее), `include_in_net_worth`
(входит ли актив во «Всего с капиталом»), `comment` и колонки синка. Снятие признака —
мягкое удаление строки, повторная пометка оживляет ту же строку
(`WalletDbCRUD.SetAccountAsset`/`ClearAccountAsset`); вторая строка с тем же uuid
не появляется. У категорий строки не бывает. Признак читают `LoadAccounts`
(`AccountOperatingItem.AssetInfo`, `null` — ликвидный кошелёк) и
`LoadBalancesPerWallet` (`WalletBalanceRow.assetType`/`includeInNetWorth`).

Деньги в актив идут обычным переводом. Изменение его стоимости — **переоценка**:
одна проводка между активом и третьей системной категорией «Переоценка»
(`account.system_kind = 3`, `AccountItem.SystemKind.REVALUATION`). Она заводится
лениво (`WalletDbCRUD.GetOrCreateRevaluationCategory`); если обычная категория с
именем «Переоценка» уже есть, системная получает имя «Переоценка активов» — чужую
категорию в системную не превращаем, её прошлые проводки ушли бы из расходов.
Исключение из дохода и расхода идёт по `system_kind`, поэтому две системные
«Переоценки» с разных устройств безвредны.

### Представления

| Представление | Что делает |
| --- | --- |
| `v_account_operating` | Счёта с валютой (`LEFT JOIN account_operating`) |
| `v_account_expanse` | Счёта без валюты, то есть категории |
| `v_currency_conversion` | Раскрывает каждую пару в **два** направления: прямое и обратное с флагом `is_reverse` |
| `v_currency_conversion_rate` | Курс на дату с уже применённым `1.0/rate` для обратного направления; через `UNION` добавляет тождественный курс 1 валюты к себе с датой `2000-01-01` |
| `v_operation` | Операция с именами счётов и валютой; `is_revaluation` — 1, если одна из сторон — счёт с `system_kind = 3` (v89) |
| `v_operation_eq` | Операция с курсом на свою дату и суммой в эквиваленте; пробрасывает `is_revaluation` |

`is_revaluation` — единственное место, где решается «это переоценка». Её используют
отчёты дохода и расхода (`GenerateIncomeReportByAccount`/`GenerateExpanseReportByAccount`)
и плитки-фильтры (`DataFilter.OperationKind.income/expense`, а для уже загруженной
операции — `DataFilter.matchesKind`): переоценка, в том числе заведённая вручную на
«Переоценку», не доход и не расход. Признак не фильтрует `deleted_at` счёта — так
же, как у wallet-api.

Два приёма стоит держать в голове.

Во-первых, `v_currency_conversion` избавляет запросы от необходимости знать, в какую
сторону задана пара: достаточно спросить курс `from → to`, обратный посчитается сам.

Во-вторых, `UNION` с тождественным курсом в `v_currency_conversion_rate` означает,
что пересчёт валюты в саму себя всегда находит курс 1 — отдельная ветка «если валюты
совпадают» в вызывающем коде не нужна.

### Курс на дату

Везде, где нужен курс, применяется один шаблон: сначала `max(date)` не позже нужной
даты, затем join обратно к представлению по этой дате.

```sql
LEFT JOIN (SELECT from_currency_id, to_currency_id, max(date) max_date
           FROM v_currency_conversion_rate WHERE date <= ?
           GROUP BY from_currency_id, to_currency_id) mx
       ON mx.from_currency_id = c._id AND mx.to_currency_id = ?
LEFT JOIN v_currency_conversion_rate vccr
       ON vccr.from_currency_id = mx.from_currency_id
      AND vccr.to_currency_id   = mx.to_currency_id
      AND vccr.date             = mx.max_date
```

## Расчёт балансов

Баланс кошелька — разница поступлений и списаний до даты:

```sql
round(coalesce(opt.amount_to, 0) - coalesce(opf.amount_fr, 0), c.precision)
```

где `opt` — сумма операций с `acc_to_id = кошелёк`, `opf` — с `acc_fr_id = кошелёк`,
обе с условием `date < ?`.

Единственная реализация живёт в `WalletDbLoader.LoadBalancesPerWallet` и логирует
свой SQL и параметры в logcat под тегом `WalletDbLoader`. Она обслуживает оба места:

- `BalanceReportActivity` — построчный список кошельков
- `GenerateReportGetBalanceToDate` — суммирует её результат для полей
  «баланс на начало / на конец» в `ReportActivity`

С v89 итог карточки периода (`GenerateReportGetBalanceToDate`) — **только ликвидные**
кошельки: строки с `assetType != null` пропускаются. Капитал считает
`GenerateCapitalToDate` из тех же строк: «Капитал» — все активы, «Всего с
капиталом» — ликвидные плюс активы с `include_in_net_worth`. Неполнота та же, что у
итога: строка без курса не пересчитывается 1:1, а помечает сумму `~`. Поэтому
покупка квартиры переводом уменьшает «На конец» на её цену без расхода, а «Всего с
капиталом» не меняется — строка «Капитал · Всего с капиталом» под балансами карточки
это и объясняет.

Раньше у отчёта за период был свой SQL (`getBalancesToDate`), и цифры расходились.
Теперь источник один. Константа `getBalancesToDate` всё ещё используется другими
отчётными методами того же класса.

### Три состояния эквивалента

`WalletBalanceRow` различает не два случая, а три, и от этого зависят и отображение,
и итоги:

| `hasEq` | `approx` | Значит | Показ |
| --- | --- | --- | --- |
| `true` | `false` | Кошелёк уже в валюте эквивалента | число без пометки |
| `true` | `true` | Пересчёт по курсу | `≈ ` + число |
| `false` | — | Курса к валюте эквивалента нет | `~` без числа |

Два знака различаются по смыслу и не взаимозаменяемы: `≈` значит «число посчитано,
но через курс», `~` — «числа нет». Поэтому в агрегатах `~` перекрывает `≈`: сумма,
из которой выпал кошелёк, занижена на неизвестную величину, и это более сильное
предупреждение, чем округление. Логика собрана в `BalanceReportActivity.totalMarker`
и в `AdapterUtils`:

```java
String aproxSign = isIncomplete ? "~" : isAprox ? "≈" : "";
```

`EquivalentResult` несёт оба признака: `approxEq` (был пересчёт) и `incomplete`
(что-то не удалось пересчитать и было исключено из суммы). Перегрузки
`AdapterUtils.setAmountText`, принимающие `EquivalentResult`, передают оба —
вызовы, передающие только `boolean isAprox`, трактуются как «пересчёт», то есть `≈`.

Ключевое место — **отсутствие `coalesce` на курсе** в SELECT:

```sql
round(vccr.rate * (coalesce(opt.amount_to, 0) - coalesce(opf.amount_fr, 0)), 2) balance_eq
```

Если поставить сюда `coalesce(vccr.rate, 1)`, то `balance_eq` перестанет быть `NULL`,
`hasEq` всегда окажется `true`, а кошельки без курса молча войдут в итоги по курсу 1:1
и завысят их. Так и было до правки.

`ORDER BY balance_eq DESC` при этом оставляет строки без эквивалента в конце списка:
в SQLite `NULL` меньше любого значения.

Итоги считаются по всем строкам с `hasEq`, а строки без него исключаются и помечают
сумму как приблизительную — исключение кошелька занижает итог, поэтому пометка нужна
в обоих случаях.

Тот же принцип в списке счётов: `AccountOperatingItem.getBalanceEq` возвращает
`Double` и `null` при отсутствии курса. Раньше он отдавал `-1` как признак
«посчитать нельзя», из-за чего кошелёк с отрицательным балансом (кредитка, овердрафт)
показывался как `~` вместо своего эквивалента.

## Точность

Точность — не константа, а поле:

- `currency.precision` — знаков в суммах этой валюты (миграция ставит 2)
- `currency_pair.precision` — знаков в курсе пары (миграция ставит 5)

Форматирование идёт через `AdapterUtils.getFormat(precision)` с кэшем
`DecimalFormat` по числу знаков. Класс — **`android.icu.text.DecimalFormat`**,
а не `java.text.DecimalFormat`; при работе с результатом нужен тот же импорт,
иначе компилятор не приведёт типы.

Тот же `precision` ограничивает ввод в полях суммы через `InputFilter`.

## Миграции

`DATABASE_VERSION = 89` в `WalletDbHelper` (v89 — таблица `account_asset` с триггером
dirty и индексом uuid, колонка `is_revaluation` во вьюхах и флаг однократного
перечита ledger, `upgradeToCapitalAssetsV89`, см. «Капитальные активы и
„Переоценка“» и «Синхронизация»; общие `createSyncTriggers`/`createUuidIndexes`
пропускают `account_asset`, пока её нет, — их зовут и старые шаги v72/v82;
v88 — `from_seed` у `account`, `currency`,
`currency_pair`, см. «Синхронизация» → «Встроенный набор и подключение к кошельку»;
v87 — таблица `sync_reload_seen`, см.
«Синхронизация» → «Удалённые строки»; v86 — `icon.origin`, см. «Иконки с
сервера и свои»; v85 — `currency.show_in_equivalent`, `currency.is_shared`,
`currency_pair.is_shared`, см. «Свои и общие валюты и пары»).

`onUpgrade` разветвляется по порогу 68. Для 68 и выше — инкрементальные `ALTER TABLE`
(69 добавила `currency.precision`, 70 — `currency_pair.precision`, 71 — таблицу
`operation_link`, 72 — четыре колонки синка на все 9 синхронизируемых таблиц плюс
первые настоящие триггеры схемы, см. [`schema/README.md`](../schema/README.md), раздел «v72 —
синхронизация с сервером», и `org/docs/TODO_mobile_sync.md`, Фаза 3). Для более
старых баз — полное пересоздание: `DROP` всех представлений и таблиц в порядке
зависимостей, затем `onCreate`. Данные при этом теряются.

Новая миграция — это новый блок `if (oldVersion < N)` **и** правка соответствующего
`CREATE TABLE`, иначе чистая установка и обновление разойдутся. С v72 это верно и
для представлений: раньше `CreateViews` вызывалась только из `onCreate`/полного
пересоздания, теперь шаг `oldVersion < 72` зовёт её и поверх уже существующей базы
(сама она безопасна для повторного вызова — начинает каждый блок с `DROP VIEW IF
EXISTS`).

`getWritableDatabase` переопределён и включает `PRAGMA foreign_keys = ON` — на
readable-соединении внешние ключи не проверяются.

### Один uuid — одна операция (v83)

С v83 у `operation` уникальный индекс `ux_operation_uuid` по
`lower(replace(uuid, '-', ''))` — тем же правилам, по которым uuid ищет pull
(`SyncEngine.localUuid`: с дефисами и без, любой регистр). На сервере
`operation` — `PRIMARY KEY (ledger_id, uuid)`: две локальные строки с одним uuid
после синка расходятся с ним (на телефоне две траты, на сервере одна). Повод —
импорт выписки, который пишет операции с заранее известным uuid (`row_id` строки,
change `add-ai-statement-import`).

Шаг `oldVersion < 83` сначала сводит дубли (`WalletDbHelper.dedupeOperationUuids`):
на uuid остаётся одна строка — живая, с меньшим `_id`; лишние помечаются
удалёнными **и получают новый uuid**. Со старым их tombstone при синке удалил бы
на сервере ту операцию, которую представляет оставшаяся строка.

Вставка с заданным uuid — `WalletDbCRUD.InsertOperationWithUuid`: живая с этим
uuid уже есть — `UUID_TAKEN`, вторая не создаётся; удалённая есть — та же строка
получает новые поля и `deleted_at = NULL`. Обычный `InsertOperation` по-прежнему
ставит случайный uuid.

## Иконки

Хранятся в базе как BLOB, не в ресурсах: пользователь добавляет свои из галереи.

Путь добавления: галерея → системный кроп 1:1 → `resizeBitmap` до 256×256 →
`compress(WEBP, 85)` → `INSERT`.

Две вещи здесь неочевидны.

`ICON_SIZE_PX = 256` выбран по фактическому потреблению: самая крупная отрисовка —
`Icon.getBitmap64()` (164 px) и ячейка сетки 48 dp (192 px на xxxhdpi). Прежние 512
были избыточны.

Результат кропа забирается **через временный файл** в `getCacheDir()`, а не через
`return-data`: битмап 512×512 ARGB весит около 1 МБ и не проходит лимит
Binder-транзакции (~500 КБ), из-за чего extras приходили пустыми. Файл отдаётся
кроп-приложению как `FileProvider`-Uri, поэтому в `file_provider_paths.xml` есть
`cache-path`. Второй путь там — `external-files-path` `Download/`: «Экспорт базы» на
всех версиях пишет в папку приложения `Android/data/<пакет>/files/Download`
(`ExportActivity.exportDir`, без разрешений; общая `Download/` на Android 8–9 падала
с `Permission denied` — android-wallet#38). Если кроп-приложения нет, пользователь отменил кроп или файл нечитаем —
срабатывает кадрирование по центру.

`icon.icon_type` группирует иконки на экране: `0` — категория, `1` — флаг.
Порядок значений в `Icon.IconType` подогнан под уже лежащие в базе данные.
`IconType.fromValue` возвращает `CATEGORY` для всего, кроме `1`.

### Иконки с сервера и свои (v86)

Общий набор иконок ведёт администратор на сайте (wallet-web → `POST
/api/admin/icons` в wallet-api), телефон иконки **только скачивает**: в `POST
/sync/push` нет таблицы `icon`, `POST`/`HEAD /sync/blobs` не вызываются. Скачивание
не изменилось — строка `icon` из `GET /sync/pull`, байты новой иконки —
`GET /sync/blobs/{sha256}` до транзакции страницы.

`icon.origin` — локальная колонка: `0` — с сервера (пришла через pull или ушла на
сервер до v86), `1` — своя (галерея, `seed.json`). Миграция v86 делает своими иконки,
которые ещё ждали отправки (`dirty = 1`), и обнуляет `dirty` у всех иконок —
условием `WHERE dirty <> 0`, иначе `trg_icon_dirty` пометил бы чистые строки.

Сервер требует, чтобы `account.icon_uuid`/`currency.icon_uuid` указывали на
существующую иконку. Поэтому у счёта или валюты со своей иконкой в push уходит
**заместитель** (`sync/IconSubstitutes`, `SyncEngine.PushIconRefs`): живая иконка с
сервера с той же картинкой (SHA-256 байт = её `content_sha256`), иначе с тем же
именем без учёта регистра, иначе `unknown`. Индекс строится один раз за раунд push.
Локально счёт по-прежнему ссылается на свою иконку. Заместителя нет (иконок с
сервера ещё нет — первый синк чистой установки) — строка и всё, что на неё
ссылается, откладываются (`deferDependents`), в журнале «нет иконки-заместителя
для «<имя>»: строка отложена», исход прохода — «частично»; после pull иконок
следующий проход их отправит.

На pull:

- строка `icon` всегда пишется с `origin = 0`;
- имя новой иконки занято своей — своя переименовывается в «<имя> (своя)»
  («(своя 2)», …); если у своей та же картинка, она и есть эта иконка: получает
  uuid и метаданные серверной и становится иконкой с сервера, байты не качаются
  (так иконки `seed.json` чистой установки не дублируются);
- у счёта или валюты со своей иконкой пришедший `icon_uuid` её заместителя
  `icon_id` не меняет (`SyncEngine.PullIconGuard`, индекс один на страницу) —
  иначе каждый синк затирал бы своё фото эхом собственного push. Другой
  `icon_uuid` (иконку сменили на сайте или на другом устройстве) применяется.

`IconEditDialog` открывает иконку с сервера только для просмотра: картинка, имя и
«Иконка с сервера — изменить или удалить её может администратор на сайте.», без
полей правки, выбора картинки, «Удалить» и «Сохранить». Признак — `Icon.isFromServer`
(`WalletDbLoader.LoadIcons` читает `origin`). Свои иконки правятся и удаляются как
раньше (удаление — жёсткий `DELETE`, используемую не даёт удалить FK).

## Загрузка курсов

`CurrencyPairRateListActivity` выбирает источник по составу пары:

- есть `RUB` → ЦБ РФ, `XML_dynamic.asp`, XML в **Windows-1251**, код валюты
  берётся из таблицы `CBR_CODES` внутри активности
- иначе → Frankfurter, JSON

Шаг выборки задаётся в диалоге: каждый день, понедельник каждой недели или
1-е число месяца. Записи вставляются пачкой через `insert or replace`,
так что повторная загрузка того же периода безопасна.

Развязка по двум источникам планируется к замене на один универсальный провайдер,
так что таблица `CBR_CODES` и разбор Windows-1251 — временная конструкция.

## Синхронизация

Пакет `sync/`: `SyncEngine` (push dirty-строк и pull чужих изменений,
протокол — README wallet-api), транспорт `SyncApiClient`, токены `AuthSession`.
Проход целиком (refresh → push → pull → чистка удалённых строк) — `SyncRunner`.
Иконки в push не входят — только скачиваются (см. «Иконки с сервера и свои»).

Проход идёт **один на процесс**. Его зовут три места: `SyncWorker` по
расписанию, кнопка «Синхронизировать сейчас» (`IntegrationsActivity`) и экран
«ИИ» перед разбором траты. `SyncRunner` держит текущий проход как `FutureTask`
на своём потоке; если он уже идёт, новый вызов не запускает второй, а ждёт исход
идущего. Раньше кнопка и воркер могли идти параллельно: одни и те же строки
уезжали двумя порциями, два pull двигали курсоры в `AppSettings`.

- `runOnce(ctx)` — ждёт исход до конца (воркер, кнопка).
- `runOnceShared(ctx, timeoutMs)` — ждёт не дольше таймаута и тогда отвечает
  `Status.STILL_RUNNING`; проход не прерывается и доходит до конца в фоне.

Время последнего прохода с исходом `OK`/`PARTIAL` — `AppSettings.getLastSyncSuccessAt`.

### Встроенный набор и подключение к кошельку (v88)

Пустая база заполняется из `assets/seed.json` (`WalletFillData.InsertFirstDictionary`,
openspec `new-user-default-set`): общие категории с именами `{ru, en}` (язык — по
языку телефона при посеве, `CurrencyNames.seedLanguage`), системные `Transfer` /
`Transfer commission`, иконки, валюты с флагами (название — от Android по коду ISO),
пары. Кошельков и курсов нет. Строки набора помечены `from_seed = 1` у `account`,
`currency`, `currency_pair`; правка имени, иконки, кода или символа снимает метку
(`WalletDbCRUD`). Метка локальная, в push не входит. Апгрейд v88 добавляет колонку с 0 —
старые базы ничего «из набора» не имеют.

Общая валюта показывается названием Android на текущем языке (`Currency.getName`),
своя — сохранённым.

**Подключение** — первый проход после входа на базе без строк, бывших на сервере
(чистая установка, переустановка, полная очистка). Признак
`AppSettings.isLedgerConnectionPending` ставит `SyncRunner.startConnectionIfFresh`, снимает
проход с прошедшей отправкой или «Удалить с телефона». Порядок прохода другой:

```
 refresh -> pull (adopt by key) -> connection step -> push -> purge
                                          |
                                          +-- question -> Status.AWAITING_DECISION, no push
```

- Загрузка подключения (`SyncEngine.adoptLocalByKey`): пришедшая валюта (код), пара
  (символ) или счёт (имя по правилу `AccountNames`), совпавшая с неотправленной локальной
  строкой, заменяет её — локальная строка берёт uuid и поля серверной
  (`LedgerConnection.adoptByKey`). Связи по `_id`, поэтому операции и кошельки
  переклеивать не нужно.
- Шаг после загрузки (`LedgerConnection.afterPull`): если на телефоне есть счёт с
  сервера, нетронутые строки набора без ссылок удаляются (системные — нет); если
  остались неотправленные операции и «Перенести» не нажато — вопрос.
- Вопрос «Операции без входа» показывает главный экран (`LedgerConnectionDialog`):
  «Перенести» — проход с отправкой; «Удалить с телефона» — `LedgerConnection.discardOffline`.

### Капитальные активы в синке (v89)

`account_asset` — в `TABLE_ORDER` после `account_operating` (и в `PURGE_ORDER` перед
ней). Push отправляет `uuid, asset_type, include_in_net_worth, comment, deleted` —
ключи по `TableSpec` wallet-api, сервер читает каждый без дефолта. Pull
(`applyAccountAsset`) — копия `applyAccountOperating`: счёт ищется по тому же uuid,
нет счёта — строка откладывается (`PullDeferredRows`). При 409 `duplicate_name`
`resolveAccountConflicts` переклеивает uuid и у `account_asset`, иначе признак ушёл
бы со старым uuid несуществующего на сервере счёта.

Прежняя версия приложения молча пропускала строки `account_asset` в pull, а курсор
ledger уходил дальше. Поэтому шаг БД v89 поднимает флаг
`AppSettings.isLedgerRereadPending`, и `pullAll` один раз читает поток **ledger** с
нуля: reference-курсор и `ledger_id` не трогаются, загрузкой с вычисткой это не
считается (upsert по uuid идемпотентен, удалять нечего). Флаг снимается после первой
применённой страницы (или если поток пуст) — обрыв дальше продолжает с сохранённого
курсора. Свежая установка флага не получает: курсор и так 0.

### Удалённые строки

Удаление в UI мягкое (`deleted_at`), строка нужна только до отправки на сервер
(openspec `purge-synced-tombstones`; серверная пара — change
`purge-synced-tombstones` в wallet-api: подтверждения устройств, чистка и
`reset_reason` на сервере).

- **Чистка** — шаг прохода после успешного `pullAll`
  (`SyncEngine.purgeTombstones`): строки с `deleted_at` и `dirty = 0`
  удаляются физически, от листьев к корням (`PURGE_ORDER`), построчно. Строка,
  на которую ссылаются, остаётся: `NO ACTION`-ссылки ловит
  `SQLiteConstraintException`, а две каскадные (`operation_link → operation`,
  `recurring_operation → account`) проверяются явно — иначе удаление унесло бы
  их молча. Ошибка загрузки — чистки нет; ошибка чистки исход не меняет.
- **Удаление неизвестной строки** из pull не создаёт строку (`applyRow`) и не
  пишет «Удалено» в журнал; содержимое удалённой иконки не скачивается.
- **Загрузка с нуля с вычисткой.** Флаг `AppSettings.isFullReloadPending`
  ставится вместе с обнулением обоих курсоров: сброс от сервера (любая
  причина — `ahead` или `purged`; страница выбрасывается), смена кошелька,
  одноразовая перекачка, курсор 0 при уже синкнутых строках. Пока флаг поднят,
  каждая страница пишет uuid своих строк (нижний регистр, без дефисов) в
  `sync_reload_seen` в своей транзакции; курс засчитывает и локальную строку
  той же пары и даты. Когда оба потока догнаны — синкнутые (`dirty = 0`)
  строки без отметки удаляются тем же построчным удалением, таблица
  очищается, флаг снимается. Прерванная загрузка продолжается с сохранённого
  курсора. Свои иконки (`origin = 1`, у них `dirty = 0` всегда) не
  вычищаются.
- `GET /sync/pull` передаёт `ledger_id` — леджер, к которому относится курсор
  (`AppSettings.getSyncLedgerId`), если он известен: сервер засчитывает курсор
  как подтверждение только для своего леджера.

`AuthSession.ensureFresh` — `synchronized`: refresh-токен одноразовый, и два
параллельных refresh (проход синка и запрос экрана «ИИ») отдали бы один и тот же
токен — второй получил бы 401 и ложное «Нужна повторная авторизация». Запросы вне
синка зовут `ensureFreshForRequest`: refresh только если access-токен истекает
меньше чем через минуту.

## Капитальные активы на экранах

- **Карточка периода** (`view_period_summary.xml`, `BaseTabActivity.refreshCapitalRow`):
  под балансами строка «Капитал · Всего с капиталом» на конец периода — только
  если на телефоне есть хоть один актив (`WalletDbCRUD.HasAssets`). «На начало», «На
  конец» и «Итог» — ликвидные.
- **Таб «Кошельки»** (`AccountActivity`): при наличии активов — секции «Ликвидные» и
  «Капитал» (`wallet_section_header.xml`) со своими суммами на конец периода;
  сортировка «самый толстый первым» — внутри секции, опустевшая после фильтра секция
  не показывается. Колонка изменения за период у актива включает переоценку — это и
  есть изменение его стоимости.
- **Выбор счёта** (`AccountPickerActivity`): при вводе операции активы стоят после
  всех ликвидных кошельков при любой сортировке, «Переоценка» скрыта; в справочнике
  «Кошельки и категории» всё как раньше, у актива — подпись типа.
- **«Новый кошелёк» и карточка кошелька** (`view_asset_fields.xml`, `AssetFields`):
  «Капитальный актив», «Тип актива», в карточке ещё «Учитывать во „Всего с
  капиталом“». Запись — в одной транзакции с остальным сохранением.
- **Переоценка** (`RevaluationDialog`, кнопка «Переоценить» у актива): дата и новая
  оценка, строка «Сейчас: … · Разница: …». `WalletDbCRUD.WriteRevaluation` пишет одну
  проводку на |Δ| (Δ = оценка − баланс по проводкам до конца дня,
  `WalletDbLoader.LoadAccountBalanceAt`) на конец выбранного дня с комментарием
  «Оценка: …», Δ = 0 — «Оценка не изменилась». После — `markDataChanged()` и синк в фоне.
- **Лента** (`TransactionArrayAdapter`, `TYPE_REVALUATION`,
  `transaction_item_revaluation.xml`): актив, подпись «Переоценка», «+»/«−» цветом
  перевода. В итог дня не входит (`OperationItem.getMovementAmountEq` даёт 0).

### Перенос операций категории в актив

Если раньше квартиру вели категорией расходов, её операции можно перенести в актив:
действие «Перенести операции в актив» в карточке обычной категории (есть, только если
есть активы) → выбор актива → подтверждение с числом операций →
`WalletDbCRUD.MoveCategoryToAsset`, одной транзакцией:

- кошелёк в валюте актива — сторона-категория заменяется активом: расход становится
  переводом «кошелёк → актив», возврат — «актив → кошелёк»;
- другая валюта — валютный перевод в две ноги через «Transfer» по курсу на дату
  операции, как `TransactionActivity.saveTransfer` (связка `LEG`); нет курса — операция
  пропускается и считается, пользователь видит «Не перенесено операций: N — нет курса
  на дату»; уже связанная разновалютная операция тоже пропускается;
- правила повторяющихся операций с кошельком в валюте актива перенацеливаются на актив,
  разновалютные остаются;
- категория без оставшихся операций уходит в архив.

Делает это **клиент**, а не миграция на сервере: офлайн-устройство с неотправленными
операциями на старую категорию после серверной миграции привезло бы их обратно.
Изменения уходят ближайшим синком как обычные правки операций.

## Экран «ИИ»

Трата своими словами через wallet-ai (`/api/ai/*`, контракт — `wallet-ai/openapi.yaml`).
Поведение — spec `openspec/specs/ai-expense-entry` (change `add-ai-client`,
правка в окне операции — `ai-expense-in-operation-window`).

```mermaid
flowchart LR
    T["Текст<br/>поле · голос · «Поделиться»"] --> S["SyncRunner.runOnceShared<br/>до 10 с"]
    S --> J["AiApiClient<br/>createJob → getJob?wait=25"]
    J --> R["OperationPrefill<br/>DraftReconciler: uuid → локальный account"]
    R -->|все счета на телефоне| W["TransactionActivity<br/>«Новая операция» + подсказка"]
    R -->|счёт пропал| P["Блок ошибки<br/>синк и повтор / открыть операцию"]
    P --> W
    W --> D["«Записано: …»<br/>+ фоновый синк"]
```

| Класс | Ответственность |
| --- | --- |
| `Activities/AiActivity` | Экран: ввод, ожидание, блок ошибки «пропал», результат окна операции. Приём `ACTION_SEND` `text/plain` |
| `sync/AiApiClient` | Транспорт: status, jobs; свой `readTimeout` дольше long-poll |
| `ai/ParseExpenseResult` | Разбор `ParseExpenseResult` из ответа задания |
| `ai/DraftReconciler` | Сверка uuid черновика с телефоном: есть / нет / удалён / в архиве |
| `ai/OperationPrefill` | Поля окна операции из черновика: живые счета, кандидаты для пикеров, подсказка |
| `ai/AiTexts` | Тексты ошибок и деталей — дословно из спеки, под юнит-тестом |

Почему синк перед разбором: resolver wallet-ai выбирает кошелёк и категорию по
справочнику **сервера** (wallet-report), а записывать можно только на строку,
которая есть в локальной базе. Push отдаёт серверу то, что создано на телефоне,
pull приносит созданное в вебе и боте. Если uuid из черновика локально всё равно
не нашёлся, окно не открывается сразу: экран «ИИ» показывает блок ошибки.

Правка и запись — в обычном окне операции, отдельной карточки нет. Окно
принимает от экрана «ИИ»:

| Extra `TransactionActivity` | Что |
| --- | --- |
| `cHint` | строка «Подсказка ИИ: …» над полями (исходный текст, вопросы resolver, `notes`) |
| `cPriorityWalletIds`, `cPriorityCategoryIds` | `int[]` `_id` кандидатов resolver; окно передаёт их пикеру как `AccountPickerActivity.cPriorityIds` — первыми при любой сортировке |
| `cOpUuid` | uuid новой операции (экран «Выписка»: `row_id` строки). Живая операция с ним есть — окно открывается как «Изменение операции» этой операции; удалённая — «Сохранить» восстанавливает ту же строку. У перевода uuid получает главная проводка, вторая нога и комиссия — свои |
| результат `cSavedOperationId` | `_id` главной проводки после «Сохранить»; у удаления его нет |

По `cSavedOperationId` экран «ИИ» строит «Записано: …» из сохранённой операции
(пользователь мог сменить сумму, счета, вид). Закрыли окно без сохранения —
черновик остаётся («Открыть снова» без нового запроса к ИИ), он же переживает
пересоздание экрана через `onSaveInstanceState`.

`default_wallet_uuid` — `AppSettings.getAiLastWalletUuid`, серверное написание
uuid (с дефисами): локально встречаются uuid без дефисов (`LocalUuid.find`).

Сеть — на голом `new Thread`; поздний ответ отменённого разбора отсекает счётчик
`generation`. В logcat — только id задания, статусы и длительности.

## Экран «Выписка»

Импорт банковской выписки через wallet-ai (`/api/ai/imports*`, контракт —
`wallet-ai/openapi.yaml`). Поведение — change `add-ai-statement-import`, спеки
`ai-statement-import` и `operation-identity`. Задача ИИ — разобрать выписку и
предложить категории; дубли ищет телефон по своим операциям, серверные группы
дублей (`exists`, `imported`, `possible_duplicate`) не используются.

```mermaid
flowchart LR
    F["Файл<br/>выбор · «Поделиться»"] --> S["SyncRunner.runOnceShared"]
    S --> C["createImport<br/>base64 в JSON"]
    C --> P["getImport каждые 3 с<br/>только пока экран открыт"]
    P --> L["Список строк<br/>StatementRowState + StatementDuplicates"]
    L -->|тап| W["TransactionActivity<br/>cOpUuid = row_id"]
    L -->|«Записать готовые»| B["InsertOperationWithUuid<br/>одной транзакцией"]
    W --> M["PATCH категории · /committed<br/>+ фоновый синк"]
    B --> M
```

| Класс | Ответственность |
| --- | --- |
| `Activities/StatementImportActivity` | Экран: файл и кошелёк, загрузка, ожидание, список, запись по одной и пачкой. Приём `ACTION_SEND` с `EXTRA_STREAM` (PDF, CSV, текст); текст без файла уходит на экран «ИИ» |
| `sync/AiApiClient` | `createImport`, `listImports`, `getImport`, `patchImportRowCategory`, `commitImportRows`; `HttpError.importId` у 429 `import_in_progress` |
| `ai/StatementImport` | Импорт и строки из ответа; незнакомые статусы, группы и поля не ломают разбор |
| `ai/StatementDuplicates` | Дубль по полям: тот же кошелёк, та же сумма, дата ±1 день, не из этого импорта; один к одному, ближайший по дате |
| `ai/StatementRowState` | Состояние строки по порядку: «Добавлена», «Не для записи», «Проверить», «Похоже, уже есть», «Нужна категория», «Ждёт»; отбор «готовых» |
| `ai/AiTexts` | Тексты экрана — дословно из спеки, под юнит-тестом |

**Строка и операция связаны через `row_id`.** Операция из строки сохраняется с
`uuid = row_id`. Отдельного флага «добавлено» нет: строка добавлена, если на
телефоне есть живая операция с этим uuid (`WalletDbCRUD.FindOperationByUuid`).
Удалили операцию — строка снова «Ждёт». `row_id` не зависит от промпта, суммы и
даты, а `op_uuid` сервера — зависит, поэтому клиент на него не опирается.
Повторную загрузку той же выписки (новые `row_id`) ловит проверка дублей по
полям.

**Строки на телефоне не хранятся.** Список каждый раз берётся с сервера
(`GET /api/ai/imports/{id}`), перед первым показом за открытие экрана — синк,
чтобы проверка дублей видела операции из веба и бота. Незавершённый (или
готовый, но не записанный) импорт после перезапуска находится через
`GET /api/ai/imports`. Без сети список не открывается; уже открытый остаётся
рабочим — запись идёт в базу телефона.

**Отметка на сервере.** После записи строки: категория, если она отличается от
серверной, — `PATCH rows` (только `category_uuid`: сервер выучит правило
продавца и проставит категорию похожим строкам), затем `POST /committed` и
фоновый `SyncRunner.runOnce`. Сумма и дата на сервер не уходят. Не прошла
отметка (нет сети) — строка «Добавлена» с `committed: false` дошлётся в
`/committed` при следующем открытии списка.

Записываются только расходы «кошелёк выписки → категория», дата — день строки
в 12:00, комментарий — описание (продавец). Доходы, переводы и незнакомые группы
сервера — «не для записи».

## Известные шероховатости

Это не задачи, а места, где поведение расходится с ожидаемым по имени.

- `AccountList.getIncomeAccounts()` возвращает **все** счёта без фильтрации. В схеме
  нет разделения на доходные и расходные категории — только операционные и нет.
  Вкладка «доходы» показывает всё.
- `Singleton` держит `SQLiteDatabase` на весь процесс, при этом часть экранов
  открывает свои соединения через `new WalletDbHelper(...)` и закрывает их в `finally`.
  Две модели работы с соединением сосуществуют.
- `MainActivity.transactions` — `public static OperationList`. Список операций живёт
  вне `Singleton` и вне жизненного цикла активности.
- Проект на Support Library 28. Пакеты `android.support.*`, миграция на AndroidX
  не выполнялась; `android.arch.lifecycle` тоже доandroidx-овский.
- `package` объявлен в `AndroidManifest.xml`, а не через `namespace` в Gradle —
  AGP предупреждает о deprecation.
- Строки интерфейса частью в `strings.xml`, частью захардкожены в активностях.
