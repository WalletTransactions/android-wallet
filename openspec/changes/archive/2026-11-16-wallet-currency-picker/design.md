## Context

`CurrencyPickerActivity` умеет режим выбора (`buildIntent(ctx, title, currentIso)` → `RESULT_ISO`) и просмотра. Источник валют — `Singleton.getCurrencyList` (все валюты справочника), он же в `AddWalletActivity` и `AccountItemActivity`. Сейчас оба экрана собирают `String[] items` и показывают `AlertDialog.setItems`. Серверы не затрагиваются; всё локально.

## Goals / Non-Goals

**Goals:** один экран выбора валюты в приложении; текущая валюта отмечена.

**Non-Goals:** менять сам `CurrencyPickerActivity`, правила списка («Показывать в эквиваленте» не фильтрует его), смену валюты существующего кошелька с операциями (`UpdateAccountCurrency` как есть).

## Decisions

1. **Запуск через `startActivityForResult`.** Оба экрана вызывают `CurrencyPickerActivity.buildIntent(this, getString(R.string.account_choose_currency), currentIso)` с отдельным `REQUEST_PICK_CURRENCY` (в `AddWalletActivity` сейчас единственный код — `REQUEST_PICK_ICON = 1`, в `AccountItemActivity` заняты 2 и 3).
2. **Результат.** Из `RESULT_ISO` ищем валюту в `CurrencyList` по ISO (цикл по списку или готовый поиск, если он есть) и выполняем то, что делал `setItems`: в `AddWalletActivity` — флаг и подпись; в `AccountItemActivity` — `refreshCurrencyUI()` и `updateSaveButton()`.
3. **Фильтрация не нужна.** Список всех валют уже показывает и скрытые (в адаптере они помечены).
4. **Кнопки «создать валюту» и правки строки** на экране выбора остаются как в «Валютных парах»: новая валюта сразу выбирается из списка. Отдельный режим «только выбор» не вводим.
5. **`onActivityResult` в `AddWalletActivity`** сейчас выходит при `requestCode != REQUEST_PICK_ICON` — условие нужно разделить по кодам, иначе результат валюты потеряется.

## Risks / Trade-offs

- [Пользователь может создать валюту прямо при выборе, она сразу становится доступной кошельку] → это поведение «Валютных пар», считаем допустимым.
- [Смена валюты кошелька с операциями] → без изменений, ограничения те же.

## Migration Plan

Только клиентский релиз. Откат — вернуть `AlertDialog`.

## Open Questions

Нет.
