# Proposal

## Why

На телефоне одно приложение, и оно смотрит то на прод (`:8090`), то на
test-контур (`:8091`). Переключение сервера в «Интеграциях» означает полную
очистку базы и загрузку с нуля, а ручные проверки на test идут прямо в
приложении с реальными данными. Нужны два приложения рядом: прод и
тестовое, каждое со своей базой, настройками и входом.

## What Changes

- Два варианта сборки (product flavors, измерение `env`):
  - `prod` — `applicationId` прежний (`com.example.wallettransactions`),
    название «Посчитай и сбереги», сервер по умолчанию `http://localhost:8090/`.
    Ставится поверх нынешнего приложения, данные остаются.
  - `qa` — `com.example.wallettransactions.qa`, название «QA Посчитай и сбереги»,
    другой цвет фона иконки, сервер по умолчанию `http://localhost:8091/`.
- `FileProvider` — authority от `applicationId`, иначе второе приложение
  не установится (`INSTALL_FAILED_CONFLICTING_PROVIDER`).
- Адрес сервера по умолчанию — из `BuildConfig.DEFAULT_SERVER_URL` варианта.
- CI, релиз, `deploy-debug.sh`, `ci/run_unit_tests.sh`, Jenkins-задачи
  BuildPlatform — на задачи с вариантом (`testProdDebugUnitTest`,
  `assembleProdRelease`, `install<Flavor>Debug`).

## Capabilities

### New Capabilities
- `build-variants`: варианты prod/qa — идентификатор, название, иконка,
  сервер по умолчанию, совместная установка.

### Modified Capabilities
<!-- нет -->

## Impact

- `app/build.gradle`, `app/src/main/AndroidManifest.xml`, `AppSettings.java`,
  `app/src/qa/res/values/` (название, цвет иконки).
- `.github/workflows/ci.yml`, `.github/workflows/release.yml`,
  `ci/run_unit_tests.sh`, `ci/README.md`, `README.md`, `deploy-debug.sh`.
- BuildPlatform: `jobs/android-install-device/Jenkinsfile` (параметр
  `FLAVOR`), README.
- **Вне репозитория:** Android OAuth client в Google Cloud для
  `com.example.wallettransactions.qa` + SHA-1 debug-ключа — без него вход
  Google в QA-приложении не работает. Сервисы (google-auth, wallet-api,
  wallet-gateway) не меняются.
