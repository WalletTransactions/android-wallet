package com.example.wallettransactions.Data

import android.provider.BaseColumns

object WalletContract {
    object OperationEntry : BaseColumns {
        const val TABLE_NAME = "operation"

        const val _ID = BaseColumns._ID
        const val COLUMN_DATE = "date"
        const val COLUMN_FROM = "acc_fr_id"
        const val COLUMN_TO = "acc_to_id"
        const val COLUMN_AMOUNT = "amount"
        const val COLUMN_COMMENT = "comment"
        const val COLUMN_UUID = "uuid"
        const val COLUMN_DIRTY = "dirty"
        const val COLUMN_DELETED_AT = "deleted_at"
        const val COLUMN_UPDATED_BY_DEVICE = "updated_by_device"
    }

    /**
     * Связь проводок, которые пользователь завёл одной операцией, а база хранит
     * несколькими: перевод между разновалютными кошельками (две ноги через категорию
     * «Transfer») и комиссия за перевод.
     *
     * `main_id` — всегда проводка списания с кошелька-источника; к ней цепляются
     * и вторая нога, и комиссия. Так по любой из проводок находится вся операция.
     */
    object OperationLinkEntry {
        const val TABLE_NAME = "operation_link"

        const val COLUMN_MAIN_ID = "main_id"
        const val COLUMN_LINK_ID = "link_id"
        /**
         * v77 — явная роль связки (0=нога валютного перевода, 1=комиссия, см.
         * `OperationItem.LinkType`), заменяет прежний вывод по `system_kind`
         * счёта-источника.
         */
        const val COLUMN_TYPE = "type"
        const val COLUMN_UUID = "uuid"
        const val COLUMN_DIRTY = "dirty"
        const val COLUMN_DELETED_AT = "deleted_at"
        const val COLUMN_UPDATED_BY_DEVICE = "updated_by_device"
    }

    /**
     * v79 — правила повторяющихся операций (экран «Ещё» → «Повторяющиеся операции»).
     * Сами по себе в ленту не попадают: по расписанию из них заводятся обычные строки
     * [OperationEntry] (см. `RecurringOperationsDb.generateDue`).
     *
     * Только локальная таблица: колонок синка (uuid/dirty/deleted_at) нет, сервер о ней
     * не знает — синхронизируются уже созданные по правилу операции.
     */
    object RecurringOperationEntry : BaseColumns {
        const val TABLE_NAME = "recurring_operation"

        const val _ID = BaseColumns._ID
        const val COLUMN_FROM = "acc_fr_id"
        const val COLUMN_TO = "acc_to_id"
        const val COLUMN_AMOUNT = "amount"
        const val COLUMN_COMMENT = "comment"
        /** 0=ежедневно, 1=еженедельно, 2=ежемесячно, 3=ежегодно — см. `RecurringOperation.Frequency`. */
        const val COLUMN_FREQUENCY = "frequency"
        /** Шаг повтора: «каждые N недель». У остальных частот всегда 1. */
        const val COLUMN_INTERVAL = "interval"
        /** Дни недели еженедельного повтора, битовая маска: пн=1, вт=2, … вс=64. */
        const val COLUMN_WEEK_DAYS = "week_days"
        /** С какой даты повторять, "yyyy-MM-dd HH:mm" — время берут созданные операции. */
        const val COLUMN_START_DATE = "start_date"
        /** До какой даты включительно, "yyyy-MM-dd"; NULL — «всегда». */
        const val COLUMN_END_DATE = "end_date"
        const val COLUMN_IS_ACTIVE = "is_active"
        /** Дата последней уже созданной операции, "yyyy-MM-dd HH:mm"; NULL — ещё ни одной. */
        const val COLUMN_LAST_DATE = "last_date"
    }

    object AccountEntry : BaseColumns {
        const val TABLE_NAME = "account"

        const val _ID = BaseColumns._ID
        const val COLUMN_NAME = "name"
        const val COLUMN_COMMENT = "comment"
        const val COLUMN_ICON_ID = "icon_id"
        const val COLUMN_IS_ARCHIVED = "is_archived"
        /**
         * v75 — явный признак системности (0=обычная, 1=Transfer, 2=Transfer commission,
         * см. `AccountItem.SystemKind`), заменяет прежнее распознавание по имени
         * (`AccountItem.isSystemName`).
         */
        const val COLUMN_SYSTEM_KIND = "system_kind"
        /**
         * v76 — явный признак «кошелёк или категория» (0=категория, 1=кошелёк, см.
         * `AccountItem.Kind`), дублирует наличие строки в `account_operating`
         * явным полем. Проставляется один раз при создании строки и дальше не меняется.
         */
        const val COLUMN_KIND = "kind"
        /**
         * v92 (openspec category-kind-income-expense) — вид категории: 0=расход, 1=доход, 2=оба
         * (см. `AccountItem.CategoryKind`). У кошелька и системной категории всегда 2.
         * Разметку существующих категорий присылает сервер (wallet-api, миграция 000041),
         * локально по операциям она не вычисляется.
         */
        const val COLUMN_CATEGORY_KIND = "category_kind"
        /**
         * v88 (openspec new-user-default-set, design D3): 1 — строка заведена из встроенного
         * набора и пользователь её не менял (имя, иконка). Только локальная, в push не уходит.
         */
        const val COLUMN_FROM_SEED = "from_seed"
        const val COLUMN_UUID = "uuid"
        const val COLUMN_DIRTY = "dirty"
        const val COLUMN_DELETED_AT = "deleted_at"
        const val COLUMN_UPDATED_BY_DEVICE = "updated_by_device"
    }

    object AccountOperatingEntry : BaseColumns {
        const val TABLE_NAME = "account_operating"

        const val _ID = BaseColumns._ID
        const val COLUMN_CURRENCY_ID = "currency_id"
        const val COLUMN_UUID = "uuid"
        const val COLUMN_DIRTY = "dirty"
        const val COLUMN_DELETED_AT = "deleted_at"
        const val COLUMN_UPDATED_BY_DEVICE = "updated_by_device"
    }

    /**
     * Признак капитального актива у кошелька (v88, openspec capital-assets, design п. 1) —
     * по образцу [AccountOperatingEntry]: общий первичный ключ с `account`
     * (`_id` = `account._id`, `uuid` = `account.uuid`), строка есть
     * только у кошелька. Снятие признака — мягкое удаление (`deleted_at`), повторная
     * пометка оживляет ту же строку. Синхронизируется таблицей `account_asset`
     * (wallet-api, миграция 000033).
     */
    object AccountAssetEntry : BaseColumns {
        const val TABLE_NAME = "account_asset"
        const val _ID = BaseColumns._ID
        /** 1 — недвижимость, 2 — авто, 3 — прочее (CHECK на сервере). */
        const val COLUMN_ASSET_TYPE = "asset_type"
        /** 1 — актив входит во «Всего с капиталом». */
        const val COLUMN_INCLUDE_IN_NET_WORTH = "include_in_net_worth"
        const val COLUMN_COMMENT = "comment"
        const val COLUMN_UUID = "uuid"
        const val COLUMN_DIRTY = "dirty"
        const val COLUMN_DELETED_AT = "deleted_at"
        const val COLUMN_UPDATED_BY_DEVICE = "updated_by_device"

        const val TYPE_REAL_ESTATE = 1
        const val TYPE_VEHICLE = 2
        const val TYPE_OTHER = 3
    }

    /**
     * Контрагент долга (v91, openspec debts, design D1): человек или организация, с которой
     * есть займы. Общий для всех участников леджера, синкается таблицей `counterparty`
     * (wallet-api, миграция 000036). Имя уникально среди живых по
     * [AccountNames.keySql] — индекс `uq_counterparty_name`.
     */
    object CounterpartyEntry : BaseColumns {
        const val TABLE_NAME = "counterparty"
        const val _ID = BaseColumns._ID
        const val COLUMN_NAME = "name"
        const val COLUMN_COMMENT = "comment"
        /** uuid иконки или `null` — без иконки. */
        const val COLUMN_ICON_UUID = "icon_uuid"
        const val COLUMN_IS_ARCHIVED = "is_archived"
        const val COLUMN_UUID = "uuid"
        const val COLUMN_DIRTY = "dirty"
        const val COLUMN_DELETED_AT = "deleted_at"
        const val COLUMN_UPDATED_BY_DEVICE = "updated_by_device"
    }

    /**
     * Признак долгового кошелька (v91, openspec debts, design D1) — по образцу
     * [AccountAssetEntry]: `_id` = `account._id`, `uuid` =
     * `account.uuid`. Один заём — один кошелёк; знак баланса — направление долга
     * («+» мне должны, «−» я должен). Снятие признака — мягкое удаление, повторная
     * пометка оживляет ту же строку.
     */
    object AccountDebtEntry : BaseColumns {
        const val TABLE_NAME = "account_debt"
        const val _ID = BaseColumns._ID
        const val COLUMN_COUNTERPARTY_UUID = "counterparty_uuid"
        const val COLUMN_COMMENT = "comment"
        /** Срок возврата `yyyy-MM-dd` или `null`. */
        const val COLUMN_DUE_DATE = "due_date"
        const val COLUMN_UUID = "uuid"
        const val COLUMN_DIRTY = "dirty"
        const val COLUMN_DELETED_AT = "deleted_at"
        const val COLUMN_UPDATED_BY_DEVICE = "updated_by_device"
    }

    /**
     * Привязка контрагента к контакту телефона (v91, design D7) — только локальная:
     * без колонок синка и без dirty-триггера, на другом устройстве связь заводится заново.
     */
    object CounterpartyContactEntry {
        const val TABLE_NAME = "counterparty_contact"
        const val COLUMN_COUNTERPARTY_UUID = "counterparty_uuid"
        /** `ContactsContract.Contacts.LOOKUP_KEY` выбранного контакта. */
        const val COLUMN_LOOKUP_KEY = "lookup_key"
    }

    object CurrencyEntry : BaseColumns {
        const val TABLE_NAME = "currency"

        const val _ID = BaseColumns._ID
        const val COLUMN_NAME = "name"
        const val COLUMN_ISO_NAME = "iso_code"
        const val COLUMN_ICON_ID = "icon_id"
        const val COLUMN_SYMBOL = "symbol"
        const val COLUMN_PRECISION = "precision"
        /** v85: предлагать ли валюту в пикерах эквивалента; синкается (`show_in_equivalent`). */
        const val COLUMN_SHOW_IN_EQUIVALENT = "show_in_equivalent"
        /** v85: 1 — строка из общего справочника сервера (`ledger_id` пуст в pull), локальная. */
        const val COLUMN_IS_SHARED = "is_shared"
        /** v88: см. [AccountEntry.COLUMN_FROM_SEED]; снимается при правке имени, кода, символа, иконки. */
        const val COLUMN_FROM_SEED = "from_seed"
        const val COLUMN_UUID = "uuid"
        const val COLUMN_DIRTY = "dirty"
        const val COLUMN_DELETED_AT = "deleted_at"
        const val COLUMN_UPDATED_BY_DEVICE = "updated_by_device"
    }

    object IconEntry : BaseColumns {
        const val TABLE_NAME = "icon"

        const val _ID = BaseColumns._ID
        const val COLUMN_NAME = "name"
        const val COLUMN_IMAGE = "image"
        const val COLUMN_ICON_TYPE = "icon_type"
        const val COLUMN_UUID = "uuid"
        const val COLUMN_DIRTY = "dirty"
        const val COLUMN_DELETED_AT = "deleted_at"
        const val COLUMN_UPDATED_BY_DEVICE = "updated_by_device"
        /**
         * v86 (openspec server-managed-icons): откуда иконка — [.ORIGIN_SERVER]
         * (пришла через pull или ушла на сервер прежней версией) или [.ORIGIN_LOCAL]
         * (своя, на сервер не отправляется).
         */
        const val COLUMN_ORIGIN = "origin"
        const val ORIGIN_SERVER = 0
        const val ORIGIN_LOCAL = 1
    }

    object CurrencyConversionEntry : BaseColumns {
        const val TABLE_NAME = "currency_conversion"

        const val _ID = BaseColumns._ID
        const val COLUMN_FROM_CURRENCY_ID = "from_currency_id"
        const val COLUMN_TO_CURRENCY_ID = "to_currency_id"
        const val COLUMN_NUM = "num"
        const val COLUMN_CURRENCY_PAIR_ID = "currency_pair_id"
        const val COLUMN_IS_REVERSE = "is_reverse"
        const val COLUMN_UUID = "uuid"
        const val COLUMN_DIRTY = "dirty"
        const val COLUMN_DELETED_AT = "deleted_at"
        const val COLUMN_UPDATED_BY_DEVICE = "updated_by_device"
    }

    object CurrencyPairEntry : BaseColumns {
        const val TABLE_NAME = "currency_pair"

        const val _ID = BaseColumns._ID
        const val COLUMN_SYMBOL = "symbol"
        const val COLUMN_BASE_CURRECNCY_ID = "base_currency_id"
        const val COLUMN_QUOTE_CURRECNCY_ID = "quote_currency_id"
        const val COLUMN_PRECISION = "precision"
        /** v85: 1 — пара из общего справочника сервера (`ledger_id` пуст в pull), локальная. */
        const val COLUMN_IS_SHARED = "is_shared"
        /** v88: см. [AccountEntry.COLUMN_FROM_SEED]; пары на телефоне не правятся. */
        const val COLUMN_FROM_SEED = "from_seed"
        const val COLUMN_UUID = "uuid"
        const val COLUMN_DIRTY = "dirty"
        const val COLUMN_DELETED_AT = "deleted_at"
        const val COLUMN_UPDATED_BY_DEVICE = "updated_by_device"
    }

    object CurrencyPairRateEntry : BaseColumns {
        const val TABLE_NAME = "currency_pair_rate"

        const val _ID = BaseColumns._ID

        const val COLUMN_CURRENCY_PAIR_RATE = "currency_pair_id"
        const val COLUMN_DATE = "date"
        const val COLUMN_RATE = "rate"
        const val COLUMN_UUID = "uuid"
        const val COLUMN_DIRTY = "dirty"
        const val COLUMN_DELETED_AT = "deleted_at"
        const val COLUMN_UPDATED_BY_DEVICE = "updated_by_device"
    }

}
