# Tasks

## 1. Схема

- [x] 1.1 `WalletDbHelper`: `DATABASE_VERSION = 86`, в `onCreate` колонка `icon.origin INTEGER NOT NULL DEFAULT 0`, в `onUpgrade` (`oldVersion < 86`) — `ADD COLUMN`, `origin = 1 WHERE dirty = 1`, `dirty = 0` у всех иконок; `WalletContract.IconEntry.COLUMN_ORIGIN`; Robolectric-тест апгрейда v85→v86 (dirty-иконка → своя, остальные → с сервера) — b3ba2e4 (`IconOriginDbTest`; иконки `seed.json` тоже своя — см. design п. 1)
- [x] 1.2 `schema/table/icon.sql`: колонка `origin` с комментарием, обновить комментарий к `dirty` и заголовок версии — b3ba2e4

## 2. Push

- [x] 2.1 `SyncEngine`: убрать `icon` из push (`collectDirtyIcons`, `ensureBlobUploaded`, `BLOB_UPLOAD_ATTEMPTS`), удалить `SyncApiClient.uploadBlob`/`blobExists`; юнит-тест: при dirty-иконке тело push без `icon`, к `/sync/blobs` запросов нет (мок `SyncApiClient`) — d69b0da (`SyncEngineDeferIconTest.push_hasNoIconTable_andNoBlobRequests`, транспорт — наследник `SyncApiClient`)
- [x] 2.2 Новый `sync/IconSubstitutes`: индекс иконок `origin = 0` (SHA-256 картинки, имя без регистра, `unknown`) и `substituteFor(localIcon)`; юнит-тесты на порядок выбора и отсутствие заместителя — 1c85d3b (`IconSubstitutesTest`)
- [x] 2.3 `collect` для `currency`/`account`: `icon_uuid` своей иконки → заместитель; нет заместителя — в `deferred`, проблема «нет иконки-заместителя для «<имя>»: строка отложена»; переписать `SyncEngineDeferIconTest` под новый сценарий откладывания — зелёный `bash gradlew testDebugUnitTest` — d69b0da (`SyncEngine.PushIconRefs`)

## 3. Pull

- [x] 3.1 `applyIcon`: вставка с `origin = 0`; строка с uuid своей иконки → `origin = 0`; конфликт имени со своей иконкой → переименование в «<имя> (своя)» / «(своя N)»; Robolectric-тест конфликта — b38d2ce (`SyncEnginePullIconsTest`; плюс своя с той же картинкой становится серверной без дубля — см. design п. 5)
- [x] 3.2 `applyAccount`/`applyCurrency`: не менять `icon_id`, если локальная иконка своя и пришёл её заместитель; тест на оба сценария спеки (заместитель / другая иконка) — b38d2ce (`SyncEngine.PullIconGuard`, `SyncEnginePullIconsTest`)

## 4. UI

- [x] 4.1 Модель `Icon` + `WalletDbLoader.LoadIcons`: поле «с сервера» из `origin`; `IconEditDialog.saveNewIcon` пишет `origin = 1` — 6597796 (`IconOriginDbTest.loadIcons_fromServerFlagFollowsOrigin`)
- [x] 4.2 `IconEditDialog`: режим просмотра для иконки с сервера с текстом «Иконка с сервера — изменить или удалить её может администратор на сайте.»; ручная проверка на эмуляторе: открыть иконку с сервера и свою, добавить свою из галереи — Код — 6597796; проверка на устройстве — Тест: [android-wallet#6](https://github.com/WalletTransactions/android-wallet/issues/6) — проверено 2026-11-02: Pixel 8 Pro на test, иконки с сервера и своя открыты в диалоге, своя добавлена из галереи (локальная проверка, следов на сервере по замыслу нет; своя иконка ушла заместителем — см. 5.2)

## 5. Документация и живая проверка

- [x] 5.1 `docs/ARCHITECTURE.md`: раздел синка — иконки только скачиваются, заместители, `origin` — 42cadc0 (раздел «Иконки с сервера и свои (v86)», «Миграции», «Синхронизация»)
- [x] 5.2 Живая проверка на устройстве против задеплоенного сервера: синк с новой своей иконкой — в журнале нет загрузок иконок, счёт ушёл с заместителем (виден на вебе), локально иконка своя; админ добавляет общую иконку на сайте — после синка она в списке телефона — Код — d69b0da, b38d2ce; проверка на устройстве (после деплоя wallet-api/wallet-web server-managed-icons) — Тест: [android-wallet#6](https://github.com/WalletTransactions/android-wallet/issues/6) — проверено 2026-11-02: push 19:16Z — счёт «Wirex» с `icon_uuid` общей иконки «Клод», без строк `icon` и без `POST /sync/blobs` с телефона, на вебе показан в 19:34Z; общие иконки «Клод», «Oreo», «88», добавленные админом на сайте (18:53, 19:11, 20:48Z), телефон забрал через `/sync/pull` за 6–154 с
