# Tasks

## 1. Подготовка

- [x] 1.1 `ci/check_hardcoded_text.sh` с файлом исключений (все нынешние файлы с зашитым текстом), вызов из `ci/run_unit_tests.sh`; lint `HardcodedText` — ошибка с тем же списком подавлений (design D5). Проверка: скрипт зелёный на `main`; новый литерал «Тест» в любой Activity вне исключений даёт ошибку — выполнено 2026-11-07: скрипт + `ci/hardcoded_text_allowlist.txt` (110 файлов, 988 строк), шаг в `.github/workflows/ci.yml` и `ci/run_unit_tests.sh`; литерал «Тест» в новом файле — выход 1. Lint не подключён: в CI lint не запускается, атрибуты layout/menu проверяет тот же скрипт (design D5 уточнён)
- [x] 1.2 Убрать мусор из `strings.xml` (`MainActivity2`, `Android Studio`, неиспользуемые `category_*`, `array_category`). Проверка: сборка и `bash gradlew testProdDebugUnitTest` зелёные — выполнено 2026-11-07: убраны 29 неиспользуемых строк, тесты зелёные
- [x] 1.3 Показываемое имя системных категорий по `system_kind` (design D4), строки «Перевод», «Комиссия за перевод». Проверка: юнит-тесты на сценарии «Русский интерфейс» и «Синк не затронут» — выполнено 2026-11-07: `AccountItem.getDisplayName` в 25 местах показа (`getName` — для правки, поиска по имени, синка); `SystemCategoryDisplayNameTest`

## 2. Вынос строк (values/ = русский, поведение не меняется)

Каждая задача — вынос строк экрана в ресурсы, склейки — в форматные
строки, окончания — в plurals, файлы убираются из исключений проверки.
Проверка каждой: `ci/check_hardcoded_text.sh` и юнит-тесты зелёные, экран
на эмуляторе выглядит как до выноса.

- [ ] 2.1 Главный экран, вкладки, `BaseTabActivity`, `MoreActivity`, `FilterActivity`
- [ ] 2.2 Операция: `TransactionActivity`, `MultiPicker*`, ввод ИИ в окне операции
- [ ] 2.3 Счета и категории: `AccountItemActivity`, `AddWalletActivity`, `IconEditDialog`, `IconCropActivity`
- [ ] 2.4 Валюты и курсы: `CurrencyPairListActivity`, `CurrencyPairRateListActivity`, `CurrencyFormActivity`, `RateEditDialog`
- [ ] 2.5 Повторяющиеся операции и напоминания: `RecurringOperationActivity`, `RecurringOperationListActivity`, `RecurringOperation`, `reminder/ReminderWorker`
- [ ] 2.6 Настройки и безопасность: `SettingsActivity`, `LockActivity`, `security/BiometricErrors`, `ResetDataActivity`
- [ ] 2.7 Импорт и экспорт: `ImportActivity`, `ExportActivity`, `StatementImportActivity`, `Data/WalletImportData`, `Data/WalletExportData`
- [ ] 2.8 Интеграции и синк: `IntegrationsActivity`, `SyncLinkActivity`, `sync/SyncEngine` (журнал), `SyncLog`, `SyncRunner`, `NewOperationsNotifier`, `AuthSession`, `TelegramLinkClient`
- [ ] 2.9 ИИ: `AiActivity`, `ai/AiTexts` — только тексты интерфейса, запросы к wallet-ai помечены `// not UI` (design D2)
- [ ] 2.10 Остаток: файл исключений проверки пуст. Проверка: `ci/check_hardcoded_text.sh` без исключений зелёный

## 3. Английский

- [ ] 3.1 `values-en/strings.xml` — перевод всех строк и plurals; lint `MissingTranslation` — ошибка. Проверка: lint зелёный; Robolectric-тест с `qualifiers = "en"` — сценарии «Числа с окончаниями / Английский», «Журнал синка на английском»
- [ ] 3.2 Ручная проверка каждого экрана группы 2 на эмуляторе с английским языком: нет русского текста приложения, тексты влезают в кнопки и заголовки

## 4. Смена базового языка

- [ ] 4.1 `values/` = английский, `values-ru/` = русский; `robolectric.properties` с `qualifiers=ru` (design D6). Проверка: все юнит-тесты зелёные без правок проверок текста; Robolectric-тест с `qualifiers = "de"` — интерфейс английский (сценарий «Немецкий телефон»)
- [ ] 4.2 `res/xml/locales_config.xml` и `android:localeConfig` (design D7). Проверка: на эмуляторе Android 13+ приложение есть в «Языках приложений», выбор «English» при русском телефоне даёт английский интерфейс
- [ ] 4.3 `docs/ARCHITECTURE.md`: раздел о текстах и языках (ресурсы, проверка D5, `// not UI`, показ системных категорий). Проверка: раздел есть, ссылки на скрипт и файлы верны

## Workflow follow-up

- Уточнить правило `specs` в `openspec/config.yaml`: тексты в спеках — русские, английские не дублируются (design D8).
- wallet-web и wallet-telegram — отдельные change в своих репозиториях.
- Архивировать после 4.2.
