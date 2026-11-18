## 1. Суммы и ленты

- [x] 1.1 `AdapterUtils.getFormat`: символ группировки — `U+00A0` в любой локали
- [x] 1.2 `TransactionArrayAdapter.getTransferView`: одновалютный перевод — `setAmountTransferText`; разновалютный — расход красным; перевод с займом — по `DebtLabels.Label` (`GAVE`/`REPAID` красным, `TOOK`/`RETURNED` зелёным)
- [x] 1.3 `transaction_item_layout`: иконка категории 44dp без отступа; `transaction_item_transfer`/`transaction_item_revaluation`: убран `padding` иконок; для перевода подложка `bg_icon_circle_translucent` (`colorIconCircleTranslucent`, светлая и тёмная тема)

## 2. Кошельки, долги, справочник

- [x] 2.1 `AccountActivity`/`activity_account`: удалён переключатель «Показывать архивные», архивные скрыты всегда
- [x] 2.2 `AccountPickerActivity`/`activity_account_picker`: чипы типов «Все / Обычные / Активы / Займы» (`picker_wallet_type_*`), только в справочнике на списке «Кошельки»
- [x] 2.3 `DebtsActivity`: удалён пункт «Операции займа», строка `debt_action_operations` убрана из ресурсов
- [x] 2.4 `activity_account_item`: кнопка «Переоценить» — обводная пилюля (`bg_pill_outline_state`)

## 3. Проверка

- [x] 3.1 Сборка `:app:installProdDebug` и юнит-тесты `testProdDebugUnitTest` проходят (584 теста, 0 падений); добавлен `AmountGroupingTest`
- [x] 3.2 Huawei P20 Pro (prod, русская локаль): чипы типов в справочнике — на вкладке «Кошельки», нет на «Категории»; на табе «Кошельки» нет галочки архивных; лента: `-412 000,00€`, одновалютный перевод нейтральным цветом без знака, иконки без отступа
- [ ] 3.3 Не проверено на устройстве: цвета долговых переводов (нет займов на телефоне), светлая тема, английская локаль, фильтрация списка кошельков чипами (на телефоне заведены только обычные кошельки «Nalichnye» и «Karta»)
