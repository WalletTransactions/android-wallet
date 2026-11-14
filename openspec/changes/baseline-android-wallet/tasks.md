# Tasks

Изменение не меняет код: задачи — только сверка спецификаций с кодом и
тестами. Найденное несоответствие исправляется в спецификации, если неточна
она, или записывается в «Наблюдения» `design.md`, если расходится документ или
код ведёт себя неожиданно. Код не правится, новые тесты не пишутся: пробелы
покрытия фиксируются для отдельного change.

## 1. Исходное состояние

- [ ] 1.1 Убедиться, что сверка идёт на коммите из `design.md` (`316bfc7`) или зафиксировать новый коммит в Context; проверка — `git log -1` совпадает с записанным
- [ ] 1.2 Прогнать юнит-тесты (`bash ci/run_unit_tests.sh` или `bash gradlew :app:testProdDebugUnitTest`); проверка — все тесты зелёные, число тестов записано в раздел «Покрытие» `design.md`

## 2. Сверка по capability

Для каждой спецификации: каждый сценарий сопоставить с тестом `app/src/test`
или местом в коде, тексты интерфейса — с `res/values-ru/` дословно; сценарии
без теста — в «Покрытие» `design.md`.

- [ ] 2.1 `operation-entry` — `TransactionActivity`, `AccountPickerActivity`, `PickerSort`, `CalendarPicker`, `WalletDbCRUD` (операции, `operation_link`), `TransactionSaveButtonTest`
- [ ] 2.2 `operations-feed` — `MainActivity`, `TransactionArrayAdapter`, `FilterActivity`, `MultiPickerActivity`, `DataFilter`, `WalletDbLoader::LoadOperations/AttachLinkedOperations/CountOperations`
- [ ] 2.3 `period-summary` — `BaseTabActivity`, `PeriodFilterState`, `BottomNav`, `ChooseDatePeriodFragment`, `ChooseDateFragment`
- [ ] 2.4 `balances-and-equivalent` — `WalletDbLoader::LoadBalancesPerWallet/GenerateReportGetBalanceToDate`, вьюхи `v_currency_conversion*`, `EquivalentResult`, `AdapterUtils`, `CurrencyReloadInPlaceTest`
- [ ] 2.5 `reports` — `ReportActivity`, `ReportByAccountsAdapter`, `ReportByAccount`, отчётные запросы `WalletDbLoader`
- [ ] 2.6 `wallets-tab` и `account-screens` (ADDED) — `AccountActivity`, `AccountItemActivity`, `AddWalletActivity`, `AddCategoryActivity`, `AccountNames`, `AccountNamesTest`
- [ ] 2.7 `recurring-operations` и `reminders` — `RecurringOperation*`, `RecurringOperationsDb`, `RecurringWorker`, `ReminderWorker`, соответствующие тесты
- [ ] 2.8 `currency-rates` и `currency-pairs` (ADDED) — `CurrencyPairRateListActivity`, `RateEditDialog`, `RateChartView`, `CurrencyPairListActivity`, `CurrencyPairListAdapter`
- [ ] 2.9 `icon-library` — `IconManagerActivity`, `IconEditDialog`, `IconCropActivity`, `CircleCropView`, `IconPickerActivity`
- [ ] 2.10 `data-import`, `data-export` (ADDED), `data-reset` — `ImportActivity`, `WalletImportData`, `ExportActivity`, `WalletExportData`, `ResetDataActivity`, `HardWipe*Test`, `ImportNewCurrencyTest`
- [ ] 2.11 `app-lock` — `LockActivity`, `security/*`, раздел безопасности `SettingsActivity`, `AppLockTest`
- [ ] 2.12 `server-account`, `telegram-link`, `sync-notifications` — `IntegrationsActivity`, `SyncLinkActivity`, `AuthSession`, `TokenStore`, `HttpClients`, `SyncScheduler`, `SyncWorker`, `SyncLog`, `TelegramLinkClient`, `NewOperationsNotifier`
- [ ] 2.13 `sync-data-exchange` — `SyncEngine`, `PullDeferredRows`, `LocalUuid`, `SyncEngine*Test`; сверить с README wallet-api, что описан только клиент
- [ ] 2.14 `more-and-settings` — `MoreActivity`, `SettingsActivity`, `AppSettings`

## 3. Итоговая проверка

- [ ] 3.1 Убедиться, что база не пересекается с открытыми changes: имена ADDED-требований не совпадают с требованиями `android-flavors`, `archive-wallet-writeoff`, `new-user-default-set` и существующих `account-screens`, `currency-pairs`, `data-export`; проверка — `openspec show baseline-android-wallet --json --deltas-only` без дублей
- [ ] 3.2 Сократить требования длиннее 500 символов (70 шт.): примеры и крайние случаи — в сценарии, составные — разбить; проверка — `openspec validate baseline-android-wallet --strict` без предупреждений
- [ ] 3.3 Перечитать «Наблюдения» `design.md` после сверки: новые добавлены, ошибочные убраны; проверка — каждое Р-наблюдение ссылается на конкретный файл

## Workflow follow-up

- Заархивировать change (`/opsx:archive`) после ревью: спецификации переедут в `openspec/specs/`.
- Ответить на Open Questions `design.md` (выход из аккаунта, синк правил повторов).
- Решить, какие из Р1–Р70 оформлять отдельными changes; первыми — потеря данных (Р1, Р2, Р6, Р7).
- Поправить `docs/ARCHITECTURE.md` и README по Р61–Р65 (отдельный change документации).
