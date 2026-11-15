# Proposal

## Why

В окне операции «Статья расхода» показывает все категории подряд: «Зарплата»
предлагается в расходах, «Продукты» — в доходах. Фильтровать список было не по
чему: у категории не было признака вида. Сервер уже хранит его в
`account.category_kind` (wallet-api, миграция `000041`, change
`category-kind-income-expense`), и **требует это поле в каждой строке `account`
при `POST /sync/push`** — без него приложение получает `400 invalid_row` и не
может отправить ни создание, ни правку счёта. Клиент должен научиться хранить,
синхронизировать и использовать вид категории.

## What Changes

- Локальная БД (v92): колонка `account.category_kind INT NOT NULL DEFAULT 2`
  (`0` расход, `1` доход, `2` оба). Локальной разметки по операциям нет: вид
  существующих категорий приходит с сервера через `/sync/pull` (его миграция
  уже разметила леджеры одним `seq`).
- Синхронизация: `category_kind` уходит в каждой строке `account` при push;
  при pull применяется, а при отсутствии поля в строке берётся `2`.
- Выбор статьи при вводе операции: «Статья расхода» показывает категории вида
  «расход» и «оба», «Статья дохода» — «доход» и «оба». Уже выбранная в
  редактируемой операции категория остаётся в списке, даже если вид не подходит.
  То же правило у повторяющихся операций.
- Карточка категории и «Новая категория»: выбор вида «Расход» / «Доход» /
  «Оба». У новой категории по умолчанию «Расход». У кошельков и системных
  категорий выбора нет, вид всегда «Оба».
- Стартовый набор (`seed.json`): 23 расходные категории получают вид «Расход»,
  4 доходные («Зарплата», «Подработка», «Проценты», «Прочие доходы») — «Доход».
- JSON-экспорт и импорт: поле `category_kind` у счетов; нет поля — «Оба».
- Документы: `docs/ARCHITECTURE.md`, зеркало схемы `schema/table/account.sql`.
- **BREAKING (для порядка выкладки)**: сервер с `category_kind` отвечает `400`
  приложению без этой правки. Релиз клиента и prod-тег `v0.4.6` wallet-api
  идут вместе.

## Capabilities

### New Capabilities
- `category-kind`: вид категории на клиенте — хранение и значения, правило для
  кошельков и системных категорий, синхронизация, стартовый набор, JSON-обмен.

### Modified Capabilities
- `operation-entry`: выбор «Статья расхода» / «Статья дохода» фильтрует
  категории по виду.
- `account-screens`: «Новая категория» и карточка категории получают выбор вида.

## Impact

- `android-wallet`: `Data/WalletDbHelper.java` (v92), `Data/WalletContract.java`,
  `Data/WalletDbLoader.java`, `Data/WalletDbCRUD.java`, `DataItems/Accounts/
  AccountItem.java`, `sync/SyncEngine.java` (push и `applyAccount`),
  `Activities/AccountPickerActivity.java`, `TransactionActivity.java`,
  `RecurringOperationActivity.java`, `AddCategoryActivity.java`,
  `AccountItemActivity.java`, `Data/WalletExportData.java`,
  `Data/WalletImportData.java`, `Data/WalletFillData.java`/`seed.json`, ресурсы
  строк (ru, en), `schema/table/account.sql`, `docs/ARCHITECTURE.md`, тесты.
- `wallet-api`: правка не нужна, контракт — `openapi.yaml`
  (`AccountPushRow`/`AccountPullRow`) и спека `category-kind` в `openspec/specs`
  репозитория wallet-api. На test выкачен `0.4.6-rc.1`, на prod `0.4.5`.
- `wallet-report`, `wallet-web`, `wallet-telegram`: не затрагиваются; фильтр по
  виду в отчётах и в резолвере бота — отдельно позже.
