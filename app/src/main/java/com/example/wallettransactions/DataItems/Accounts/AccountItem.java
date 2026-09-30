package com.example.wallettransactions.DataItems.Accounts;

import com.example.wallettransactions.DataItems.IFilterIsLike;
import com.example.wallettransactions.DataItems.Icon;

public class AccountItem implements IFilterIsLike {

    /**
     * Категория-мост «Transfer»: через неё проходит перевод между кошельками в разных
     * валютах — двумя проводками, потому что у операции одна сумма, а валют две
     * (см. MoneyLover_csv_Import/main.py). Такую категорию нельзя удалять: без неё
     * уже проведённые переводы теряют вторую сторону, а импорт заводит её заново.
     *
     * Системность с v75 хранится явно в колонке {@code account.system_kind} (см.
     * {@link SystemKind}), а не распознаётся по имени — так переименование системной
     * категории больше не обходит защиту от удаления/переименования. Эта константа
     * используется только как имя ПО УМОЛЧАНИЮ для новой строки, если системную
     * категорию приходится завести с нуля (см. {@code WalletDbCRUD.GetOrCreateSystemCategory}).
     */
    public static final String SYSTEM_TRANSFER_NAME = "Transfer";

    /**
     * Категория комиссии за перевод — вторая системная: на неё по умолчанию уходит
     * проводка комиссии ({@code TransactionActivity.saveCommission}). С v75 находится
     * по {@code system_kind}, а не по имени (см. {@link SystemKind}) — переименование
     * больше не ломает подстановку. Заменить её на свою в конкретном переводе можно:
     * это выбор счёта, а не правка справочника.
     */
    public static final String SYSTEM_COMMISSION_NAME = "Transfer commission";

    /**
     * Явный признак системности счёта (v75, колонка {@code account.system_kind}) — заменяет
     * прежнее распознавание по имени ({@link #isSystemName}). Значения и их числовые коды
     * зафиксированы на сервере (see servers/), менять код существующего значения нельзя.
     */
    public enum SystemKind {
        NONE(0),
        TRANSFER(1),
        TRANSFER_COMMISSION(2);

        private final int value;

        SystemKind(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        /** Безопасный дефолт {@link #NONE} на любое незнакомое значение — тот же приём, что {@code Icon.IconType.fromValue}. */
        public static SystemKind fromValue(int value) {
            if (value == TRANSFER.value) return TRANSFER;
            if (value == TRANSFER_COMMISSION.value) return TRANSFER_COMMISSION;
            return NONE;
        }
    }

    /**
     * Сравнение по имени — источник правды ДО v75 и теперь оставлен только ради двух
     * легитимных вызовов: бэкфилл {@code system_kind} на миграции ({@code WalletDbHelper}
     * v75) и при первом сидировании справочника ({@code WalletFillData}) — это ровно
     * момент превращения старого неявного состояния (имя) в новое явное ({@code system_kind}).
     * Новых вызовов добавлять нельзя — определять системность нужно через {@link #isSystem()}/
     * {@link #isSystemCommission()} или напрямую через {@code system_kind}.
     */
    public static boolean isSystemName(String name) {
        return isNamed(SYSTEM_TRANSFER_NAME, name) || isNamed(SYSTEM_COMMISSION_NAME, name);
    }

    private static boolean isNamed(String systemName, String name) {
        return systemName.equalsIgnoreCase(name == null ? null : name.trim());
    }

    /**
     * Явный признак «кошелёк или категория» (v76, колонка {@code account.kind}) — дублирует
     * наличие строки в {@code account_operating} явным полем на самой строке {@code account}.
     * Проставляется РОВНО ОДИН РАЗ, в момент создания строки (см. {@code WalletDbCRUD.InsertAccount}/
     * {@code InsertOperatingAccount}), и дальше не меняется: конвертации категории в кошелёк и
     * обратно в коде нет. {@link #isOperatingAccount()} читает именно это поле, а не тип объекта —
     * {@link AccountOperatingItem} остаётся отдельным Java-подтипом только ради полей, которых у
     * категории нет ({@code currency}/{@code CurrentBalance}), и всегда создаётся с {@code kind ==
     * WALLET} (см. {@code WalletDbLoader.LoadAccounts}).
     */
    public enum Kind {
        CATEGORY(0),
        WALLET(1);

        private final int value;

        Kind(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        /** Безопасный дефолт {@link #CATEGORY} на любое незнакомое значение — тот же приём, что {@link SystemKind#fromValue}. */
        public static Kind fromValue(int value) {
            return value == WALLET.value ? WALLET : CATEGORY;
        }
    }

    private Integer id;
    private String name;
    private String comment;
    private Icon icon;
    private Boolean isArchived;
    private SystemKind systemKind;
    private Kind kind;

    /** Системный счёт: его нельзя ни удалить, ни переименовать. */
    public boolean isSystem() {
        return systemKind != SystemKind.NONE;
    }

    /** Системных категорий две, и объясняются они по-разному — см. AccountItemActivity. */
    public boolean isSystemCommission() {
        return systemKind == SystemKind.TRANSFER_COMMISSION;
    }

    public SystemKind getSystemKind() {
        return systemKind;
    }

    public Kind getKind() {
        return kind;
    }


    public Integer getId(){
        return id;
    }

    public String getName(){
        return name;
    }

    public String getComment(){
        return comment;
    }

    public Icon getIcon(){
        return icon;
    }

    /**
     * Архив — признак только для интерфейса: такой счёт не предлагают при вводе операции
     * и прячут из списков выбора. В расчётах он участвует наравне с остальными — его
     * операции и остаток входят и в отчёты, и в карточку состояния денег. Иначе архивация
     * молча меняла бы итоги за прошлые периоды.
     */
    public Boolean getIsArchived(){return isArchived;}

    /** Источник истины — {@link #kind}, не тип объекта (см. javadoc {@link Kind}). */
    public boolean isOperatingAccount()
    {
        return kind == Kind.WALLET;
    };

    /**
     * @deprecated не проставляет ни {@code system_kind} (принимает {@link SystemKind#NONE}),
     * ни {@code kind} (принимает {@link Kind#CATEGORY}) — годится только для мест, где строки
     * в {@code account} на самом деле нет (временный объект в UI-логике). Всё, что строится из
     * курсора БД, обязано передавать реальные значения через
     * {@link #AccountItem(Integer, String, String, Icon, boolean, SystemKind, Kind)}.
     */
    @Deprecated
    public AccountItem(Integer id, String name, String comment, Icon icon, boolean isArchived){
        this(id, name, comment, icon, isArchived, SystemKind.NONE, Kind.CATEGORY);
    }

    public AccountItem(Integer id, String name, String comment, Icon icon, boolean isArchived,
                        SystemKind systemKind, Kind kind){
        this.id = id;
        this.name = name;
        this.comment = comment;
        this.icon = icon;
        this.isArchived = isArchived;
        this.systemKind = systemKind;
        this.kind = kind;
    }

    public void update(String name, String comment, Icon icon, boolean isArchived,
                        SystemKind systemKind, Kind kind){
        this.name = name;
        this.comment = comment;
        this.icon = icon;
        this.isArchived = isArchived;
        this.systemKind = systemKind;
        this.kind = kind;
    }

    public boolean isLike(String value)
    {
        return value.isEmpty()|| name.toLowerCase().matches(".*"+ value.toLowerCase()+".*");
    }

}
