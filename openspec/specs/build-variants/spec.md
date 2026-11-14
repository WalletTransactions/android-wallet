# build-variants Specification

## Purpose
TBD - created by archiving change android-flavors. Update Purpose after archive.

## Requirements

### Requirement: Варианты сборки prod и qa
Приложение SHALL собираться в двух вариантах. `prod` — `applicationId`
`com.example.wallettransactions`, название «Посчитай и сбереги», сервер по
умолчанию `http://localhost:8090/`. `qa` — `applicationId`
`com.example.wallettransactions.qa`, название «QA Посчитай и сбереги», иконка в другой гамме
с надписью «QA», сервер по умолчанию `http://localhost:8091/`. Код
приложения в вариантах MUST быть один и тот же.

#### Scenario: Обновление нынешнего приложения
- **WHEN** на устройстве стоит прежняя debug-сборка, и ставится `prod` debug
- **THEN** она обновляется на месте, база и настройки сохраняются

#### Scenario: Сервер по умолчанию
- **WHEN** QA-приложение установлено впервые
- **THEN** в «Интеграциях» адрес сервера — `http://localhost:8091/`

### Requirement: Совместная установка
Варианты `prod` и `qa` SHALL устанавливаться на одно устройство одновременно,
с отдельными базой, настройками и токенами. Ресурсы с глобальными именами
(authority `FileProvider`) MUST зависеть от `applicationId`.

#### Scenario: Оба приложения рядом
- **WHEN** на устройстве стоит `prod`, и ставится `qa`
- **THEN** установка проходит, в лаунчере две иконки, данные приложений не пересекаются

#### Scenario: Экспорт в каждом приложении
- **WHEN** в любом из двух приложений выполняется экспорт и «Поделиться»
- **THEN** файл отдаётся через `FileProvider` своего приложения

### Requirement: Сборка и установка по варианту
CI SHALL гонять unit-тесты `testProdDebugUnitTest` и выкладывать debug-APK обоих
вариантов; релиз MUST публиковать только `prod`. `deploy-debug.sh` и Jenkins
`android-install-device` SHALL ставить вариант из `FLAVOR`: `prod` (по
умолчанию), `qa` или `all`.

#### Scenario: Установка QA скриптом
- **WHEN** запускается `FLAVOR=qa ./deploy-debug.sh`
- **THEN** на устройство ставится `com.example.wallettransactions.qa`, prod не трогается
