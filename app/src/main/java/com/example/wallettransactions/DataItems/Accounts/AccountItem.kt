package com.example.wallettransactions.DataItems.Accounts

import com.example.wallettransactions.DataItems.IFilterIsLike
import com.example.wallettransactions.DataItems.Icon

open class AccountItem(
    val id: Int,
    name: String,
    comment: String?,
    icon: Icon?,
    isArchived: Boolean,
    systemKind: SystemKind,
    kind: Kind
) : IFilterIsLike {

    /**
     * Явный признак системности счёта (v75, колонка {@code account.system_kind}) — заменяет
     * прежнее распознавание по имени ({@link #isSystemName}). Значения и их числовые коды
     * зафиксированы на сервере (see servers/), менять код существующего значения нельзя.
     */
    enum class SystemKind(val value: Int) {
        NONE(0),
        TRANSFER(1),
        TRANSFER_COMMISSION(2),
        REVALUATION(3);

        companion object {
            /** Безопасный дефолт {@link #NONE} на любое незнакомое значение — тот же приём, что {@code Icon.IconType.fromValue}. */
            @JvmStatic
            fun fromValue(value: Int): SystemKind {
                if (value == TRANSFER.value) return TRANSFER
                if (value == TRANSFER_COMMISSION.value) return TRANSFER_COMMISSION
                if (value == REVALUATION.value) return REVALUATION
                return NONE
            }
        }
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
    enum class Kind(val value: Int) {
        CATEGORY(0),
        WALLET(1);

        companion object {
            /** Безопасный дефолт {@link #CATEGORY} на любое незнакомое значение — тот же приём, что {@link SystemKind#fromValue}. */
            @JvmStatic
            fun fromValue(value: Int): Kind {
                return if (value == WALLET.value) WALLET else CATEGORY
            }
        }
    }

    /**
     * Вид категории (v92, колонка {@code account.category_kind}, openspec
     * category-kind-income-expense): «Статья расхода» при вводе операции предлагает
     * {@link #EXPENSE} и {@link #BOTH}, «Статья дохода» — {@link #INCOME} и {@link #BOTH}.
     * Числовые коды совпадают с серверными (wallet-api, спека category-kind), менять код
     * существующего значения нельзя. У кошелька и системной категории вид всегда
     * {@link #BOTH} и в интерфейсе не показывается.
     */
    enum class CategoryKind(val value: Int) {
        EXPENSE(0),
        INCOME(1),
        BOTH(2);

        companion object {
            /** Безопасный дефолт {@link #BOTH} на любое значение вне 0..2 — тот же приём, что {@link SystemKind#fromValue}. */
            @JvmStatic
            fun fromValue(value: Int): CategoryKind {
                if (value == EXPENSE.value) return EXPENSE
                if (value == INCOME.value) return INCOME
                return BOTH
            }
        }
    }

    /**
     * Хранимое имя — для правки, поиска по имени и синка. Для показа — {@link #getDisplayName}.
     */
    var name: String = name
        private set

    var comment: String? = comment
        private set

    var icon: Icon? = icon
        private set

    /**
     * Архив — признак только для интерфейса: такой счёт не предлагают при вводе операции
     * и прячут из списков выбора. В расчётах он участвует наравне с остальными — его
     * операции и остаток входят и в отчёты, и в карточку состояния денег. Иначе архивация
     * молча меняла бы итоги за прошлые периоды.
     */
    @get:JvmName("getIsArchived")
    var isArchived: Boolean = isArchived
        private set

    var systemKind: SystemKind = systemKind
        private set

    var kind: Kind = kind
        private set

    /** Вид категории; у кошелька и системной категории — {@link CategoryKind#BOTH}. */
    var categoryKind: CategoryKind = CategoryKind.BOTH
        private set

    /** Системный счёт: его нельзя ни удалить, ни переименовать. */
    val isSystem: Boolean
        get() = systemKind != SystemKind.NONE

    /** Системных категорий три, и объясняются они по-разному — см. AccountItemActivity. */
    val isSystemCommission: Boolean
        get() = systemKind == SystemKind.TRANSFER_COMMISSION

    /** Системная категория переоценки активов (capital-assets). */
    val isSystemRevaluation: Boolean
        get() = systemKind == SystemKind.REVALUATION

    /** Ставит загрузчик ({@code WalletDbLoader.LoadAccounts}) и экран правки после записи в БД. */
    fun setCategoryKind(categoryKind: CategoryKind?) {
        this.categoryKind = categoryKind ?: CategoryKind.BOTH
    }

    /**
     * Имя для показа (openspec android-i18n, spec ui-language): системные категории — на
     * языке интерфейса («Перевод», «Комиссия за перевод»), остальные — хранимое имя.
     * Хранимое {@code Transfer}/{@code Transfer commission} не меняется и уходит в синк.
     */
    val displayName: String
        get() {
            var c: android.content.Context? = appContext
            if (c == null) return name
            c = com.example.wallettransactions.AppLanguage.localized(c)
            return when (systemKind) {
                SystemKind.TRANSFER ->
                    c.getString(com.example.wallettransactions.R.string.system_category_transfer)
                SystemKind.TRANSFER_COMMISSION ->
                    c.getString(com.example.wallettransactions.R.string.system_category_transfer_commission)
                // Хранимое имя («Переоценка» или запасное «Переоценка активов») — данные
                // синка; показ — на языке интерфейса, как у Transfer.
                SystemKind.REVALUATION ->
                    c.getString(com.example.wallettransactions.R.string.system_category_revaluation)
                else -> name
            }
        }

    /** Флаг архива в кэше — после записи флага в БД в обход {@code AccountList} (авто-архив займа). */
    fun setIsArchived(archived: Boolean) {
        this.isArchived = archived
    }

    /** Источник истины — {@link #kind}, не тип объекта (см. javadoc {@link Kind}). */
    val isOperatingAccount: Boolean
        get() = kind == Kind.WALLET

    /**
     * @deprecated не проставляет ни {@code system_kind} (принимает {@link SystemKind#NONE}),
     * ни {@code kind} (принимает {@link Kind#CATEGORY}) — годится только для мест, где строки
     * в {@code account} на самом деле нет (временный объект в UI-логике). Всё, что строится из
     * курсора БД, обязано передавать реальные значения через
     * {@link #AccountItem(Integer, String, String, Icon, boolean, SystemKind, Kind)}.
     */
    @Deprecated("см. javadoc: годится только для временных объектов без строки в account") // not UI: сообщение разработчику
    constructor(id: Int, name: String, comment: String?, icon: Icon?, isArchived: Boolean) :
        this(id, name, comment, icon, isArchived, SystemKind.NONE, Kind.CATEGORY)

    fun update(
        name: String, comment: String?, icon: Icon?, isArchived: Boolean,
        systemKind: SystemKind, kind: Kind
    ) {
        this.name = name
        this.comment = comment
        this.icon = icon
        this.isArchived = isArchived
        this.systemKind = systemKind
        this.kind = kind
    }

    override fun isLike(value: String): Boolean {
        return value.isEmpty() || name.lowercase(java.util.Locale.getDefault())
            .matches(Regex(".*" + value.lowercase(java.util.Locale.getDefault()) + ".*"))
    }

    companion object {
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
        const val SYSTEM_TRANSFER_NAME: String = "Transfer"

        /**
         * Категория комиссии за перевод — вторая системная: на неё по умолчанию уходит
         * проводка комиссии ({@code TransactionActivity.saveCommission}). С v75 находится
         * по {@code system_kind}, а не по имени (см. {@link SystemKind}) — переименование
         * больше не ломает подстановку. Заменить её на свою в конкретном переводе можно:
         * это выбор счёта, а не правка справочника.
         */
        const val SYSTEM_COMMISSION_NAME: String = "Transfer commission"

        /**
         * Третья системная категория — «Переоценка» капитального актива (v88, openspec
         * capital-assets, design п. 4): через неё проводится разница между новой оценкой актива и
         * его балансом. Проводки с ней не входят ни в доход, ни в расход (колонка
         * {@code is_revaluation} вьюх). Заводится лениво при первой переоценке; если обычная
         * категория с этим именем уже есть, берётся {@link #SYSTEM_REVALUATION_FALLBACK_NAME}.
         */
        const val SYSTEM_REVALUATION_NAME: String = "Переоценка" // not UI: хранимое имя, уходит в синк
        const val SYSTEM_REVALUATION_FALLBACK_NAME: String = "Переоценка активов" // not UI

        /** Ресурсы приложения для {@link #getDisplayName}; ставит {@code WalletApplication.onCreate}. */
        @Volatile
        private var appContext: android.content.Context? = null

        @JvmStatic
        fun initDisplayNames(context: android.content.Context) {
            appContext = context.applicationContext
        }

        /**
         * Сравнение по имени — источник правды ДО v75 и теперь оставлен только ради двух
         * легитимных вызовов: бэкфилл {@code system_kind} на миграции ({@code WalletDbHelper}
         * v75) и при первом сидировании справочника ({@code WalletFillData}) — это ровно
         * момент превращения старого неявного состояния (имя) в новое явное ({@code system_kind}).
         * Новых вызовов добавлять нельзя — определять системность нужно через {@link #isSystem()}/
         * {@link #isSystemCommission()} или напрямую через {@code system_kind}.
         */
        @JvmStatic
        fun isSystemName(name: String?): Boolean {
            return isNamed(SYSTEM_TRANSFER_NAME, name) || isNamed(SYSTEM_COMMISSION_NAME, name)
        }

        private fun isNamed(systemName: String, name: String?): Boolean {
            return systemName.equals(name?.trim(), ignoreCase = true)
        }
    }
}
