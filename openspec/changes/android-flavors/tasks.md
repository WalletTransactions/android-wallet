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

- [ ] 3.1 (2026-11-03: `app-qa-debug.apk` 1.0.99-qa поставлен рядом с prod 1.0.98, оба пакета на устройстве; осталось — prod поверх, иконки, `:8091`, экспорт) На Pixel: `prod` поверх нынешнего приложения — данные на месте; `qa` рядом — вторая иконка «QA Посчитай и сбереги», сервер по умолчанию `:8091`; экспорт → «Поделиться» в обоих
- [ ] 3.2 Вход Google в QA после регистрации Android OAuth client (`com.example.wallettransactions.qa`, SHA-1 из design.md) — привязка к test-контуру, синк

## Workflow follow-up

- [x] Регистрация OAuth client в Google Cloud Console — сделано 2026-11-03, ID в design.md.
