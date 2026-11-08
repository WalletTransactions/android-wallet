# Proposal

## Why

Задача android-wallet#40. Первый запуск после установки на Huawei P20 Pro
(Android 10) — ANR: `WalletDbHelper.onCreate` заводит схему и заполняет
справочник (67 иконок категорий декодируются и перекодируются в PNG, валюты,
пары, кошельки) на главном потоке — в `MainActivity.onCreate`, где базу
попросили первой (`getCurrencyEq → PeriodFilterState.restore`). На Pixel 8 Pro
успевает, на медленном телефоне — нет. То же грозит тяжёлым миграциям
`onUpgrade` и «Поделиться» сразу после установки.

## What Changes

- `Data/DbInit`: база готова, если файл есть и его версия не ниже
  `DATABASE_VERSION` (заголовок читается без helper'а). Точки входа —
  `MainActivity`, `AiActivity`, `StatementImportActivity` — сразу после
  `super.onCreate` зовут `DbInit.redirectIfNeeded`.
- Не готова — экран «Готовим кошелёк…» (`DbInitActivity`): открывает базу
  в фоновом потоке (там же `onCreate`/`onUpgrade`), затем открывает исходный
  экран с его intent'ом; ClipData и право на файл из «Поделиться» переносятся.
  Ошибка открытия — текст на том же экране.
- `Singleton.getReadableDatabase/getWritableDatabase/getDbHelper` —
  `synchronized`: базу открывают и главный поток, и фоновые (подготовка,
  воркеры WorkManager); без этого два потока могли завести по своему helper'у.

## Capabilities

### New Capabilities
- `app-start`: первый запуск и миграции базы не блокируют главный поток.

### Modified Capabilities
<!-- нет -->

## Impact

- Новое: `Data/DbInit`, `Activities/DbInitActivity`, `layout/activity_db_init.xml`,
  строки `db_init_*` (en, ru), activity в манифесте.
- `MainActivity`, `AiActivity`, `StatementImportActivity` — одна строка в `onCreate`.
- `WalletDbHelper`: `DATABASE_NAME`/`DATABASE_VERSION` видны пакету `Data`.
- Robolectric `Data/DbInitTest`.
- Только телефон; сервер и веб не затронуты.
