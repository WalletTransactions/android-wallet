# android-wallet — «Посчитай и сбереги»

Android-приложение для учёта личных финансов: кошельки в разных валютах,
доходы и расходы по категориям, переводы, повторяющиеся операции, отчёты
и курсы валют. Работает офлайн на локальной SQLite; при желании
синхронизируется с собственным сервером WalletTransactions.

## Возможности

- **Операции** — доходы, расходы, переводы между кошельками (в т.ч.
  валютные, с комиссией), фильтр по периоду, категориям и кошелькам.
- **Кошельки** — счета в любых валютах, остатки и эквивалент в выбранной
  валюте.
- **Отчёты** — доходы и расходы по категориям за период.
- **Повторяющиеся операции** — правила с расписанием, включаются и
  выключаются одним переключателем.
- **Курсы валют** — валютные пары, история курсов и график; загрузка из
  Frankfurter (ECB), ЦБ РФ и Yahoo Finance.
- **Иконки** — свои иконки категорий и кошельков с кадрированием.
- **Импорт / экспорт** — JSON-формат всей базы (тот же, что понимает импорт
  из Money Lover).
- **Синхронизация** — вход через Google (Credential Manager), push/pull
  изменений с сервером, фоновое автоподключение через WorkManager.

Нижняя навигация: **Операции · Кошельки · Отчёт · Ещё**.

## Стек

| | |
|---|---|
| Язык | Java 8 |
| SDK | minSdk 27, target/compileSdk 34 |
| Сборка | Gradle 7.5, Android Gradle Plugin 7.4.2, JDK 17 |
| UI | AndroidX AppCompat, Material Components, ConstraintLayout, RecyclerView |
| Данные | SQLite (`SQLiteOpenHelper`, схема v79), seed-справочники в `assets/seed.json` |
| Сеть | OkHttp 4 |
| Авторизация | Credential Manager + Google ID, токены в EncryptedSharedPreferences |
| Фон | WorkManager |
| Тесты | JUnit 4, Robolectric |

## Структура

```
app/src/main/java/com/example/wallettransactions/
  Activities/    экраны (MainActivity — операции, AccountActivity, ReportActivity, MoreActivity …)
  Adapters/      адаптеры списков
  Data/          SQLite: схема (WalletContract), миграции (WalletDbHelper), CRUD, импорт/экспорт
  DataItems/     модели: операции, счета, валюты и курсы, фильтры
  sync/          синхронизация с сервером: AuthSession, SyncApiClient, SyncEngine, SyncWorker
  Views/         свои view: график курса, кадрирование иконки
datepickupview/  модуль выбора даты
ci/              unit-тесты в контейнере (JDK17 + Android SDK)
```

## Сборка и запуск

Нужны JDK 17 и Android SDK (platform 34, build-tools 30.0.3). Путь к SDK —
в `local.properties` (`sdk.dir=...`), файл в git не хранится.

```bash
./gradlew assembleDebug          # APK: app/build/outputs/apk/debug/
./deploy-debug.sh [serial]       # собрать и поставить на устройство через adb
```

`versionCode` растёт автоматически при каждой сборке (`app/version.properties`),
`versionName` — `1.0.<code>`; версия и время сборки видны в «Настройки → О приложении».

## Тесты

```bash
./gradlew :app:testDebugUnitTest     # локально
bash ci/run_unit_tests.sh            # в одноразовом контейнере, на хосте нужен только Docker
```

Отчёты JUnit — `app/build/test-results/testDebugUnitTest/`. Подробнее —
[`ci/README.md`](ci/README.md).

## Синхронизация с сервером

Приложение полностью работает без сервера. Для синхронизации: «Ещё →
Синхронизация» — указать адрес сервера (по умолчанию `http://localhost:8090/`)
и связать аккаунт через Google.

Поток: `POST /auth/nonce` → Credential Manager (Google ID token) →
`POST /auth/google/mobile` → пара access/refresh-токенов → `POST /sync/push` /
`pull`. На текущем этапе сервер доступен только в LAN и без TLS, поэтому
в манифесте включён `usesCleartextTraffic`.

Серверная часть (wallet-api, google-auth и др.) — в соседних репозиториях
организации [WalletTransactions](https://github.com/WalletTransactions).
