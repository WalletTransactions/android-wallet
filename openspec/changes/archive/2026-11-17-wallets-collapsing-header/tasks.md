## 1. Раскладка «Кошельков»

- [x] 1.1 `activity_account.xml`: сводка периода (`view_period_summary`, `scrollFlags="scroll"`) и `walletsFilterBlock` (`scrollFlags="scroll"`) — внутрь `AppBarLayout` `summaryBar` в `CoordinatorLayout` `scrollable`; `ListView` `listWallets` — `match_parent`, `nestedScrollingEnabled`, `appbar_scrolling_view_behavior`; `headerRow` остаётся вне AppBar
- [x] 1.2 `AccountActivity.reloadTabData`: при пустом `walletRows` — `summaryBar.setExpanded(true, false)`

## 2. Проверка

- [x] 2.1 Сборка `bash deploy-debug.sh` (prod и qa) проходит, приложение установлено на Pixel 7 Pro
- [x] 2.2 Huawei P20 Pro (qa-сборка, русская локаль): прокрутка вниз сворачивает сводку и чипы, заголовок с кнопками остаётся; прокрутка вверх возвращает шапку целиком
- [x] 2.3 Ещё на Huawei: чипы секций и «Показывать архивные» переключаются при развёрнутой шапке, тап по строке открывает «Операции» с фильтром, период без кошельков (2011 г.) показывает шапку целиком при пустом списке, английская локаль — тексты и чипы без переносов, шапка сворачивается целиком. Не проверено: пустой список сразу после свёрнутой шапки (шапка была развёрнута до смены периода)
