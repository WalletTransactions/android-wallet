# Как применить экран «Операции» в проекте

Папка `Android` подключена только на чтение, поэтому файлы лежат здесь.
Скопируйте их поверх `android-wallet/app/src/main/res/`, сохраняя структуру.

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
