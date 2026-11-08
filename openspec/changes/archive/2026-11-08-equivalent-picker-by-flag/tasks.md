# Tasks

## 1. Пикер шапки

- [x] 1.1 `BaseTabActivity.onPickCurrencyEq` — `EquivalentCurrencies.filter` по всему справочнику; `forHeader` и `currenciesInUse` удалены
- [x] 1.2 `EquivalentCurrenciesTest`: валюта с флагом без кошелька предлагается (`flaggedCurrencyWithoutWalletOffered`)
- [x] 1.3 Проверка на устройстве: валюта без кошелька с флагом есть в пикере шапки «Операций»; снять флаг — пропала; включить — снова есть — Тест: [android-wallet#31](https://github.com/WalletTransactions/android-wallet/issues/31) — проверено 2026-11-08: Pixel 8 Pro, QA 1.0.117; валюта «Ннью» (НН) без кошелька выбрана эквивалентом шапки (`currencyEqIso=НН`), флаг включён; снятие/включение флага — по отметке владельца (следа не оставляет)

## 2. Флаг новой валюты

- [x] 2.1 `EquivalentCurrencies.defaultForNew` (выключен, кроме пустого справочника), `CurrencyFormActivity` берёт его для новой валюты; тест `newCurrencyFlagOffExceptFirst`
- [ ] 2.2 Проверка на устройстве: «+ Создать валюту» открывается с выключенным «В эквиваленте»; сохранённая валюта не появляется в пикерах эквивалента, пока флаг не включить — Тест: [android-wallet#31](https://github.com/WalletTransactions/android-wallet/issues/31) — проверено 2026-11-08: Pixel 8 Pro, валюта «Ннью» создана на телефоне 2026-11-07 в окне теста, ушла на test-контур push seq 104; выключенный флаг в новой форме — по отметке владельца
