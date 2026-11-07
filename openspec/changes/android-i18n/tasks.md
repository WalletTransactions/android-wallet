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

Выполнено 2026-11-07 для всех групп: layout/menu — одним проходом (314 атрибутов →
`strings.xml`), Java — по группам в `values/strings_<группа>.xml`. Тексты, которые
строятся вне экранов (журнал синка, отчёт импорта, ИИ), берутся через `Texts`
(контекст приложения). Логи и технические детали исключений — по-английски или с
`// not UI`. Тексты русского интерфейса не менялись, кроме: английские статусы импорта
(«Importing operations…») стали русскими, в примерах подсказок импорта личные названия
заменены общими, ручные окончания — plurals. Вид экранов на эмуляторе по одному не
сверялся — его покрывает ручная проверка 3.2 (английский) и прогон русского там же.

- [x] 2.1 Главный экран, вкладки, `BaseTabActivity`, `MoreActivity`, `FilterActivity` — `strings_main.xml`
- [x] 2.2 Операция: `TransactionActivity`, `MultiPicker*`, ввод ИИ в окне операции — `strings_operation.xml`, `PickerSort` хранит ресурс
- [x] 2.3 Счета и категории: `AccountItemActivity`, `AddWalletActivity`, `IconEditDialog`, `IconCropActivity` — `strings_accounts.xml`, `Icon.IconType` хранит ресурс
- [x] 2.4 Валюты и курсы: `CurrencyPairListActivity`, `CurrencyPairRateListActivity`, `CurrencyFormActivity`, `RateEditDialog` — `strings_currency.xml`
- [x] 2.5 Повторяющиеся операции и напоминания: `RecurringOperationActivity`, `RecurringOperationListActivity`, `RecurringOperation`, `reminder/ReminderWorker` — `strings_recurring.xml`; причины пропуска напоминания — только в лог
- [x] 2.6 Настройки и безопасность: `SettingsActivity`, `LockActivity`, `security/BiometricErrors`, `ResetDataActivity` — `strings_settings.xml`; помощники безопасности берут `Context`, `AppLockTest` обновлён
- [x] 2.7 Импорт и экспорт: `ImportActivity`, `ExportActivity`, `StatementImportActivity`, `Data/WalletImportData`, `Data/WalletExportData` — `strings_import.xml`; описание формата внутри JSON-экспорта — данные, `// not UI`
- [x] 2.8 Интеграции и синк: `IntegrationsActivity`, `SyncLinkActivity`, `sync/SyncEngine` (журнал), `SyncLog`, `SyncRunner`, `NewOperationsNotifier`, `AuthSession`, `TelegramLinkClient` — `strings_sync.xml`; диалог «Операции без входа» — plurals
- [x] 2.9 ИИ: `AiActivity`, `ai/AiTexts` — только тексты интерфейса, запросы к wallet-ai помечены `// not UI` (design D2) — `strings_ai.xml`; `AiTextsTest` — Robolectric с `qualifiers = "ru"`. Язык распознавания речи (`ru-RU`) не трогали — это вход для wallet-ai, не интерфейс
- [x] 2.10 Остаток: файл исключений проверки пуст. Проверка: `ci/check_hardcoded_text.sh` без исключений зелёный — выполнено 2026-11-07: в `ci/hardcoded_text_allowlist.txt` 0 файлов, проверка и все юнит-тесты зелёные. Проверка ловит только кириллицу: английские литералы без кириллицы (например, `Snackbar.setAction("Action")`) она не видит

## 3. Английский

- [x] 3.1 `values-en/strings.xml` — перевод всех строк и plurals; lint `MissingTranslation` — ошибка. Проверка: lint зелёный; Robolectric-тест с `qualifiers = "en"` — сценарии «Числа с окончаниями / Английский», «Журнал синка на английском» — выполнено 2026-11-07: 833 ключа (все файлы `strings*.xml`), название приложения «Count & Save» (QA — «QA Count & Save»). Вместо lint — `UiLanguageTest.everyStringTranslated_withSamePlaceholders` (ключи и подстановки совпадают, идёт в CI; lint в CI не запускается); `plurals_english`, `syncJournal_english`
- [x] 3.2 Ручная проверка каждого экрана группы 2 на эмуляторе с английским языком: нет русского текста приложения, тексты влезают в кнопки и заголовки — выполнено 2026-11-07 пользователем на Pixel 8 Pro (Android 17), QA 1.0.109: «английский норм». Категории набора, заведённые по-русски, после смены языка остаются русскими — так и задумано (решение пользователя)

## 4. Смена базового языка

- [x] 4.1 `values/` = английский, `values-ru/` = русский; `robolectric.properties` с `qualifiers=ru` (design D6). Проверка: все юнит-тесты зелёные без правок проверок текста; Robolectric-тест с `qualifiers = "de"` — интерфейс английский (сценарий «Немецкий телефон») — выполнено 2026-11-07: `robolectric.properties` понадобился уже на этапе 3 (с `values-en/` локаль en-US по умолчанию давала английский); все тесты зелёные, `UiLanguageTest.germanPhone_englishTexts`
- [x] 4.2 `res/xml/locales_config.xml` и `android:localeConfig` (design D7). Проверка: на эмуляторе Android 13+ приложение есть в «Языках приложений», выбор «English» при русском телефоне даёт английский интерфейс — код выполнен 2026-11-07 (`localeConfig` в итоговом манифесте, `assembleProdDebug`/`assembleQaDebug` зелёные); ручная проверка 2026-11-07 на Pixel 8 Pro (Android 17): язык выбирается в «Языках приложений», English при русском телефоне даёт английский интерфейс
- [x] 4.3 `docs/ARCHITECTURE.md`: раздел о текстах и языках (ресурсы, проверка D5, `// not UI`, показ системных категорий). Проверка: раздел есть, ссылки на скрипт и файлы верны — выполнено 2026-11-07: «Тексты и языки»

- [ ] 4.4 Пункт «Язык» в настройках приложения (design D7): «Как на телефоне», «English», «Русский» через `AppCompatDelegate`, `AppLanguage.localized` для `Texts`/системных категорий/языка набора на Android 8–12. Проверка: `AppLanguageTest`; ручная — на телефоне выбор в настройках приложения меняет язык сразу и совпадает с «Языками приложений» — код выполнен 2026-11-07, ручная проверка ждёт — Тест: [android-wallet#30](https://github.com/WalletTransactions/android-wallet/issues/30)

## Workflow follow-up

- Уточнить правило `specs` в `openspec/config.yaml`: тексты в спеках — русские, английские не дублируются (design D8).
- wallet-web и wallet-telegram — отдельные change в своих репозиториях.
- Архивировать после 4.2.
