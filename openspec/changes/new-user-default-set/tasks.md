# Tasks

## 1. Новый набор

- [ ] 1.1 Утвердить с пользователем состав набора (черновик — design D2): категории, валюты, пары, иконки. Проверка: список в комментарии к PR подтверждён — Тест: [android-wallet#29](https://github.com/WalletTransactions/android-wallet/issues/29)
- [x] 1.2 Переписать `assets/seed.json`: секции `category_icons`, `currencies`, `currency_pairs`, `accounts` (только категории и `Transfer`/`Transfer commission`), без `rates`; недостающие флаги — в `res/drawable`. Проверка: юнит-тест на Robolectric по чистой базе — категории набора есть, кошельков 0, курсов 0, имён из прежнего сида («Квартира Рисан», «Альфа Саша Руб») нет, `ResolveDrawableId` ни разу не упал на `unknown` — выполнено 2026-11-07: `StarterSetTest` (`cleanInstall_*`, `everySeedDrawableExists`); 8 флагов (GBP, CNY, BYN, UAH, AMD, TRY, THB, AED) — векторные `flag_*.xml`
- [x] 1.3 Проверить, что в коде и тестах нет опоры на прежний состав сида (имена счетов, число иконок). Проверка: `bash gradlew testProdDebugUnitTest` зелёный — выполнено 2026-11-07: `TransactionSaveButtonTest` брал кошелёк из сида, теперь заводит его сам; весь набор тестов зелёный
- [x] 1.4 Имена категорий `{ru, en}` в `seed.json` и выбор языка посева; названия валют набора из `java.util.Currency` (design D10). Проверка: Robolectric-тесты с `qualifiers = "ru"` и `"de"` — сценарии «Русский телефон», «Английский телефон»; смена локали после посева имена не меняет — выполнено 2026-11-07: `StarterSetTest.cleanInstall_ru_*`, `cleanInstall_otherLanguage_englishNames`, `languageChangedAfterSeed_namesStay`
- [ ] 1.5 Показываемое имя валюты: общая — по ISO на текущем языке, своя — хранимое; список «Валюты», пикеры, форма валюты. Проверка: юнит-тесты на сценарии `currency-directory` («Общая валюта на английском телефоне», «Своя валюта с известным кодом», «Неизвестный код»); ручная проверка на эмуляторе с английским языком — юнит-тест выполнен 2026-11-07 (`StarterSetTest.sharedCurrencyShownInPhoneLanguage_ownKeepsItsName`, одна точка — `Currency.getName`); ручная проверка ждёт — Тест: [android-wallet#29](https://github.com/WalletTransactions/android-wallet/issues/29)

## 2. Метка «из набора»

- [x] 2.1 БД v88: `from_seed INTEGER NOT NULL DEFAULT 0` у `account`, `currency`, `currency_pair` (`WalletDbHelper`, `WalletContract`); сид ставит 1. Проверка: тест апгрейда v87→v88 — у существующих строк 0; тест чистой базы — у строк сида 1 — выполнено 2026-11-07: `StarterSetTest.upgradeV88_existingRowsNotFromSeed`, `seedRowsMarkedFromSeed`
- [x] 2.2 Сброс метки при правке имени, иконки, кода валюты, символа пары (`WalletDbCRUD`); операция по категории метку не трогает. Проверка: юнит-тесты на сценарии «Переименование категории набора» и «Операция без правки категории» — выполнено 2026-11-07: `StarterSetTest.renameSeedCategory_clearsMark`, `saveSeedCategoryUnchanged_keepsMark`, `operationOnSeedCategory_keepsMark`, `editSeedCurrency_clearsMark` (пары на телефоне не правятся)
- [x] 2.3 `from_seed` не уходит в push. Проверка: тест сборщиков push — в JSON строк `account`/`currency`/`currency_pair` ключа нет — выполнено 2026-11-07: `LedgerConnectionTest.pushRows_haveNoFromSeed`
- [x] 2.4 Пересев в «Полная очистка базы» использует новый сид и ставит метку. Проверка: тест `HardWipe…` — после очистки с пересевом строки помечены — выполнено 2026-11-07: `StarterSetTest.hardWipeWithReseed_markedAgain`

## 3. Подключение: загрузка первой и принятие по ключу

- [x] 3.1 `AppSettings.ledgerConnectionPending` и правило старта/снятия (design D4). Проверка: юнит-тесты — ставится при сессии и `hasSyncedRows == false`; переживает прерванную загрузку; снимается после успешного push — выполнено 2026-11-07: `LedgerConnectionTest.connectionStarts_onFreshDb_andSurvivesPartiallyAdoptedRows`, `alreadySynced_noConnection`; снятие после push — `SyncRunnerTest.connecting_pullBeforePush` (`done` после `push`)
- [x] 3.2 `SyncRunner`: при подключении порядок refresh → pull → шаг подключения → push → purge; новый статус «ждёт решения». Проверка: `SyncRunnerTest` — при подключении `pull` вызван раньше `push`, при ожидании решения `push` не вызван, исход «ждёт решения»; обычный проход — прежний порядок — выполнено 2026-11-07: `SyncRunnerTest.connecting_*`, прежние `steps_*` зелёные
- [x] 3.3 Принятие по ключу в `applyCurrency`/`applyCurrencyPair`/`applyAccount` только при подключении (design D6). Проверка: юнит-тесты — общая `EUR` заменяет `EUR` набора (одна строка, `is_shared = 1`, `dirty = 0`, кошелёк ссылается на неё); «продукты» с сервера заменяет «Продукты» с двумя операциями (операции на месте, отправляются только они); «Cafe» и «Кафе» остаются обе; вне подключения столкновение пропускается, как раньше — выполнено 2026-11-07: `LedgerConnectionTest` (`sharedCurrency_*`, `pairAdoptedBySymbol`, `categoryWithOperations_*`, `otherName_*`, `walletAdopted_*`, `outsideConnection_*`)

## 4. Подключение: шаг после загрузки

- [x] 4.1 Удаление нетронутых строк набора при непустом кошельке и пропуск удаления при пустом (design D7). Проверка: юнит-тесты на сценарии «Неиспользованная категория», «Удалённая когда-то категория», «Своя категория без операций», «Новый человек»; системные `Transfer`/`Transfer commission` не удаляются — выполнено 2026-11-07: `LedgerConnectionTest.nonEmptyLedger_untouchedSeedDropped_usedOwnSystemStay`, `emptyLedger_*`
- [x] 4.2 Признак «ждёт решения» при непустом кошельке и неотправленных операциях. Проверка: юнит-тест — 7 офлайн-операций → признак стоит, push не вызывался; операций нет → признака нет, push вызван — выполнено 2026-11-07: `LedgerConnectionTest.nonEmptyLedger_offlineOps_ask_untilTransferChosen`, `nonEmptyLedger_noOfflineOps_noQuestion`, `SyncRunnerTest.connecting_askedQuestion_*`
- [x] 4.3 Исходы «Перенести» и «Удалить с телефона» (design D8). Проверка: юнит-тесты — после «Перенести» push отправляет операции, признаки сняты; после «Удалить» неотправленных операций/счетов/валют/пар нет, свои иконки на месте, следующий проход обычный — выполнено 2026-11-07: «Перенести» — `afterPull(db, true)` → PUSH, снятие признаков — `connectionStarts_*`; «Удалить» — `LedgerConnectionTest.discardOffline_*`
- [x] 4.4 Подключение без сети (design D9). Проверка: юнит-тест — ошибка загрузки → ничего не удалено и не отправлено, признак подключения остаётся, исход «нет сети» — выполнено 2026-11-07: `SyncRunnerTest.connecting_pullFailed_*`

## 5. Диалог

- [ ] 5.1 Диалог «Операции без входа» на главном экране и подтверждение удаления, тексты дословно из `ledger-connection`, склонение N через plurals. Проверка: юнит-тест склонения (1 операция, 2 операции, 7 операций); ручная проверка на эмуляторе — диалог появляется, закрытие без выбора показывает его снова при следующем открытии, «Отмена» в подтверждении возвращает к диалогу — юнит-тест выполнен 2026-11-07 (`LedgerConnectionTest.dialogTexts`; склонение пока в Java, в ресурсы переедет с `android-i18n`); ручная проверка ждёт — Тест: [android-wallet#29](https://github.com/WalletTransactions/android-wallet/issues/29)
- [x] 5.2 `docs/ARCHITECTURE.md`: сид и метка `from_seed`, режим подключения в проходе синка. Проверка: раздел про синк описывает порядок прохода при подключении — выполнено 2026-11-07: «Синхронизация» → «Встроенный набор и подключение к кошельку (v88)», «Миграции»

## 6. Проверка на устройстве

- [ ] 6.1 Чистая установка QA-варианта без входа: категории набора, кошельков нет, создать первый кошелёк, внести операцию. Проверка: ручная, на эмуляторе — Тест: [android-wallet#29](https://github.com/WalletTransactions/android-wallet/issues/29)
- [ ] 6.2 Новый Google-аккаунт на test, 5 офлайн-операций, вход: вопроса нет, на вебе категории набора и 5 операций. Проверка: ручная + веб test — Тест: [android-wallet#29](https://github.com/WalletTransactions/android-wallet/issues/29)
- [ ] 6.3 Переустановка QA-варианта и вход в наполненный кошелёк на test с 2 офлайн-операциями: вопрос, «Перенести» — на вебе 2 новые операции, нетронутых категорий набора нет, удалённые раньше категории не вернулись. Проверка: ручная + веб test — 2026-11-07 на Pixel 8 Pro (QA 1.0.109) пройдена ветка «Удалить с телефона»: вопрос появился, после удаления на телефоне 8653 операции кошелька, офлайн-операций нет; на test новых операций и категорий набора нет (MCP); валюты без дублей, лек и др. с флагами. Ветка «Перенести» ждёт — Тест: [android-wallet#29](https://github.com/WalletTransactions/android-wallet/issues/29)

## Workflow follow-up

- Наполнение общего справочника валют и пар на сервере (wallet-api, администратор) — отдельная работа; без неё валюты набора уходят в кошелёк как свои.
- Архивировать change после ручных проверок 6.1–6.3.
