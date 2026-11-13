# Tasks

## 1. Данные

- [x] 1.1 `WalletDbLoader.LoadAccountBalance` (итоговый остаток, D2) и `WalletDbCRUD.WriteOffBalance` (D3, D6, D9): направление по знаку, округление до точности, 0 — без проводки, `23:59`, комментарий; проверка — Robolectric-тесты `ArchiveWriteOffDbTest`
- [x] 1.2 `AccountPickerActivity.cHideSystem` — без системных категорий (D8); проверка — тест `ArchiveWriteOffDbTest`/просмотр кода, ручная (п. 3.1)

## 2. Экран «Кошелёк»

- [x] 2.1 `AccountItemActivity.btnSaveClick`: условие показа диалога (D5, D4), сохранение одной транзакцией со списанием (D9), после записи — reload, `markDataChanged`, синк (D11); проверка — `ArchiveWriteOffDbTest` (условие показа — `needsWriteOff`), ручная (п. 3.1)
- [x] 2.2 `ArchiveWriteOffDialog` + `dialog_archive_writeoff.xml`: остаток, категория, дата (`CalendarPicker`), три кнопки (D8, D10); проверка — сборка, ручная (п. 3.1)
- [x] 2.3 Выбор категории из диалога через `AccountPickerActivity` и `onActivityResult`; проверка — ручная (п. 3.1)
- [x] 2.4 Строки ru/en (`strings_accounts.xml`), без кириллицы в Java; проверка — `ci/check_hardcoded_text.sh`

## 3. Проверка

- [x] 3.1 (проверено 2026-11-13 на Pixel 8 Pro и Huawei P20 Pro, ru/en; синк на сервер — после wallet-api 0.4.1-rc.1: операция «Архивация кошелька» в базе test) На устройстве (qa-сборка, test-контур): остаток > 0 → «Списать» на категорию сегодняшним числом — расход с комментарием «Архивация кошелька», остаток 0, кошелька нет в пикерах, после синка операция в web; остаток < 0 → доход; остаток 0 → без диалога; «Без списания»; «Отмена»; категория → без диалога; в выборе категории нет «Перевода»/«Комиссии»/«Переоценки» — Тест: [android-wallet#44](https://github.com/WalletTransactions/android-wallet/issues/44)
