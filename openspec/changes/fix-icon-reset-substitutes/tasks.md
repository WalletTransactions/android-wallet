# Tasks

## 1. Синк больше не застревает

- [x] 1.1 `IconSubstitutes.load`: кандидаты `origin = 0 AND (deleted_at IS NULL OR dirty = 1)`; тест — локально удалённая серверная иконка — заместитель в push, tombstone из pull — нет (`SyncEngineDeferIconTest.serverIconDeleted*`)

## 2. «Удалить данные» и пересев

- [x] 2.1 `ResetIcons` (оба режима) удаляет только `origin = 1`
- [x] 2.2 Сеятели иконок через `SeedIcon`: живое имя занято — берётся существующая иконка
- [x] 2.3 Тесты: пересев при живых серверных иконках — серверные на месте, своих дублей нет, счета перепривязаны; без пересева серверные не удаляются (`Data/IconResetFixTest.reset*`)

## 3. Импорт

- [x] 3.1 `WalletImportData.InsertOrUpdateAccount`: иконка счёта и валюты — живая по имени из базы, иначе живая `unknown` (без `_id = 1`)
- [x] 3.2 Тест: импорт счёта с иконкой, удалённой на телефоне, — `unknown`; с живой — она (`Data/ImportLiveIconTest`; `HardWipeCacheInvalidationTest`: импорт с протухшим кэшем иконок больше не падает на FK)

## 4. Починка пострадавших баз (v90)

- [x] 4.1 `OwnIconNames.freeOwnName` (вынесено из `SyncEngine.OwnIcon`)
- [x] 4.2 `WalletDbHelper` v90: вернуть локально удалённые серверные иконки — своя с той же картинкой → перевесить ссылки и удалить, с другой → «<имя> (своя)»; затем `RepairOrphanedIconLinks`
- [x] 4.3 Тест миграции v89 → v90 на базе с пострадавшими иконками (`Data/IconResetFixTest.upgradeTo90_*`)

## 5. Проверка

- [x] 5.1 `testQaDebugUnitTest` — весь набор зелёный: 336 тестов, 0 упавших (2026-11-10); `ci/check_hardcoded_text.sh` — чисто
- [ ] 5.2 Устройство: «Удалить данные» с «Иконки» + «Пересеять набор» → импорт → синк без «нет иконки-заместителя» — Тест: [android-wallet#52](https://github.com/WalletTransactions/android-wallet/issues/52)
