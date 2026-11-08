# Proposal

## Why

Задача android-wallet#38. На Android 8.1–9 (API 27–28) экспорт базы и JSON
падал: `ExportActivity.getDownloadsDir()` для API < 29 писал в общую папку
`Download/`, а `WRITE_EXTERNAL_STORAGE` во время работы никто не запрашивал —
разрешение только объявлено в манифесте. Нашлось при разборе поддержки
Huawei P20 Pro (вышел на 8.1). На Android 10+ ошибки нет: там файл пишется
в папку приложения.

## What Changes

- Экспорт на всех версиях пишет в папку приложения
  `Android/data/<пакет>/files/Download` (`getExternalFilesDir(DOWNLOADS)`) —
  без разрешений; ветка для API < 29 убрана. На Android 10+ поведение то же.
- `file_provider_paths.xml`: убран `external-path` для общей `Download/` —
  больше не используется.
- Плата на 8–9: файла нет в общих «Загрузках» файлового менеджера (туда он и
  раньше не попадал — запись падала); «Поделиться» работает как на 10+.

## Capabilities

### New Capabilities
- `data-export`: куда экспорт кладёт файл и как его отдаёт.

### Modified Capabilities
<!-- нет -->

## Impact

- `Activities/ExportActivity`: `exportDir(Context)` вместо ветки по версии.
- `res/xml/file_provider_paths.xml`.
- Robolectric-тест `Activities/ExportDirTest` (API 28 и 34).
- Только телефон; сервер и веб не затронуты.
