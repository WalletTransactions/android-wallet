## 1. Выбор валюты в «Новом кошельке»

- [ ] 1.1 `AddWalletActivity.pickCurrencyClick`: вместо `AlertDialog` запускать `CurrencyPickerActivity.buildIntent(this, getString(R.string.account_choose_currency), selectedCurrency != null ? selectedCurrency.getIso() : null)` с кодом `REQUEST_PICK_CURRENCY`
- [ ] 1.2 `AddWalletActivity.onActivityResult`: разделить обработку `REQUEST_PICK_ICON` и `REQUEST_PICK_CURRENCY`; по `RESULT_ISO` найти валюту, обновить `selectedCurrency`, флаг и подпись «ISO  —  название»
- [ ] 1.3 Удалить неиспользуемые импорты (`AlertDialog`, `CurrencyList` — если больше не нужны)

## 2. Выбор валюты в карточке кошелька

- [ ] 2.1 `AccountItemActivity.pickCurrencyClick`: тот же запуск, `currentIso` берётся из `selectedCurrency`, код `REQUEST_PICK_CURRENCY = 4`
- [ ] 2.2 `AccountItemActivity.onActivityResult`: по `RESULT_ISO` обновить `selectedCurrency`, вызвать `refreshCurrencyUI()` и `updateSaveButton()`; не сломать ветки `REQUEST_PICK_WRITEOFF_CATEGORY` и `REQUEST_PICK_ICON`

## 3. Проверка

- [ ] 3.1 Юнит-тест на разбор результата выбора (поиск валюты по ISO), если логика вынесена в отдельный метод; иначе пропустить с пометкой
- [ ] 3.2 Ручная проверка на Huawei P20 Pro (qa-сборка): «Новый кошелёк» — выбор, поиск, отмена; карточка существующего кошелька — отметка текущей валюты, «Сохранить» включается только при смене валюты; русская и английская локаль
