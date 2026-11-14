# Tasks

## 1. Экспорт (android-wallet)
- [x] 1.1 `WalletExportData`: раздел `counterparties` (живые, по порядку создания; `name`, `comment`, `icon`, `archived`), раздел присутствует всегда
- [x] 1.2 `WalletExportData`: блок `debt` (имя контрагента через `counterparty_uuid`, `comment`, `due_date` только если задан) у долговых кошельков
- [x] 1.3 `WalletExportData`: блок `asset` (`type` строкой, `include_in_net_worth`, `comment`) у активов
- [x] 1.4 `WalletExportData`: `"system": "revaluation"` у категории переоценки; не экспортировать `counterparty_contact` и поля синка
- [x] 1.5 Обновить `buildSchemaComment()` описанием новых полей

## 2. Импорт (android-wallet)
- [x] 2.1 `FillOperationDataFromJson`: порядок контрагенты → счета → пары → курсы → операции
- [x] 2.2 Разбор `counterparties`: поиск живого по `AccountNames.keySql`, обновление или вставка с `dirty`; иконка по имени (`LiveIconIdOrUnknown`)
- [x] 2.3 Блок `debt` в `ParseAndInsertJsonAccount`: найти/завести контрагента, создать/оживить `account_debt`; `due_date` в `yyyy-MM-dd`
- [x] 2.4 Блок `asset`: тип, `include_in_net_worth`, комментарий; создать/оживить `account_asset`
- [x] 2.5 Проверки: оба блока, блок на категории, противоположный признак, неизвестный `type`, неверная `due_date` — пропуск «accounts#N — …»
- [x] 2.6 `system: "revaluation"` — поставить `system_kind` через `GetOrCreateSystemCategory`, без второй категории
- [x] 2.7 Файл без новых полей и старый формат (голый массив) работают как раньше

## 3. Тесты (android-wallet)
- [x] 3.1 Unit/инструментальные тесты импорта: долг с новым и с существующим контрагентом, актив, взаимоисключение, переоценка, файл без новых полей
- [x] 3.2 Круговой тест «экспорт → импорт на пустую базу»: контрагенты, долги со сроками, активы, переоценка сохраняются
- [ ] 3.3 Прогонять `testProdDebugUnitTest`/`testQaDebugUnitTest` (корневой `testDebugUnitTest` тестов приложения не запускает)

## 4. Формат и строки
- [x] 4.1 Диалог «Формат» JSON (`strings_import.xml`, ru и en): `counterparties`, `debt`, `asset`, `system`
- [x] 4.2 README и `schema/` — описание расширенного формата

## 5. Конвертер Money Lover (`tools/moneylover`)
- [x] 5.1 `csv_export.build_accounts_json`: блок `debt` уже содержит `counterparty`; `comment`/`due_date` в выгрузке Money Lover нет — не пишутся (задаются в приложении)
- [x] 5.2 `main.py`: писать в `trans.json` раздел `counterparties` (уникальные контрагенты долговых кошельков, `Без имени` включительно)
- [x] 5.3 `README.md`: раздел про долги (категории `Loan`/`Debt Collection`/`Debt`/`Repayment`, определение контрагента, `ALIASES`/`OVERRIDES`, структура `counterparties` и `debt` в `trans.json`)
- [ ] 5.4 Прогнать конвертер на свежей выгрузке и импортировать `trans.json` на тестовом устройстве (Huawei P20 Pro), проверить экран «Долги» на русском и английском

## 6. Проверка
- [ ] 6.1 Синк после импорта разносит контрагентов и долговые кошельки на второе устройство/в wallet-api
