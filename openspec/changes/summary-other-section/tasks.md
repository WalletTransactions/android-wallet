## 1. Расчёт «Прочего»

- [ ] 1.1 Добавить чистый класс `SummaryOther` (рядом с `AdapterUtils`/`Data`): сумма «Прочего» как `Итог − (Доход − Расход)`, потоки капитала и долгов, курсовая разница как остаток, остатки капитала и долгов, «Всего с капиталом и долгами» = `netWorth + owedToMe − iOwe`, флаги видимости строк и блоков, пометки `approx`/`incomplete`
- [ ] 1.2 Юнит-тесты `SummaryOtherTest`: нет активов и долгов — строки нет; покупка актива (−399 850 / −400 000); выдача займа; неполные курсы (сумма сходится, пометка стоит); порог 0,5; «Всего…» без активов равно прежнему «Всего с долгами», без долгов — прежнему «Всего с капиталом»

## 2. Настройка

- [ ] 2.1 `AppSettings`: добавить `isSummaryOtherExpanded` / `setSummaryOtherExpanded` (по умолчанию `false`), удалить `isCapitalInSummaryOn` / `setCapitalInSummaryOn` и ключ `PREF_CAPITAL_IN_SUMMARY`
- [ ] 2.2 `activity_settings.xml` и `SettingsActivity`: заменить строку «Капитал в итогах» на переключатель «Раскрывать «Прочее» при открытии» (`switchSummaryOtherExpanded`, `onToggleSummaryOtherExpanded`), без `markDataChanged`
- [ ] 2.3 Строки ru и en: заголовок и пояснение настройки; удалить `settings_capital_in_summary*`

## 3. Карточка

- [ ] 3.1 `view_period_summary.xml`: убрать строки `capitalRow`, `netWorthRow`, `debtOwedRow`, `debtOweRow`, `debtTotalRow`, `capitalFlowRow`, `debtFlowRow`, `fxDiffRow`; добавить строку «Прочее» со стрелкой и контейнер деталей с строками из спеки `period-summary`
- [ ] 3.2 `BaseTabActivity`: заменить `refreshCapitalRow`, `refreshDebtRows`, `refreshReconciliationRows` одним `refreshOtherSection`, использовать `SummaryOther`; поле `otherExpanded` из настройки в `initTabScreen`, `onSaveInstanceState`/восстановление; обработчик нажатия на строку «Прочее»
- [ ] 3.3 Строки ru и en для подписей деталей («Прочее», «Капитал: изменение», «Долги: изменение», «Всего с капиталом и долгами» и т. д.); проверить отсутствие захардкоженного текста (CI-проверка)

## 4. Проверка

- [ ] 4.1 Прогнать `testProdDebugUnitTest` и `testQaDebugUnitTest`
- [ ] 4.2 Ручная проверка на Huawei P20 Pro (qa-сборка): «Операции», «Кошельки», «Отчёты» — свёрнуто и раскрыто, настройка вкл./выкл., нажатие ▾ не меняет настройку, смена периода сохраняет вид, возврат с другого таба сохраняет вид, перезапуск берёт вид из настройки; обязательно английская локаль
- [ ] 4.3 Ручная проверка без активов и займов (строки «Прочее» нет) и с неполными курсами
- [ ] 4.4 Обновить `docs/ARCHITECTURE.md`, если там описана карточка итогов
