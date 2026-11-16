## Why

Валюта кошелька выбирается в `AlertDialog` со списком «ISO — название», без поиска и флагов. В приложении уже есть полноценный экран выбора валюты (`CurrencyPickerActivity`: поиск, флаги, отметка текущей), которым пользуются «Валютные пары». Два способа выбора одного и того же выглядят и работают по-разному.

## What Changes

- «Новый кошелёк» (`AddWalletActivity`) и карточка кошелька (`AccountItemActivity`) выбирают валюту через существующий `CurrencyPickerActivity` вместо `AlertDialog`.
- Заголовок экрана выбора — «Выберите валюту» (текущая строка `account_choose_currency`); текущая валюта кошелька отмечена в списке.
- Новый диалог не вводится, `CurrencyPickerActivity` не меняется по существу.

## Capabilities

### New Capabilities

Нет.

### Modified Capabilities
- `currency-directory`: добавляется требование к выбору валюты кошелька.

## Impact

- Код: `Activities/AddWalletActivity.java` (`pickCurrencyClick`, `onActivityResult`), `Activities/AccountItemActivity.java` (`pickCurrencyClick`, `onActivityResult`).
- Другие сервисы не затрагиваются.
