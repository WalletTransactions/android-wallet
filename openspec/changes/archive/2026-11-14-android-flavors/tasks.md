# Tasks

## 1. Варианты сборки

- [x] 1.1 `app/build.gradle`: `flavorDimensions "env"`, `prod`/`qa` (D1, D2) — `applicationIdSuffix`, `versionNameSuffix`, `DEFAULT_SERVER_URL`; проверка — `assembleDebug` собирает `app-prod-debug.apk` и `app-qa-debug.apk`, `aapt dump badging` показывает пакеты и названия
- [x] 1.2 `app/src/qa/res/values/` — `app_name`, `ic_launcher_background` (D2); проверка — две разные иконки в лаунчере (ручная, п. 3.1)
- [x] 1.3 Манифест: `FileProvider` — `${applicationId}.fileprovider`; проверка — `aapt dump xmltree` у обоих APK, установка обоих (п. 3.1)
- [x] 1.4 `AppSettings.DEFAULT_SERVER_BASE_URL` = `BuildConfig.DEFAULT_SERVER_URL`; проверка — unit-тесты `testProdDebugUnitTest` и `testQaDebugUnitTest`

## 2. Сборка и установка

- [x] 2.1 `.github/workflows/ci.yml`, `release.yml`, `ci/run_unit_tests.sh`, `ci/README.md`, `README.md` (D3); проверка — CI на PR зелёный, артефакт содержит оба APK
- [x] 2.2 `deploy-debug.sh` — `FLAVOR` (D4); проверка — `FLAVOR=qa ./deploy-debug.sh` ставит QA
- [x] 2.3 BuildPlatform `jobs/android-install-device/Jenkinsfile` — параметр `FLAVOR`, README (D4); `jobs/android-unit-tests/Jenkinsfile` — путь JUnit XML `testProdDebugUnitTest`; проверка — просмотр диффа (BuildPlatform без git, бэкапы `*.bak-20261103` рядом с файлами)

## 3. Ручная проверка

- [x] 3.1 (2026-11-14, Pixel 8 Pro, prod 1.0.151 + qa 1.0.151-qa: prod обновлялся на месте с 2026-07-27, база на месте; две иконки в лаунчере — зелёная и оранжевая с «QA»; в QA сервер `:8091`, в prod `:8090` — на устройстве введены вручную, значения по умолчанию подтверждены по `BuildConfig`, на чистой установке не проверялись; экспорт → «Поделиться» работает в обоих) На Pixel: `prod` поверх нынешнего приложения — данные на месте; `qa` рядом — вторая иконка «QA Посчитай и сбереги», сервер по умолчанию `:8091`; экспорт → «Поделиться» в обоих — Тест: [android-wallet#32](https://github.com/WalletTransactions/android-wallet/issues/32)
- [x] 3.2 (2026-11-14, Pixel 8 Pro: QA привязан к Google-аккаунту, «Синхронизировать сейчас» на `:8091` прошёл — отправлено счёт ×1, признак актива ×1, операции ×2) Вход Google в QA после регистрации Android OAuth client (`com.example.wallettransactions.qa`, SHA-1 из design.md) — привязка к test-контуру, синк — Тест: [android-wallet#32](https://github.com/WalletTransactions/android-wallet/issues/32)

## Workflow follow-up

- [x] Регистрация OAuth client в Google Cloud Console — сделано 2026-11-03, ID в design.md.
