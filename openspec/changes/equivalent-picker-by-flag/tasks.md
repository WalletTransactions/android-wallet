# Tasks

## 1. Пикер шапки

- [x] 1.1 `BaseTabActivity.onPickCurrencyEq` — `EquivalentCurrencies.filter` по всему справочнику; `forHeader` и `currenciesInUse` удалены
- [x] 1.2 `EquivalentCurrenciesTest`: валюта с флагом без кошелька предлагается (`flaggedCurrencyWithoutWalletOffered`)
- [ ] 1.3 Проверка на устройстве: валюта без кошелька с флагом есть в пикере шапки «Операций»; снять флаг — пропала; включить — снова есть

## 2. Флаг новой валюты

- [x] 2.1 `EquivalentCurrencies.defaultForNew` (выключен, кроме пустого справочника), `CurrencyFormActivity` берёт его для новой валюты; тест `newCurrencyFlagOffExceptFirst`
- [ ] 2.2 Проверка на устройстве: «+ Создать валюту» открывается с выключенным «В эквиваленте»; сохранённая валюта не появляется в пикерах эквивалента, пока флаг не включить
