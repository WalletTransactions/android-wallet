package com.example.wallettransactions.Data;

import android.provider.BaseColumns;

public class WalletContract {

    private WalletContract() {
    };

    public static final class OperationEntry implements BaseColumns {
        public final static String TABLE_NAME = "operation";

        public final static String _ID = BaseColumns._ID;
        public static final String COLUMN_DATE = "date";
        public final static String COLUMN_FROM = "acc_fr_id";
        public final static String COLUMN_TO = "acc_to_id";
        public final static String COLUMN_AMOUNT = "amount";
        public final static String COLUMN_COMMENT = "comment";
        public final static String COLUMN_UUID = "uuid";
        public final static String COLUMN_DIRTY = "dirty";
        public final static String COLUMN_DELETED_AT = "deleted_at";
        public final static String COLUMN_UPDATED_BY_DEVICE = "updated_by_device";
    }

    /**
     * Связь проводок, которые пользователь завёл одной операцией, а база хранит
     * несколькими: перевод между разновалютными кошельками (две ноги через категорию
     * «Transfer») и комиссия за перевод.
     *
     * {@code main_id} — всегда проводка списания с кошелька-источника; к ней цепляются
     * и вторая нога, и комиссия. Так по любой из проводок находится вся операция.
     */
    public static final class OperationLinkEntry {
        public final static String TABLE_NAME = "operation_link";

        public final static String COLUMN_MAIN_ID = "main_id";
        public final static String COLUMN_LINK_ID = "link_id";
        /**
         * v77 — явная роль связки (0=нога валютного перевода, 1=комиссия, см.
         * {@code OperationItem.LinkType}), заменяет прежний вывод по {@code system_kind}
         * счёта-источника.
         */
        public final static String COLUMN_TYPE = "type";
        public final static String COLUMN_UUID = "uuid";
        public final static String COLUMN_DIRTY = "dirty";
        public final static String COLUMN_DELETED_AT = "deleted_at";
        public final static String COLUMN_UPDATED_BY_DEVICE = "updated_by_device";
    }

    /**
     * v79 — правила повторяющихся операций (экран «Ещё» → «Повторяющиеся операции»).
     * Сами по себе в ленту не попадают: по расписанию из них заводятся обычные строки
     * {@link OperationEntry} (см. {@code RecurringOperationsDb.generateDue}).
     * <p>
     * Только локальная таблица: колонок синка (uuid/dirty/deleted_at) нет, сервер о ней
     * не знает — синхронизируются уже созданные по правилу операции.
     */
    public static final class RecurringOperationEntry implements BaseColumns {
        public final static String TABLE_NAME = "recurring_operation";

        public final static String _ID = BaseColumns._ID;
        public final static String COLUMN_FROM = "acc_fr_id";
        public final static String COLUMN_TO = "acc_to_id";
        public final static String COLUMN_AMOUNT = "amount";
        public final static String COLUMN_COMMENT = "comment";
        /** 0=ежедневно, 1=еженедельно, 2=ежемесячно, 3=ежегодно — см. {@code RecurringOperation.Frequency}. */
        public final static String COLUMN_FREQUENCY = "frequency";
        /** Шаг повтора: «каждые N недель». У остальных частот всегда 1. */
        public final static String COLUMN_INTERVAL = "interval";
        /** Дни недели еженедельного повтора, битовая маска: пн=1, вт=2, … вс=64. */
        public final static String COLUMN_WEEK_DAYS = "week_days";
        /** С какой даты повторять, "yyyy-MM-dd HH:mm" — время берут созданные операции. */
        public final static String COLUMN_START_DATE = "start_date";
        /** До какой даты включительно, "yyyy-MM-dd"; NULL — «всегда». */
        public final static String COLUMN_END_DATE = "end_date";
        public final static String COLUMN_IS_ACTIVE = "is_active";
        /** Дата последней уже созданной операции, "yyyy-MM-dd HH:mm"; NULL — ещё ни одной. */
        public final static String COLUMN_LAST_DATE = "last_date";
    }

    public static class AccountEntry implements BaseColumns {
        public final static String TABLE_NAME = "account";

        public final static String _ID = BaseColumns._ID;
        public final static String COLUMN_NAME = "name";
        public final static String COLUMN_COMMENT = "comment";
        public final static String COLUMN_ICON_ID = "icon_id";
        public final static String COLUMN_IS_ARCHIVED = "is_archived";
        /**
         * v75 — явный признак системности (0=обычная, 1=Transfer, 2=Transfer commission,
         * см. {@code AccountItem.SystemKind}), заменяет прежнее распознавание по имени
         * ({@code AccountItem.isSystemName}).
         */
        public final static String COLUMN_SYSTEM_KIND = "system_kind";
        /**
         * v76 — явный признак «кошелёк или категория» (0=категория, 1=кошелёк, см.
         * {@code AccountItem.Kind}), дублирует наличие строки в {@code account_operating}
         * явным полем. Проставляется один раз при создании строки и дальше не меняется.
         */
        public final static String COLUMN_KIND = "kind";
        public final static String COLUMN_UUID = "uuid";
        public final static String COLUMN_DIRTY = "dirty";
        public final static String COLUMN_DELETED_AT = "deleted_at";
        public final static String COLUMN_UPDATED_BY_DEVICE = "updated_by_device";
    }

    public static final class AccountOperatingEntry implements BaseColumns {
        public final static String TABLE_NAME = "account_operating";

        public final static String _ID = BaseColumns._ID;
        public final static String COLUMN_CURRENCY_ID = "currency_id";
        public final static String COLUMN_UUID = "uuid";
        public final static String COLUMN_DIRTY = "dirty";
        public final static String COLUMN_DELETED_AT = "deleted_at";
        public final static String COLUMN_UPDATED_BY_DEVICE = "updated_by_device";
    }

    public static final class CurrencyEntry implements BaseColumns {
        public final static String TABLE_NAME = "currency";

        public final static String _ID = BaseColumns._ID;
        public final static String COLUMN_NAME = "name";
        public final static String COLUMN_ISO_NAME = "iso_code";
        public final static String COLUMN_ICON_ID = "icon_id";
        public final static String COLUMN_SYMBOL = "symbol";
        public final static String COLUMN_PRECISION = "precision";
        /** v85: предлагать ли валюту в пикерах эквивалента; синкается ({@code show_in_equivalent}). */
        public final static String COLUMN_SHOW_IN_EQUIVALENT = "show_in_equivalent";
        /** v85: 1 — строка из общего справочника сервера ({@code ledger_id} пуст в pull), локальная. */
        public final static String COLUMN_IS_SHARED = "is_shared";
        public final static String COLUMN_UUID = "uuid";
        public final static String COLUMN_DIRTY = "dirty";
        public final static String COLUMN_DELETED_AT = "deleted_at";
        public final static String COLUMN_UPDATED_BY_DEVICE = "updated_by_device";
    }

    public static final class IconEntry implements BaseColumns {
        public final static String TABLE_NAME = "icon";

        public final static String _ID = BaseColumns._ID;
        public final static String COLUMN_NAME = "name";
        public final static String COLUMN_IMAGE = "image";
        public final static String COLUMN_ICON_TYPE = "icon_type";
        public final static String COLUMN_UUID = "uuid";
        public final static String COLUMN_DIRTY = "dirty";
        public final static String COLUMN_DELETED_AT = "deleted_at";
        public final static String COLUMN_UPDATED_BY_DEVICE = "updated_by_device";
        /**
         * v86 (openspec server-managed-icons): откуда иконка — {@link #ORIGIN_SERVER}
         * (пришла через pull или ушла на сервер прежней версией) или {@link #ORIGIN_LOCAL}
         * (своя, на сервер не отправляется).
         */
        public final static String COLUMN_ORIGIN = "origin";
        public final static int ORIGIN_SERVER = 0;
        public final static int ORIGIN_LOCAL = 1;
    }

    public static final class CurrencyConversionEntry implements BaseColumns {
        public final static String TABLE_NAME = "currency_conversion";

        public final static String _ID = BaseColumns._ID;
        public final static String COLUMN_FROM_CURRENCY_ID = "from_currency_id";
        public final static String COLUMN_TO_CURRENCY_ID = "to_currency_id";
        public final static String COLUMN_NUM = "num";
        public final static String COLUMN_CURRENCY_PAIR_ID = "currency_pair_id";
        public final static String COLUMN_IS_REVERSE = "is_reverse";
        public final static String COLUMN_UUID = "uuid";
        public final static String COLUMN_DIRTY = "dirty";
        public final static String COLUMN_DELETED_AT = "deleted_at";
        public final static String COLUMN_UPDATED_BY_DEVICE = "updated_by_device";
    }

    public static final class CurrencyPairEntry implements BaseColumns {
        public final static String TABLE_NAME = "currency_pair";

        public final static String _ID = BaseColumns._ID;
        public final static String COLUMN_SYMBOL = "symbol";
        public final static String COLUMN_BASE_CURRECNCY_ID = "base_currency_id";
        public final static String COLUMN_QUOTE_CURRECNCY_ID = "quote_currency_id";
        public final static String COLUMN_PRECISION = "precision";
        /** v85: 1 — пара из общего справочника сервера ({@code ledger_id} пуст в pull), локальная. */
        public final static String COLUMN_IS_SHARED = "is_shared";
        public final static String COLUMN_UUID = "uuid";
        public final static String COLUMN_DIRTY = "dirty";
        public final static String COLUMN_DELETED_AT = "deleted_at";
        public final static String COLUMN_UPDATED_BY_DEVICE = "updated_by_device";
    }

    public static final class CurrencyPairRateEntry implements BaseColumns {
        public final static String TABLE_NAME = "currency_pair_rate";

        public final static String _ID = BaseColumns._ID;

        public final static String COLUMN_CURRENCY_PAIR_RATE = "currency_pair_id";
        public final static String COLUMN_DATE = "date";
        public final static String COLUMN_RATE = "rate";
        public final static String COLUMN_UUID = "uuid";
        public final static String COLUMN_DIRTY = "dirty";
        public final static String COLUMN_DELETED_AT = "deleted_at";
        public final static String COLUMN_UPDATED_BY_DEVICE = "updated_by_device";
    }

}
