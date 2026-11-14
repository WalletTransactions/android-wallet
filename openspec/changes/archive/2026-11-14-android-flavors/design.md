# Design

## Context

- `applicationId` один — `com.example.wallettransactions`; на Pixel стоит
  debug-сборка (подпись `~/.android/debug.keystore` хоста) с данными прода.
- Сервер выбирается в «Ещё» → «Интеграции» (`AppSettings.serverBaseUrl`),
  по умолчанию `AppSettings.DEFAULT_SERVER_BASE_URL = http://localhost:8090/`.
- `FileProvider` в манифесте — `android:authorities="com.example.wallettransactions.fileprovider"`;
  `ExportActivity` уже берёт `getPackageName() + ".fileprovider"`.
- Вход — Credential Manager, `GetGoogleIdOption` с web client ID google-auth
  (`SyncLinkActivity.WEB_CLIENT_ID`). Google выдаёт ID-токен Android-приложению,
  только если в том же проекте GCP есть Android OAuth client с его пакетом и
  SHA-1 подписи.

## Goals / Non-Goals

**Goals:** см. spec `build-variants`.

**Non-Goals:**
- Отдельный ключ подписи для QA — оба варианта подписаны debug-ключом хоста.
- Блокировка выбора «чужого» сервера: в QA можно вписать `:8090` руками.
- Release-сборка QA в GitHub Releases — релиз публикует только `prod`.

## Decisions

### D1. Product flavors, а не суффикс у buildType
Измерение `env`, варианты `prod` и `qa`. Суффикс `.qa` у `debug` сделал бы
тестовым нынешнее приложение с данными прода (на телефоне стоит именно debug).
Вариант `prod` без суффикса обновляет его на месте. Имя `test` нельзя —
Gradle резервирует его под источники тестов.

### D2. Различия только в ресурсах и BuildConfig
- `applicationIdSuffix ".qa"`, `versionNameSuffix "-qa"`.
- `buildConfigField DEFAULT_SERVER_URL`: prod — `http://localhost:8090/`
  (как раньше), qa — `http://localhost:8091/`. LAN-адрес хоста по-прежнему
  вводится в «Интеграциях»: IP меняется от сети к сети.
- `app/src/qa/res/values/strings.xml` — `app_name` «QA Посчитай и сбереги»;
  `colors.xml` — `ic_launcher_background` `#C2672E` (иконка adaptive,
  `minSdk 27`, PNG mipmap-* не нужны).
- `app/src/qa/res/drawable/main_icon.png` — foreground иконки: рисунок prod
  закрывает почти весь фон, одной сменой цвета фона QA не отличить. Зелёная
  гамма перекрашена в оранжевую (монета не тронута), по центру — красная
  плашка «QA» внутри safe zone adaptive-иконки (видна в круглой маске).
  Генератор — `tools/qa_icon.py` (Pillow): при смене иконки prod
  перегенерировать `python3 tools/qa_icon.py app/src/main/res/drawable/main_icon.png app/src/qa/res/drawable/main_icon.png`.
- Название «QA Посчитай и сбереги» — «QA» в начале, лаунчер обрезает конец.
- Код Java не ветвится по варианту.

### D3. Тесты и CI
Unit-тесты не зависят от варианта — CI гоняет `testProdDebugUnitTest`.
`assembleDebug` собирает оба варианта, артефакт CI — оба APK.
Релиз — `assembleProdRelease` / `assembleProdDebug`.

### D4. Установка
`deploy-debug.sh` — вариант из `FLAVOR` (`prod` по умолчанию, `qa`, `all`).
Jenkins `android-install-device` — параметр `FLAVOR` с теми же значениями.

## Правки в других сервисах

Не нужны. Вне репозиториев — Google Cloud Console: Android OAuth client
`com.example.wallettransactions.qa`, SHA-1 `AF:2E:F7:C7:9A:2B:C7:E5:CD:27:5B:ED:58:C9:13:5D:65:EC:6E:1B`
(debug-ключ хоста). Аудитория токена — прежний web client, google-auth его
уже принимает.

Зарегистрирован 2026-11-03: `432841979878-u63dkaqj6s9j7tq502toc6jfe5m1up09.apps.googleusercontent.com`
(проект `make-every-penny-count`). В коде не используется — Credential Manager
опознаёт приложение по пакету + SHA-1, `serverClientId` остаётся web client.

## Без сети / отказ сервера

Не меняется: варианты различаются только адресом по умолчанию.

## Risks / Trade-offs

- До регистрации OAuth client вход Google в QA падает (`NoCredentialException`
  или ошибка 10) — prod не затронут.
- Пути артефактов меняются (`apk/prod/debug/app-prod-debug.apk`) — внешние
  скрипты со старым путём сломаются; в репозиториях они обновлены этим change.
