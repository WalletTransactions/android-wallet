# Tasks

## 1. Схема

- [ ] 1.1 `WalletDbHelper`: `DATABASE_VERSION = 86`, в `onCreate` колонка `icon.origin INTEGER NOT NULL DEFAULT 0`, в `onUpgrade` (`oldVersion < 86`) — `ADD COLUMN`, `origin = 1 WHERE dirty = 1`, `dirty = 0` у всех иконок; `WalletContract.IconEntry.COLUMN_ORIGIN`; Robolectric-тест апгрейда v85→v86 (dirty-иконка → своя, остальные → с сервера)
- [ ] 1.2 `schema/table/icon.sql`: колонка `origin` с комментарием, обновить комментарий к `dirty` и заголовок версии

## 2. Push

- [ ] 2.1 `SyncEngine`: убрать `icon` из push (`collectDirtyIcons`, `ensureBlobUploaded`, `BLOB_UPLOAD_ATTEMPTS`), удалить `SyncApiClient.uploadBlob`/`blobExists`; юнит-тест: при dirty-иконке тело push без `icon`, к `/sync/blobs` запросов нет (мок `SyncApiClient`)
- [ ] 2.2 Новый `sync/IconSubstitutes`: индекс иконок `origin = 0` (SHA-256 картинки, имя без регистра, `unknown`) и `substituteFor(localIcon)`; юнит-тесты на порядок выбора и отсутствие заместителя
- [ ] 2.3 `collect` для `currency`/`account`: `icon_uuid` своей иконки → заместитель; нет заместителя — в `deferred`, проблема «нет иконки-заместителя для «<имя>»: строка отложена»; переписать `SyncEngineDeferIconTest` под новый сценарий откладывания — зелёный `bash gradlew testDebugUnitTest`

## 3. Pull

- [ ] 3.1 `applyIcon`: вставка с `origin = 0`; строка с uuid своей иконки → `origin = 0`; конфликт имени со своей иконкой → переименование в «<имя> (своя)» / «(своя N)»; Robolectric-тест конфликта
- [ ] 3.2 `applyAccount`/`applyCurrency`: не менять `icon_id`, если локальная иконка своя и пришёл её заместитель; тест на оба сценария спеки (заместитель / другая иконка)

## 4. UI

- [ ] 4.1 Модель `Icon` + `WalletDbLoader.LoadIcons`: поле «с сервера» из `origin`; `IconEditDialog.saveNewIcon` пишет `origin = 1`
- [ ] 4.2 `IconEditDialog`: режим просмотра для иконки с сервера с текстом «Иконка с сервера — изменить или удалить её может администратор на сайте.»; ручная проверка на эмуляторе: открыть иконку с сервера и свою, добавить свою из галереи

## 5. Документация и живая проверка

- [ ] 5.1 `docs/ARCHITECTURE.md`: раздел синка — иконки только скачиваются, заместители, `origin`
- [ ] 5.2 Живая проверка на устройстве против задеплоенного сервера: синк с новой своей иконкой — в журнале нет загрузок иконок, счёт ушёл с заместителем (виден на вебе), локально иконка своя; админ добавляет общую иконку на сайте — после синка она в списке телефона
