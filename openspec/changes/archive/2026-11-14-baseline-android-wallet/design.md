# Design: базовая спецификация android-wallet

## Context

Мотивация — в `proposal.md`. Спецификации в `specs/` восстановлены по коду на
коммите `316bfc7` (main, 2026-11-11, схема базы v89) и 2026-11-14 сверены с
`d3de058` (схема v91; между ними — долги, поиск по подобию, сверка «Итога»,
«Вопрос по приложению», списание остатка, варианты сборки, шапка пустой
ленты): `app/src/main/java`,
ресурсы `res/values-ru/` и `res/layout/`, юнит-тесты `app/src/test`. Сверялись
с `README.md`, `docs/ARCHITECTURE.md`, уже архивированными спецификациями
`openspec/specs/` и открытыми changes.

При расхождении документа и кода источником базы считался **код**. Расхождения
и неожиданное поведение вынесены в раздел «Наблюдения» ниже, а не записаны
требованиями.

## Goals / Non-Goals

**Goals:**
- Описать наблюдаемое поведение тех частей приложения, у которых нет
  спецификации, так, чтобы каждый сценарий проверялся юнит-тестом или ручным
  прогоном на устройстве.
- Зафиксировать найденные расхождения и пробелы покрытия как исходные данные
  для следующих changes.

**Non-Goals:**
- Правка существующих требований `openspec/specs/` (кроме добавления новых в
  `account-screens`, `currency-pairs`, `data-export`).
- Поведение, описанное спецификациями `build-variants`,
  `wallet-archive-writeoff`, `debts`, `docs-assistant`, `operations-filter`,
  `operations-tab`, и то, что ведёт открытый change `new-user-default-set`.
- Контракты wallet-api, google-auth, wallet-ai, wallet-telegram и источников
  курсов: описано только то, что приложение вызывает и как трактует ответ.
- Устройство кода (классы, схема, миграции) — это `docs/ARCHITECTURE.md`.
- Любое изменение поведения, включая исправление наблюдений.

## Decisions

### D1. База только на пробелы
Спецификации, рождённые changes с 2026-10-24, точнее этой базы: у них есть
обсуждённый дизайн и проверка на устройстве. Поэтому база их не переписывает,
а дополняет. Для трёх частично покрытых областей используется `## ADDED
Requirements` к той же capability: при архивации требования допишутся в
существующий файл, а не появится параллельная спецификация. Отвергнуто:
переписать всё заново одной базой — потеря истории и риск рассинхрона с
архивом.

### D2. Разбиение по экранам и потокам пользователя
Capability следуют тому, как пользователь ходит по приложению: табы
(«Операции», «Кошельки», «Отчёты», «Ещё»), окна «вглубь» (операция, курсы,
иконки, импорт), фоновые процессы (синк, повторы, напоминания). Расчёт
балансов и эквивалента вынесен отдельно (`balances-and-equivalent`), потому
что им пользуются карточка периода, таб «Кошельки», отчёт и лента. Так
типичное изменение затрагивает одну-две спецификации.

### D3. Граница синка
`sync-run` (уже есть) описывает проход целиком. Новая `sync-data-exchange`
описывает обмен строками: состав и порядок таблиц, порции, конфликты,
применение загруженного. `server-account` описывает вход и запуск синка
глазами пользователя. Протокол — README и `openapi.yaml` wallet-api и
google-auth, ссылкой.

### D4. Чужие спецификации и открытые changes не трогаются
Из прежних открытых changes `archive-wallet-writeoff` и `android-flavors`
заархивированы (`wallet-archive-writeoff`, `build-variants`), к ним добавились
`debts`, `docs-assistant`, `operations-filter`, `operations-tab`. База на эти
требования только ссылается; там, где её прежний текст им противоречил (состав
таблиц синка, фильтр по счетам «или» без групп, пункты «Ещё», секции таба
«Кошельки», очистка данных), текст приведён к ним. `new-user-default-set`
меняет `sync-run` и `currency-directory` и с базой не пересекается;
`summary-other-section` ДОБАВЛЯЕТ в `period-summary` — его архивируют после
базы.

### D5. Тексты интерфейса — из `values-ru`
По правилу `config.yaml` сценарии цитируют тексты дословно. Источник — русские
ресурсы, то, что видит основной пользователь. Английский перевод описан
спецификацией `ui-language` и здесь не дублируется.

## Risks / Trade-offs

- [Объём] 213 требований, восстановленных чтением кода: часть может быть
  неточна → задачи сверки в `tasks.md`, по одной на capability.
- [Длинные требования] 70 требований длиннее 500 символов: `openspec validate
  --strict` даёт предупреждения (без `--strict` change валиден). → задача 3.2:
  перенести примеры и крайние случаи в сценарии.
- [База отстаёт от кода] Снята на `316bfc7` → дальнейшие изменения поведения
  идут через changes; при сверке на более позднем коммите он фиксируется в
  Context.
- [Описано текущее, а не желаемое] Некоторые требования описывают спорное
  поведение. Оно оставлено, если выглядит намеренным; явные дефекты — только в «Наблюдениях».

## Наблюдения (не требования, не исправляются в этом изменении)

### Данные и деньги

| # | Наблюдение | Где |
|---|---|---|
| Р1 | Возможна потеря правки: строку правят, пока её порция летит на сервер, — `clearDirty` снимает dirty по uuid, правка на сервер не уйдёт. Javadoc утверждает обратное | `sync/SyncEngine::clearDirty` |
| Р2 | Строка из pull, нарушающая локальную уникальность (имя счёта, код валюты, символ пары, PK курса), пропускается навсегда: не откладывается, курсор уходит дальше, след только в logcat | `SyncEngine::upsertByUuid`/`applyRow` |
| Р3 | Валюту кошелька можно сменить при наличии операций — суммы молча начинают читаться в новой валюте | `AccountItemActivity::pickCurrencyClick`, `WalletDbCRUD::UpdateAccountCurrency` |
| Р4 | Перевод A → A можно сохранить: при сохранении from ≠ to не проверяется (запрет есть только при смене вида) | `TransactionActivity::SaveClick` |
| Р5 | Выбор времени открывается с `Calendar.HOUR` (12 ч) в 24-часовом диалоге: у операции на 15:30 показывается 03:30 | `TransactionActivity::btnTimeClick` |
| Р6 | Правка курса обновляет строку по дате без `deleted_at IS NULL` (задевает tombstone'ы); перенос на дату с живым курсом даёт два живых курса на дату — локального уникального индекса нет, на сервере 23505. Удаление курса тоже заново помечает старые tombstone'ы | `RateEditDialog::saveRate`, `::doDeleteRate` |
| Р7 | У `CZK` в `CBR_CODES` стоит код USD `R01235` (placeholder): для CZK/RUB по умолчанию выбран ЦБ РФ, и загрузка молча пишет курсы доллара | `CurrencyPairRateListActivity::CBR_CODES` |
| Р8 | «На конец» не учитывает операции ровно в 23:59 последнего дня (`date < '… 23:59'`), а отчёт (BETWEEN) учитывает | `WalletDbLoader::LoadBalancesPerWallet`, `PeriodFilterState::endOfDay` |
| Р9 | `balance_eq` и `amount_eq` всегда округляются до 2 знаков, независимо от `precision` валюты эквивалента; суммы в валюте кошелька на табе «Кошельки» и в выборе счёта — всегда 2 знака | `LoadBalancesPerWallet`, `v_operation_eq`, `AccountActivity.WalletBalanceAdapter`, `AccountPickerActivity.AccountPickerAdapter` |
| Р10 | `sum(amount_eq)` пропускает NULL: категория, где у части операций нет курса, считается частично и не помечается | `WalletDbLoader::GenerateIncomeReportByAccount`/`GenerateExpanseReportByAccount` |
| Р11 | Сумма правила повторяющейся операции не округляется до точности валюты | `RecurringOperationActivity::onSaveClick` |
| Р12 | Одна ошибочная вставка откатывает генерацию по всем правилам в этом запуске (общая транзакция) | `RecurringOperationsDb::generateDue` |
| Р13 | Правило с удалённым счётом пропускается без сдвига `last_date`; после восстановления счёта все пропущенные повторы создаются разом без диалога. `RecurringWorker` всё равно планирует пробуждения по таким правилам | `RecurringOperationsDb::generateDue`, `RecurringWorker::nextDue` |
| Р14 | Правила повторяющихся операций не синкаются (`recurring_operation` нет в `TABLE_ORDER`): на втором устройстве их нет, при переустановке теряются | `SyncEngine::TABLE_ORDER` |
| Р15 | «Операции и счета» в очистке мягко удаляют `account`/`account_operating` (и, после debts D14, `account_debt`), но не `account_asset` — признак актива остаётся живым у удалённых счетов | `WalletFillData::ResetOperationsAndAccounts` |
| Р16 | Полная очистка не сбрасывает курсоры синка: следующий pull инкрементальный, старые данные сами не вернутся, хотя текст предупреждает «могут вернуться» | `ResetDataActivity::runHardWipe` |

### Импорт и экспорт

| # | Наблюдение | Где |
|---|---|---|
| Р17 | JSON-экспорт теряет время операции (остаётся день), вид счёта (актив, архив), symbol/precision валют, повторяющиеся операции, картинки иконок — а экран обещает «перенести данные на другое устройство» | `WalletExportData::BuildExportJson` |
| Р18 | Экспорт операций соединяет со счетами без `deleted_at`, а счета выгружает только живые: операция по удалённому счёту при реимпорте пропускается с «нет счёта» | `WalletExportData::buildOperations` |
| Р19 | Экспорт БД копирует только `wallet.db`, без `-wal`/`-journal` — в режиме WAL последние изменения могут не попасть (не проверено на устройстве); кнопка не блокируется на время экспорта | `ExportActivity::exportDatabase`, `::ExportDatabaseClick` |
| Р20 | Повторный импорт операций (CSV и JSON) создаёт дубли | `WalletImportData::InsertOperationRecord`, `::ParseAndInsertJsonOperation` |
| Р21 | CSV разбирается `split(",")` без кавычек: запятая в комментарии режет его, кавычки из примера формата остаются в тексте | `WalletImportData::ParseAndInsertOperation`/`ParseAndInsertAccount` |
| Р22 | Неразбираемая дата или сумма в CSV и неверная `date` в JSON откатывают весь файл, а отсутствующее поле JSON только пропускает элемент | `WalletImportData::FillOperationDataFromFile`/`FromJson` |
| Р23 | Привязка кошелька к валюте ищет счёт по точному имени без правил `AccountNames` и без `deleted_at`; пометка kind=WALLET задевает удалённые счета с тем же именем | `WalletImportData::InsertOrUpdateAccount` |
| Р24 | CSV курсов: строка для несуществующей пары молча не считается и не попадает в отчёт о пропусках (в JSON — пропуск с причиной). JSON `currency_pairs`: элемент всегда засчитывается, даже если пара не создана. `InsertCurrencyPair` может привязать пару к удалённой валюте | `WalletImportData::FillCurrencyPairRateDataFromStream`, `::ParseAndInsertJsonCurrencyPair`, `WalletFillData::InsertCurrencyPair` |
| Р25 | Android 8–9: при отказе в разрешении — английский захардкоженный тост «Write External Storage permission allows us to read files…»; после выдачи разрешения импорт сам не продолжается | `ImportActivity::requestPermission` |
| Р26 | Символ и точность новой валюты при импорте (коммит `5733d4c`, #54) реализованы, но требования нет ни в базе, ни в архиве | `WalletImportData`, `ImportNewCurrencyTest` |

### Лента, фильтр, отчёты, период

| # | Наблюдение | Где |
|---|---|---|
| Р27 | «Только переводы» не показывает разновалютные переводы (главная проводка «кошелёк → Transfer»), а «Только расходы» показывает их строкой перевода; «Только доходы» пропускает входящую ногу, и «Перевод» в доходах показывает всю её сумму вместо курсовой разницы | `DataFilter::matchesKind`, `WalletDbLoader::LoadOperations`, `::GetTransferNetEq` |
| Р28 | Фильтр только по кошельку-получателю теряет разновалютный перевод целиком, при этом `CountOperations` его считает — «Показать N» обещает больше строк, чем выйдет | `WalletDbLoader::AttachLinkedOperations`, `::CountOperations` |
| Р29 | Поиск идёт по хранимым именам («Перевод» не находит `Transfer»), `LIKE` без учёта регистра только для ASCII («кофе» не найдёт «Кофе») | `DataFilter::getSQLFilter` |
| Р30 | После правки строка ленты перепроверяется только на период, счета и вид — не на поиск и диапазон сумм | `MainActivity::updateSingleTransaction` |
| Р31 | Шапки дней «Today»/«Yesterday» захардкожены по-английски (нарушает `ui-language` «Полнота перевода»); «сегодня» — последние 24 часа, а не календарный день. Шапка недели — дата первой операции | `TransactionArrayAdapter.DateComparerDay/Week::getDateName` |
| Р32 | Ветка «?» в итоге группы недостижима — показывается «~?» | `TransactionArrayAdapter::bindDayHeader`, `EquivalentResult` |
| Р33 | Подстановка «первого выбранного» счёта из фильтра в новую операцию берёт первую категорию в порядке строк экрана, а не в порядке выбора | `BaseTabActivity::putFilterPrefill`, `FilterActivity::onApplyFilter` |
| Р34 | Стрелка «вперёд» ломает период короче месяца (04.11–10.11 → 04.12–04.01), слушатель срабатывает дважды; месячный период «уползает» при листании (01.12–30.12) | `ChooseDatePeriodFragment::onCreateView`, `ChooseDateFragment::changeDate` |
| Р35 | Нет доходов или расходов за период — плитка показывает «~?<символ>» вместо 0 | `ReportByAccount::getTotal` → `AdapterUtils::setAmountText` |
| Р36 | Итог отчёта ставит «≈», только если валюты строк различаются между собой, не сравнивая с валютой эквивалента; одна категория без эквивалента делает весь итог «~?» | `ReportByAccount::getTotal` |
| Р37 | Разбивка по валютам в отчёте — `Double.toString` (возможно «1.0E7»); положительные суммы доходов окрашены цветом расхода | `ReportByAccountsAdapter::getView` |
| Р38 | Кошелёк без операций до конца периода не показывается на табе «Кошельки», хотя javadoc обещает обратное; в справочнике он виден с 0 | `LoadBalancesPerWallet`, `AccountActivity::buildWalletRows` |
| Р39 | Сумма в ленте без пробела перед символом валюты, у ноги и комиссии — с пробелом | `AdapterUtils::setAmountText`, `TransactionArrayAdapter::formatAmount` |

### Курсы, повторы, напоминания

| # | Наблюдение | Где |
|---|---|---|
| Р40 | При отпускании ручки графика конец выделения сдвигается на +1 день | `RateChartView::onTouchEvent` |
| Р41 | «Каждый месяц» пропускает первый месяц периода, если период начинается не 1-го; «Готово: загружено N» считает и просто обновлённые даты | `CurrencyPairRateListActivity::filterByFrequency`, `::onRunDownload` |
| Р42 | Ручной ввод, правка и загрузка курсов доступны для общих пар (нет проверки `isShared`); курс в диалоге правки — всегда 5 знаков | `CurrencyPairRateListActivity`, `RateEditDialog` |
| Р43 | Список «Курсы валют»: последний курс без ограничения «не позже сегодня»; 0% — зелёным «+0.00%», на экране истории — нейтрально; порядок пар не задан; адаптер грузит всю историю на каждый `getView`; точность пары не ограничена и не меняется | `CurrencyPairListAdapter::getView`, `WalletDbLoader::LoadCurrencyPairList`, `CurrencyPairListActivity::onCreatePair` |
| Р44 | Пауза правила: переключатель в списке включает без досоздания пропущенного, а «Повтор включён» в форме предлагает «Создать» пропущенные | `RecurringOperationsDb::setActive`, `RecurringOperationActivity::onSaveClick` |
| Р45 | Напоминание: ручная операция, совпавшая по счетам и сумме с любым правилом (даже выключенным), не считается внесённой; отказ в разрешении на уведомления оставляет переключатель включённым без подсказки | `ReminderWorker::hasEnteredToday`, `SettingsActivity::onToggleRemind` |

### Вход, синк, настройки, безопасность

| # | Наблюдение | Где |
|---|---|---|
| Р46 | Выхода из аккаунта нет: `POST /auth/logout` не вызывается, токены стираются только после 401 на refresh | `TokenStore::clear`, `IntegrationsActivity` |
| Р47 | После 401 на refresh ручной синк пишет «Не связано с сервером», а строка статуса — «Сессия истекла»; любой другой не-2xx `/auth/refresh` (400, 403, 5xx) — «Сервер недоступен по сети» | `AuthSession::ensureFresh`, `IntegrationsActivity::showSyncResultDialog` |
| Р48 | Любой не-2xx от `GET /sync/pull` (включая 401 и 500) даёт исход «нет сети», тот же 401 на push — «ошибка» | `SyncApiClient::pull` |
| Р49 | Смена адреса сервера не сбрасывает токены и курсоры старого сервера (сброс — только при смене email) | `IntegrationsActivity::onPickServerUrl` |
| Р50 | Пришла только смена валюты кошелька, `operation_link` или `currency_conversion` — журнал пуст, «Изменений нет…», ревизия табов не поднимается | `SyncEngine::applyAccountOperating`, `SyncRunner::joinOrStart` |
| Р51 | 503 с телом не в JSON — «Некорректный ответ сервера» вместо «Telegram-бот на сервере не настроен» | `TelegramLinkClient::execute` |
| Р52 | Keystore недоступен — `TokenStore` бросает RuntimeException, экран «Интеграции» падает (в `ForgotPin` исключение перехвачено) | `TokenStore::prefs` |
| Р53 | «Оценить приложение»: запасной вызов браузера без try — без магазина и браузера приложение падает | `SettingsActivity::onRateApp` |
| Р54 | Уведомления о новых операциях идут под одним id — новое заменяет непрочитанное | `NewOperationsNotifier::notifyIfAny` |
| Р55 | Тумблер «Скрывать в недавних» показан включённым и без кода доступа; ошибки при проверке текущего кода в «Настройках» идут в общий счётчик и паузу | `SettingsActivity::paintSecurity`, `LockActivity::submit` |
| Р56 | Время сборки в «О приложении» — в UTC без пометки пояса | `SettingsActivity::onCreate`, `app/build.gradle` |

### Счета и иконки

| # | Наблюдение | Где |
|---|---|---|
| Р57 | Системную категорию («Перевод», «Комиссия за перевод») можно заархивировать — она пропадает из выбора, переводы находят её по `system_kind`. Объяснение у неё — hint поля комментария, а `createCategory` пишет comment = name, поэтому объяснения не видно | `AccountItemActivity::onCreate/save`, `WalletDbCRUD::createCategory` |
| Р58 | `AddCategoryActivity` не вызывает `markDataChanged()`, в отличие от `AddWalletActivity`/`AccountItemActivity` | `AddCategoryActivity::btnSaveClick` |
| Р59 | Добавление иконки с занятым именем: `insert` молча возвращает -1, тоста нет, диалог открыт (при правке — «Ошибка при обновлении: …») | `IconEditDialog::saveNewIcon` |
| Р60 | Иконку, на которую ссылаются только удалённые счета или валюты, удалить нельзя: «Иконка используется…» (жёсткий DELETE под FK) | `IconEditDialog::deleteIcon` |

### Документация и мёртвый код

| # | Наблюдение | Где |
|---|---|---|
| Р61 | `ARCHITECTURE.md` ссылается на `BalanceReportActivity` и `totalMarker` — их нет; «На начало/На конец» показывает общая карточка `BaseTabActivity`, а не `ReportActivity` | `docs/ARCHITECTURE.md` «Расчёт балансов», «Три состояния» |
| Р62 | `ARCHITECTURE.md`: «отчёты дохода/расхода фильтр не принимают» — таб «Отчёты» передаёт фильтр шапки; «строки без курса помечают итог приблизительным» — ставится «~»; «`getBalancesToDate` используется» — не используется | `docs/ARCHITECTURE.md`, `WalletDbLoader.java:81` |
| Р63 | `ARCHITECTURE.md` «Загрузка курсов»: автовыбор источника и `insert or replace` — в коде выбор из трёх (Yahoo по умолчанию, ЦБ для RUB-пар) и update-иначе-insert | `docs/ARCHITECTURE.md`, `CurrencyPairRateListActivity::showDownloadDialog` |
| Р64 | `ARCHITECTURE.md` «Известные шероховатости»: «Support Library 28 / `android.support.*`» — код на androidx; «защита от null в `TransactionArrayAdapter`» — её нет, защита в `LoadOperations`. Раздел «Иконки» описывает системный кроп с запасным центральным — в коде своё кадрирование `IconCropActivity` | `docs/ARCHITECTURE.md` |
| Р65 | README называет пункт панели «Отчёт», в интерфейсе — «Отчёты» | `README.md:32` |
| Р66 | Спецификация `account-screens` цитирует «+ Добавить», в коде — «+ Добавить кошелёк или категорию» и диалог выбора | `openspec/specs/account-screens`, `values-ru/strings.xml` `activity_account_picker_text_1` |
| Р67 | Спецификация `icon-sync` называет вход «Ещё → Удалить данные», в коде — пять нажатий на «Версию» в «Настройках»; javadoc `SyncLog`/`SyncScheduler`/`SyncWorker`/`SyncRunner` — «Настройки → Синхронизация», на деле «Ещё → Интеграции» | `openspec/specs/icon-sync`, `sync/*.java` |
| Р68 | У `app-start` и `data-export` Purpose остался «TBD - created by archiving change …» | `openspec/specs/{app-start,data-export}/spec.md` |
| Р69 | Подпись «Комиссия пока не сохраняется в базу — см. план работ.» видна на экране, хотя комиссия пишется | `res/layout/activity_transaction.xml` (`activity_transaction_text_7`) |
| Р70 | Мёртвый код: `AccountsAdapter` (с `AccountItem.isLike` как regex и `getBalanceEq` без даты), `AccountItemFragment`, `CurrencyPairDetailActivity`/`Fragment` (шаблон с «Dummy stuff»), `rate_list_menu.xml`, `report_list_menu.xml` (кроме `checkgroup`), `IconManagerActivity::openGallery` | разное |

Каждое наблюдение при желании оформляется отдельным change: потеря данных и
целостность (Р1–Р4, Р6, Р7, Р13–Р18, Р20–Р24), расчёты и отображение (Р5,
Р8–Р11, Р27–Р39), курсы и повторы (Р12, Р40–Р45), вход и синк (Р46–Р54),
документация и чистка (Р61–Р70), прочее (Р19, Р25, Р26, Р55–Р60).

## Покрытие

Юнит-тесты на этом этапе не запускались (задача 1.2). Сценарии с тестами,
по данным сверки чтением:

| Capability | Есть тесты | Без теста (главное) |
|---|---|---|
| `operation-entry` | `TransactionSaveButtonTest`, `PlainNumberTest` | калькулятор, точность ввода, дата/время, курс и сумма получателя, запись ноги и комиссии, правка ног, удаление, сортировки и поиск пикера |
| `operations-feed` | `OperationItemMovementTest` (модель), `CapitalAssetsDbTest` | состав ленты и схлопывание ног, группировка, вёрстка строк, экран «Фильтр», `CountOperations`, поиск |
| `period-summary` | частично `CapitalAssetsRecalcTest`, `CapitalAssetsDbTest` | период по умолчанию, листание, сохранение, плитки, «+» |
| `balances-and-equivalent` | `CurrencyReloadInPlaceTest`, частично `CapitalAssetsDbTest`, `OperationItemMovementTest` | остаток на момент, курс на дату, обратная пара, тождественный курс, пометки при показе |
| `reports` | — (только переоценка, чужая зона) | всё |
| `wallets-tab` | — | всё |
| `account-screens` (ADDED) | `AccountNamesTest`, частично `CapitalAssetsScreensTest` | справочник, поиск, архив, удаление (`DeleteAccount`) |
| `recurring-operations` | `RecurringOperationScheduleTest`, `RecurringOperationsDbTest` | экраны, «Пропущенные повторы», `setActive`, `RecurringWorker.nextDue` |
| `reminders` | `ReminderWorkerTest` | настройки, разрешение, уведомление и тап |
| `currency-rates`, `currency-pairs` (ADDED) | — | всё |
| `icon-library` | — | всё |
| `data-import` | `ImportNewCurrencyTest` | форматы, отчёт о пропусках, откат файла |
| `data-export` (ADDED) | — | всё |
| `data-reset` | `HardWipeAllTablesTest`, `HardWipeCacheInvalidationTest`, `ResetCacheOrderTest`, `IconResetFixTest`, `StarterSetTest` | вход, подтверждения, выборочное удаление |
| `app-lock` | `AppLockTest` (хранение, проверка, пауза, отпечаток, забыли код, таймаут) | шаги UI, «Назад» на замке |
| `server-account` | `AuthSessionTokenTest` (exp JWT) | вход, хранение, 401, автосинк, диалоги, журнал, DNS |
| `telegram-link` | — | всё |
| `sync-notifications` | `NewOperationsNotifierTest` | условия показа, вид, приватность |
| `sync-data-exchange` | `SyncEngine*Test` (LocalUuid, PullDeferred, AccountAsset, CurrencyFlags, PullCurrencyCache, MissingReference, Tombstones) | порции, обрыв отправки, 400 и потолок раундов, кэши |
| `more-and-settings` | `PickerKeyboardModeTest` | остальное |

## Migration Plan

Кода и данных изменение не касается. После сверки (`tasks.md`) change
архивируется: спецификации переносятся в `openspec/specs/`, ADDED-требования
дописываются в `account-screens`, `currency-pairs`, `data-export`. Откат —
удалить созданные спецификации и дописанные требования.

## Open Questions

- Р46: выход из аккаунта не сделан намеренно (вход один раз на устройство)
  или это пробел? От ответа зависит, требование это или change.
- Р14: правила повторяющихся операций остаются только на устройстве по
  решению или ждут синка? Сейчас база описывает их как локальные.
