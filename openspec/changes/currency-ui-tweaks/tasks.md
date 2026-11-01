# Tasks

## 1. Локальная схема

- [ ] 1.1 `WalletContract`: `CurrencyEntry.COLUMN_SHOW_IN_EQUIVALENT`, `COLUMN_IS_SHARED`, `CurrencyPairEntry.COLUMN_IS_SHARED`; `WalletDbHelper` v85: колонки в `CREATE TABLE` и `ALTER TABLE … ADD COLUMN … NOT NULL DEFAULT` (1 для флага, 0 для `is_shared`) в ветке `oldVersion < 85` **без** `UPDATE` строк (триггер `dirty`, design D2); проверка — Robolectric-тест апгрейда v84→v85 в `app/src/test/.../Data/`: колонки есть, `dirty` ни у одной строки не изменился
- [ ] 1.2 В той же ветке `onUpgrade` сбросить курсор `reference` (`AppSettings.setReferenceSyncSeq(ctx, 0)`); проверка — тот же тест: после апгрейда `getReferenceSyncSeq` = 0, курсор `ledger` не тронут
- [ ] 1.3 `Currency`/`CurrencyPair` (DataItems) и загрузка в `WalletDbLoader`/`CurrencyList`/`CurrencyPairList`: поля `showInEquivalent`, `isShared`; проверка — юнит-тест загрузки списка валют с обоими значениями
- [ ] 1.4 `docs/ARCHITECTURE.md`: схема `currency`/`currency_pair` (новые колонки, откуда берётся `is_shared`); проверка — mermaid-диаграмма и текст содержат колонки

## 2. Синк

- [ ] 2.1 `SyncEngine` push `currency`: добавить `show_in_equivalent` (boolean); проверка — юнит-тест сборки строки (по образцу `SyncEngineLocalUuidTest`)
- [ ] 2.2 `SyncEngine.applyCurrency`: флаг из строки, только если ключ есть (`row.has`); `is_shared` из `ledger_id`; `applyCurrencyPair`: `is_shared` из `ledger_id`; проверка — юнит-тест: общая строка → `is_shared = 1`, своя → 0, строка без ключа флага не меняет локальный `0`
- [ ] 2.3 Проверить, что `applyCurrency`/`applyCurrencyPair` после pull оставляют `dirty = 0` (триггер не срабатывает на `is_shared`); проверка — тот же тест читает `dirty`
- [ ] 2.4 Проверить поведение `applyIcon` при повторном pull с тем же `content_sha256` (design, Risks); если блоб перекачивается — пропускать при совпадении; проверка — юнит-тест или запись в логе синка на эмуляторе

## 3. Удаление пары

- [ ] 3.1 `WalletDbCRUD.DeleteCurrencyPair(db, pairId)`: одна транзакция, `deleted_at` у пары и её живых курсов, отказ (возврат 0) для `is_shared = 1`; проверка — Robolectric-тест: курсы и пара мягко удалены и `dirty`, общая пара не тронута
- [ ] 3.2 `CurrencyPairRateListActivity`: кнопка «Удалить пару» в тулбаре (`activity_currency_pair_rate_list.xml`), скрыта для общей пары; диалог «Удалить пару <символ>? Вся история её курса тоже будет удалена.» с «Удалить»/«Отмена»; после удаления — `finish()`; проверка — вручную на эмуляторе: своя пара удаляется, у общей кнопки нет
- [ ] 3.3 `CurrencyPairListActivity`: долгое нажатие на строку — тот же диалог для своей пары, тост «Общая пара — удалить нельзя» для общей; после удаления — `Singleton` пар инвалидирован, список перечитан; проверка — вручную на эмуляторе, в т.ч. в режиме полёта: пара исчезает сразу, после включения сети уходит в push (лог синка «Валютная пара — Удалено» на другом устройстве)

## 4. Список валют и флаг

- [ ] 4.1 `CurrencyPickerActivity`: экстра режима просмотра (design D4), заголовок «Валюты», тап → `CurrencyFormActivity`; кнопка «Валюты» в тулбаре `activity_currencypair_list.xml` открывает его; проверка — вручную: из «Курсы валют» открывается список, из «Новая валютная пара» выбор работает как раньше
- [ ] 4.2 `currency_picker_item_layout.xml` + адаптер: подписи «общая» и «не в эквиваленте»; проверка — вручную на валютах обоих видов
- [ ] 4.3 `CurrencyFormActivity` + `activity_currency_form.xml`: переключатель «Показывать в эквиваленте» (у новой валюты включён); `WalletDbCRUD.InsertCurrency`/`UpdateCurrency` пишут флаг; проверка — Robolectric-тест CRUD (флаг сохраняется, `dirty = 1`)
- [ ] 4.4 Форма общей валюты только для чтения (design D7): поля и переключатель неактивны, «Сохранить»/«Удалить» скрыты, подпись «Общая валюта — изменить нельзя»; проверка — вручную на общей и своей валюте
- [ ] 4.5 Запрет смены кода валюты из пары (D8): проверка живых пар в `WalletDbCRUD` и ошибка «Код используется в валютной паре» в `onSave`; проверка — Robolectric-тест функции проверки (смена кода у валюты пары → отказ, смена только названия → разрешено) и вручную в форме
- [ ] 4.6 Правки валют с веба (D9): переименовать/создать/удалить свою валюту на вебе, синк на телефоне; проверка — вручную: список «Валюты» и пикеры показывают изменения без перезапуска

## 5. Пикеры эквивалента

- [ ] 5.1 Чистая функция фильтрации (design D5): с флагом + текущая; пусто → исходный список; проверка — JUnit-тест по сценариям `currency-directory` «Пикеры валюты эквивалента учитывают флаг»
- [ ] 5.2 Подключить её в `BaseTabActivity.onPickCurrencyEq` (поверх `currenciesInUse`, при пустом пересечении — все валюты с флагом) и `SettingsActivity.onPickMainCurrency`; проверка — вручную: скрытая `BTC` пропадает из обоих пикеров, текущая скрытая остаётся отмеченной

## 6. Интеграция

- [ ] 6.1 Сквозная проверка с wallet-api из `wallet-api: currency-ui-tweaks`: снять флаг на телефоне → синк → `/api/reports/currencies` отдаёт `show_in_equivalent: false`, веб-пикер её не показывает; удалить пару на телефоне → на вебе в «Курсы валют» её нет; проверка — вручную на эмуляторе и в браузере

## 7. Экраны счетов (только телефон)

- [ ] 7.1 Таб «Кошельки» без «+» (design D10): убрать `btnAddAccount` из `activity_account.xml` (и упоминание кнопки в комментарии разметки), `onAddAccount`, `REQUEST_ADD_WALLET`/`REQUEST_ADD_CATEGORY` и их ветку в `AccountActivity.onActivityResult`; проверка — `grep onAddAccount` находит только `AccountPickerActivity` и `activity_account_picker.xml`; вручную: в шапке «Кошельков» нет «+», а «Ещё» → «Кошельки и категории» → «+ Добавить» заводит кошелёк
- [ ] 7.2 `AndroidManifest.xml`: `android:windowSoftInputMode="adjustResize"` у `MultiPickerActivity` и `AccountPickerActivity` (D11); `AppSettings.applyPickerKeyboard` сохраняет биты `SOFT_INPUT_MASK_ADJUST` при установке `SOFT_INPUT_STATE_ALWAYS_VISIBLE`; проверка — вручную на эмуляторе, с настройкой клавиатуры на экранах выбора и без неё: в «Фильтр» → «Счёт затрат» после тапа в поиск «Готово» видна над клавиатурой
- [ ] 7.3 `activity_multi_picker.xml`: `imeOptions="actionDone"` у `searchInput`; `MultiPickerActivity`: `OnEditorActionListener` (`IME_ACTION_DONE` или `KEYCODE_ENTER` на `ACTION_DOWN`) → `onDone(null)`; проверка — вручную по сценариям `account-screens` «Enter на клавиатуре равен „Готово“»: отмеченный и скрытый поиском счёт возвращается в «Фильтр»

## Workflow follow-up

- Сборка — JDK 17 (`~/.gradle/gradle.properties`), `bash gradlew`.
- Выкатка после wallet-api и wallet-report; архив вместе с парными `currency-ui-tweaks`.
