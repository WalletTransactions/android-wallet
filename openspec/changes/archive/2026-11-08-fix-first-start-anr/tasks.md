# Tasks

## 1. Подготовка базы в фоне

- [x] 1.1 `Data/DbInit`: `isReady` (файл + версия), `redirectIfNeeded`, `initIntent` с ClipData и GRANT-флагами
- [x] 1.2 `Activities/DbInitActivity` + `layout/activity_db_init.xml` + строки `db_init_preparing`/`db_init_failed` (en, ru) + манифест
- [x] 1.3 Гейт в `onCreate` у `MainActivity`, `AiActivity`, `StatementImportActivity`
- [x] 1.4 `Singleton`: открытие базы и helper — `synchronized`, helper на application context

## 2. Проверка

- [x] 2.1 Robolectric `Data/DbInitTest`: нет базы / старая версия — переход; готовая — без перехода; ClipData и право на файл переносятся; экран подготовки создаёт базу в фоне и открывает следующий
- [x] 2.2 Huawei P20 Pro, чистая установка QA: «Готовим кошелёк…» → «Операции», без ANR — проверено 2026-11-08 (Android 10, QA из ветки): `DbInitActivity` на экране через 1,2 с, «база готова за 29399 мс», затем `MainActivity`; новых `data_app_anr` нет (до правки — ANR 21:32 на `Bitmap.compress` в главном потоке); полный `testProdDebugUnitTest` — 314 тестов, 0 упавших
