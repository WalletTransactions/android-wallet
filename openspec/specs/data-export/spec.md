# data-export Specification

## Purpose
TBD - created by archiving change fix-export-legacy-storage. Update Purpose after archive.

## Requirements

### Requirement: Папка экспорта без разрешений
Экспорт базы и JSON SHALL записывать файл в папку приложения
`Android/data/<пакет>/files/Download` на всех поддерживаемых версиях Android
(с 8.1). Экспорт MUST NOT требовать разрешений на доступ к памяти. «Поделиться»
SHALL отдавать последний экспортированный файл через FileProvider.

#### Scenario: Android 8–9
- **WHEN** на Android 9 пользователь нажал «Экспортировать БД»
- **THEN** файл `wallet_android_<время>.db` записан в папку приложения, без запроса разрешений и без ошибки

#### Scenario: Android 10 и новее
- **WHEN** на Android 14 пользователь нажал «Экспортировать в JSON»
- **THEN** файл `wallet_export_<время>.json` записан в ту же папку приложения, как и раньше

#### Scenario: Поделиться
- **WHEN** после экспорта пользователь нажал «Поделиться»
- **THEN** открывается системный выбор приложения с этим файлом
