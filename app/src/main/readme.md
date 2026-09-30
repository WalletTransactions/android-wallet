# Как применить экран «Операции» в проекте

Папка `Android` подключена только на чтение, поэтому файлы лежат здесь.
Скопируйте их поверх `Android/app/src/main/res/`, сохраняя структуру.

| Файл | Что делает |
| --- | --- |
| `res/values/colors.xml` | Новая палитра. Имена ролей прежние, значения заменены — остальные экраны сразу становятся теплее, ничего не ломая. |
| `res/drawable/bg_card.xml` | Карточка баланса, радиус 24, без рамки. |
| `res/drawable/bg_card_row.xml` | Карточка строки списка, радиус 20. |
| `res/drawable/bg_pill_primary.xml` | Основная кнопка-пилюля. |
| `res/drawable/bg_pill_secondary.xml` | Вторичная пилюля: период, кнопка фильтра. |
| `res/drawable/bg_pill_outline.xml` | Обводная пилюля. |
| `res/drawable/bg_pill_light.xml` | Круглые стрелки внутри пилюли периода. |
| `res/drawable/bg_icon_circle.xml` | Круг 44dp под иконкой категории. |
| `res/layout/activity_transaction_list.xml` | Экран «Операции». |
| `res/layout/transaction_item_layout.xml` | Строка операции. |
| `res/layout/fragment_choose_date_period.xml` | Период в пилюле. |
| `res/layout/fragment_choose_date.xml` | Одна дата. |
| `res/layout/view_bottom_nav.xml` | Нижняя панель: Операции · Кошельки · «+» · Отчёты · Ещё. |
| `res/drawable/bg_bottom_nav.xml` | Фон панели с верхней разделительной линией. |
| `res/drawable/bg_nav_add.xml`, `ic_nav_add.xml` | Круглая центральная кнопка «+». |
| `res/drawable/bg_nav_tab.xml` | Прозрачный ripple для табов. |
| `res/drawable/ic_nav_ops.xml`, `ic_nav_wallets.xml`, `ic_nav_report.xml`, `ic_nav_more.xml` | Иконки табов. |

## Java менять не обязательно

Все id сохранены: `drawer_layout`, `toolbar`, `scrollable`, `fragmentDataPeriod`,
`balanceState`, `openingBalance`, `endingBalance`, `totalProfitBalance`, `btnFilter`,
`listView`, `btnAddTran`, `btnUp`, `nav_view`; обработчики `btnChangeFilter`,
`btnOperationAdd`, `btnUp`, `btnReportClick` тоже на месте.

`totalProfitBalance` теперь подписан «Итог» — это та же величина, что и раньше.

## Что добавить, чтобы карточка заполнялась целиком

В карточке появились две новые ячейки — «Доход» и «Расход». В `MainActivity`
рядом с существующими `findViewById`:

```java
TextView incomeSum  = findViewById(R.id.incomeSum);
TextView expenseSum = findViewById(R.id.expenseSum);
```

и там же, где считается `totalProfitBalance`, проставить сумму доходов и расходов
за период. Пока они не заданы, ячейки показывают значения из макета.

Кнопка «View report» из карточки убрана — отчёт открывается из меню
`action_report_byaccounts`. Если она нужна на экране, верните её как
`bg_pill_primary` под тремя колонками.

## Шрифты

Caprasimo и Figtree в макете подставлены как `textStyle="bold"` на системном
шрифте — файлов шрифтов в репозитории нет. Чтобы совпало с макетом, положите
`caprasimo.ttf` и `figtree.ttf` в `res/font/` и добавьте
`android:fontFamily="@font/figtree"` в теме, а `@font/caprasimo` — на
`endingBalance` и заголовки экранов.

## Нижняя панель

Панель — отдельный layout, подключается одной строкой в корневой
`RelativeLayout` / `FrameLayout` любого экрана:

```xml
<include layout="@layout/view_bottom_nav"/>
```

В `activity_transaction_list.xml` она уже вписана. Содержимое экрана при этом
ставится `android:layout_above="@+id/bottomNav"`, чтобы список не уезжал под
панель, а `btnUp` поднят над ней.

Кнопка «+» — это тот же `btnAddTran` с обработчиком `btnOperationAdd`, только
теперь она сидит в центре панели, а не плавает над списком. Java менять не нужно.

Четыре таба вызывают методы activity — их надо добавить в каждый экран, где
подключена панель (или в общий `BaseActivity`):

```java
public void navOperations(View v) { open(MainActivity.class); }
public void navWallets(View v)    { open(AccountActivity.class); }
public void navReport(View v)     { open(ReportActivity.class); }
public void navMore(View v)       { open(MoreActivity.class); }

private void open(Class<?> c) {
    if (getClass() == c) return;
    startActivity(new Intent(this, c).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    overridePendingTransition(0, 0);
}
```

Активный таб в макете подсвечен для «Операций» (`colorPrimary`, остальные —
`colorTextHint`). На других экранах перекрасьте нужную пару из кода:

```java
void setActiveTab(int tabId) {
    int[] ids = {R.id.navOps, R.id.navWallets, R.id.navReport, R.id.navMore};
    for (int id : ids) {
        ViewGroup t = findViewById(id);
        int c = getResources().getColor(id == tabId ? R.color.colorPrimary : R.color.colorTextHint);
        ((ImageView) t.getChildAt(0)).setColorFilter(c);
        ((TextView)  t.getChildAt(1)).setTextColor(c);
    }
}
```

### Где панель не нужна

Экраны, на которые заходят «вглубь», панель и «+» не показывают: добавление и
редактирование операции, фильтр, выбор категорий и кошельков, настройки,
курсы валют и карточка пары, импорт/экспорт, редактор иконок. Там просто не
подключайте `view_bottom_nav`.

Боковой drawer в экране операций скрыт (`nav_view` с `visibility="gone"`) —
его пункты переехали в «Ещё». Удалять `main_drawer_menu.xml` не нужно.

## Новые экраны: Настройки, Курсы валют, Список валют, Фильтр

Этих экранов не было в исходном приложении — макетов для них в `Android/`
нет, поэтому ниже новые layout-файлы с нуля, в той же дизайн-системе. Их
нужно завести как новые Activity/BottomSheetDialogFragment; id в примерах
ниже — предлагаемые, при подключении к реальному коду переименуйте под свои
обработчики.

| Файл | Экран |
| --- | --- |
| `res/layout/activity_settings.xml` | «Настройки» (из «Ещё»). Валюта эквивалента, код доступа + отпечаток (зависимый тоггл), напоминание об операциях, оценка приложения, версия. |
| `res/layout/activity_currencypair_list.xml` + `pair_item_layout.xml` | «Курсы валют». Кнопка «Валюта ⌄» разворачивает поиск и чипы валют (`currencyFilterPanel`, скрыт по умолчанию) — так 40 валют не растягивают экран. |
| `res/layout/dialog_edit_rate.xml` | Нижний лист добавления/редактирования курса пары: Дата, Курс, Удалить/Отмена/Сохранить. |
| `res/layout/dialog_download_rates.xml` | Нижний лист «Загрузка котировок»: Источник, Период с/по, Объём (день/понедельник/месяц). |
| `res/layout/dialog_new_pair.xml` | Нижний лист «Новая валютная пара»: Базовая и Котируемая валюта (открывают `activity_currency_picker.xml`), Точность — числовое поле, по умолчанию 5. |
| `res/layout/activity_currency_picker.xml` + `currency_picker_item_layout.xml` | «Список валют» — общий экран выбора одной валюты с поиском, использует `pickerTitle` для заголовка («Базовая валюта» / «Котируемая валюта»). |
| `res/layout/activity_filter.xml` | «Фильтр» операций (кнопка ⛭ в шапке «Операций»): поиск по тексту, тип операции, период, счета затрат/кошельки (открывают отдельные экраны множественного выбора), суммы в валюте кошелька и в эквиваленте. |

Список валют (`assets/currencies.json` из ранее выгруженного архива — код и
название) — единственный источник для трёх мест: пикер в `dialog_new_pair`,
фильтр на «Курсы валют» и, при необходимости, любой другой выбор валюты.
Не дублируйте список по экранам.

Новые drawable: тумблеры `bg_switch_on/off` + `bg_switch_thumb` (двигать
`View` кодом через `translationX` или `layout_gravity`), радио `bg_radio_dot`
+ `bg_radio_dot_filled` (и `android:button`-вариант `bg_radio_dot_native` для
нативных `RadioButton`), поле `bg_input_pill`, пунктирная пилюля
`bg_pill_dashed`, нижний лист `bg_sheet_top`, чипы `bg_chip_selected` /
`bg_chip_unselected`. Стили `SettingsGroupLabel` (подпись группы настроек) и
`FilterChip` — в `res/values/styles_screens.xml` и `styles_filter_chip.xml`.

Эти четыре экрана и все диалоги — экраны «вглубь», нижнюю панель
(`view_bottom_nav`) на них не подключайте.

## Экран «Кошельки» — та же шапка и тот же фильтр, что на «Операциях»

`res/layout/activity_account.xml` заменяет старые вкладки Затраты/Счета/Доходы:
теперь это таб «Кошельки» нижней панели (`navWallets`), с точно такой же шапкой,
как на «Операциях» — заголовок + `btnFilter` (открывает `activity_filter.xml`
через тот же `btnChangeFilter`) + поиск, — и с тем же периодом
(`fragment_choose_date_period`) и карточкой баланса (`balanceState`,
`openingBalance`, `endingBalance`, `incomeSum`, `expenseSum`,
`totalProfitBalance` — id как на «Операциях», их можно считать одной функцией).

Фильтр общий: список кошельков (`listWallets`, строка —
`wallet_balance_item_layout.xml`) считается по тем же отфильтрованным
операциям, что и лента на «Операциях». `filterBanner` показывает то же
резюме фильтра и `btnClearFilter`, скрыт по умолчанию (`visibility="gone"`).

Кошельки отсортированы по наибольшему изменению за период сверху
(`walletNet`); показываются только ненулевые. Строка кошелька — сумма и
эквивалент на начало (`walletOpenNative`/`walletOpenEq`) и на конец
(`walletCloseNative`/`walletCloseEq`) периода.

Новые иконки: `ic_filter.xml`, `ic_search.xml`.

## Экран «Ещё»

`res/layout/activity_more.xml` — таб нижней панели (`navMore`), панель подключена.
Пункты сгруппированы в одну карточку (`moreList`): Курсы валют, Иконки и
категории, Импорт из файла, Экспорт базы, Баланс кошельков на даты, Настройки
(открывает `activity_settings.xml`) — каждая строка через стиль `MoreRow` из
`styles_more.xml`. Ниже — `btnDeleteAll`, обводная кнопка на `colorexpanse`.

Если пункты «Ещё» удобнее держать списком (ListView/RecyclerView) вместо
фиксированной верстки — используйте `more_item_layout.xml` как строку
адаптера, стили те же.
