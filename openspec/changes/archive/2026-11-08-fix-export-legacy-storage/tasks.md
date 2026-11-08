# Tasks

## 1. Исправление

- [x] 1.1 `ExportActivity.exportDir(Context)` — `getExternalFilesDir(DOWNLOADS)` на всех версиях, ветка для API < 29 убрана; `file_provider_paths.xml` без `external-path`

## 2. Проверка

- [x] 2.1 Robolectric `Activities/ExportDirTest`: на API 28 и 34 папка экспорта — внутри папки приложения, FileProvider отдаёт URI — зелёный локально
- [x] 2.2 Эмулятор API 28: экспорт базы и JSON без ошибки, «Поделиться» открывает выбор приложения — проверено 2026-11-08 (эмулятор Android 9, QA debug): main 2897c86 — «Error: /storage/emulated/0/Download/wallet_android_….db (Permission denied)»; с фиксом — «Done!» для .db и .json в `Android/data/com.example.wallettransactions.qa/files/Download/`, «Поделиться» открывает системный выбор без падения (приложений-получателей в голом образе нет)
