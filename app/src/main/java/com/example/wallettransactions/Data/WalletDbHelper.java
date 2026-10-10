package com.example.wallettransactions.Data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Operations.OperationItem;

public class WalletDbHelper extends SQLiteOpenHelper {


    public static final String LOG_TAG = WalletDbHelper.class.getSimpleName();
    private static final String DATABASE_NAME = "wallet.db";

    private static final int DATABASE_VERSION = 80;

    // v72 (org/docs/TODO_mobile_sync.md, Фаза 3, п.1) — четыре колонки на каждой
    // синхронизируемой таблице, одинаковые везде: uuid (идентичность строки на
    // сервере — генерируется здесь же по умолчанию, СВЕЖИЕ CREATE TABLE могут
    // использовать DEFAULT-выражение; апгрейд существующей базы (см. onUpgrade)
    // так не может — ALTER TABLE ADD COLUMN в SQLite разрешает только константный
    // DEFAULT, поэтому там отдельный проход backfillUuid() построчно), dirty
    // (offline-outbox: 1 — строка ещё не отправлена на сервер; сбрасывает в 0
    // только сам синк-движок), deleted_at (мягкое удаление — tombstone едет как
    // обычное изменение, а не пропадает бесследно), updated_by_device
    // (диагностика — id устройства, применившего последнее изменение; не
    // участвует ни в одном решении о конфликте, ровно как на сервере updated_at).
    private static final String SYNC_COLUMNS_DDL =
            "uuid TEXT NOT NULL DEFAULT (lower(hex(randomblob(16)))), \n"
          + "dirty INTEGER NOT NULL DEFAULT 1, \n"
          + "deleted_at INTEGER, \n"
          + "updated_by_device TEXT";

    private Context mContext;

    public WalletDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        mContext = context;
    }


    @Override
    public void onCreate(SQLiteDatabase db) {
        // Строка для создания таблицы
        Log.w("SQLite", "Creating tables");

        db.execSQL("CREATE TABLE " + WalletContract.IconEntry.TABLE_NAME + " (" + iconTableBody() + ");");
/*CREATE TABLE icons (_id INTEGER PRIMARY KEY AUTOINCREMENT, name INT NOT NULL, resource INT NOT NULL, icon_type INT NOT NULL );*/

        CreateCategory_v_3(db);
        CreateOperationEntry_v_3(db);
        CreateOperationLink(db);
        CreateCurrencyEntry(db);
        CreateAccountOperatingEntry(db);
        CreateRate(db);
        CreateRecurringOperation(db);

        createPartialUniqueIndexes(db);
        createUuidIndexes(db);
        CreateViews(db);
        createSyncTriggers(db);

        WalletFillData.InsertFirstDictionary(db, mContext);
    }

    // ── Тела CREATE TABLE для account/currency/currency_pair/icon ───────────
    //
    // Вынесены в отдельные методы (v72, доп. запрос пользователя 2026-09-05:
    // частичный уникальный индекс вместо обычного column-level UNIQUE) — одно
    // и то же тело используют и свежий CREATE TABLE (onCreate/CreateXxx ниже),
    // и пересборка уже существующей таблицы на апгрейде (см.
    // rebuildTableWithoutInlineUnique) — риск разойтись при будущей правке
    // одной из двух копий этим устраняется. "Unique" сознательно убран из
    // объявления колонки — его заменяет createPartialUniqueIndexes() ниже.

    private String iconTableBody() {
        return WalletContract.IconEntry._ID + " INTEGER PRIMARY KEY AUTOINCREMENT, \n"
             + WalletContract.IconEntry.COLUMN_NAME + " INT NOT NULL, \n"
             + WalletContract.IconEntry.COLUMN_IMAGE + " BLOB NOT NULL, \n"
             + WalletContract.IconEntry.COLUMN_ICON_TYPE + " INT NOT NULL, \n"
             + SYNC_COLUMNS_DDL;
    }

    private String accountTableBody() {
        return WalletContract.AccountEntry._ID + " INTEGER PRIMARY KEY AUTOINCREMENT, \n"
             + WalletContract.AccountEntry.COLUMN_NAME + " TEXT NOT NULL, \n"
             + WalletContract.AccountEntry.COLUMN_COMMENT + " TEXT NOT NULL, \n"
             + WalletContract.AccountEntry.COLUMN_ICON_ID + " INT NOT NULL,\n"
             + WalletContract.AccountEntry.COLUMN_IS_ARCHIVED + " INT DEFAULT 0,\n"
             // v75 — явный признак системности вместо распознавания по имени, см.
             // javadoc AccountItem.SystemKind и шаг v75 в onUpgrade ниже.
             + WalletContract.AccountEntry.COLUMN_SYSTEM_KIND + " INT NOT NULL DEFAULT 0,\n"
             // v76 — явный признак «кошелёк или категория», см. javadoc
             // AccountItem.Kind и шаг v76 в onUpgrade ниже.
             + WalletContract.AccountEntry.COLUMN_KIND + " INT NOT NULL DEFAULT 0,\n"
             + SYNC_COLUMNS_DDL + ",\n"
             + " CONSTRAINT fk_account_icon_id FOREIGN KEY ("
             + WalletContract.AccountEntry.COLUMN_ICON_ID
             + ") REFERENCES " + WalletContract.IconEntry.TABLE_NAME + " ("
             + WalletContract.IconEntry._ID + ")";
    }

    private String currencyTableBody() {
        return WalletContract.CurrencyEntry._ID + " INTEGER PRIMARY KEY AUTOINCREMENT, \n"
             + WalletContract.CurrencyEntry.COLUMN_NAME + " TEXT NOT NULL, \n"
             + WalletContract.CurrencyEntry.COLUMN_ISO_NAME + " TEXT NOT NULL, \n"
             + WalletContract.CurrencyEntry.COLUMN_ICON_ID + " INTEGER NOT NULL, \n"
             + WalletContract.CurrencyEntry.COLUMN_SYMBOL + " TEXT NOT NULL, \n"
             + WalletContract.CurrencyEntry.COLUMN_PRECISION + " INTEGER NOT NULL DEFAULT 2, \n"
             + SYNC_COLUMNS_DDL + ", \n"
             + " CONSTRAINT fk_currency_icon_id FOREIGN KEY ("
             + WalletContract.CurrencyEntry.COLUMN_ICON_ID
             + ") REFERENCES " + WalletContract.IconEntry.TABLE_NAME + " ("
             + WalletContract.IconEntry._ID + ")";
    }

    private String currencyPairTableBody() {
        return WalletContract.CurrencyPairEntry._ID + " INTEGER PRIMARY KEY AUTOINCREMENT, \n"
             + WalletContract.CurrencyPairEntry.COLUMN_SYMBOL + " TEXT NOT NULL, \n"
             + WalletContract.CurrencyPairEntry.COLUMN_BASE_CURRECNCY_ID + " integer NOT NULL, \n"
             + WalletContract.CurrencyPairEntry.COLUMN_QUOTE_CURRECNCY_ID + " INTEGER NOT NULL, \n"
             + WalletContract.CurrencyPairEntry.COLUMN_PRECISION + " INTEGER NOT NULL DEFAULT 5, \n"
             + SYNC_COLUMNS_DDL + ", \n"
             + " CONSTRAINT fk_currency_pair_base_currency_id FOREIGN KEY ("
             + WalletContract.CurrencyPairEntry.COLUMN_BASE_CURRECNCY_ID
             + ") REFERENCES " + WalletContract.CurrencyEntry.TABLE_NAME + " ("
             + WalletContract.CurrencyEntry._ID + "),"
             + " CONSTRAINT fk_currency_pair_quote_currency_id FOREIGN KEY ("
             + WalletContract.CurrencyPairEntry.COLUMN_QUOTE_CURRECNCY_ID
             + ") REFERENCES " + WalletContract.CurrencyEntry.TABLE_NAME + " ("
             + WalletContract.CurrencyEntry._ID + ")";
    }

    /**
     * Частичные уникальные индексы взамен обычного column-level UNIQUE,
     * снятого с v72 (см. rebuildTableWithoutInlineUnique) — уникальность
     * только среди живых (deleted_at IS NULL) строк, тот же приём, что и на
     * сервере (uq_account_name и т.п., см. wallet-api/schema/table/account.sql).
     * IF NOT EXISTS — вызывается и из onCreate (для новых установок, где
     * column-level UNIQUE никогда не было), и из шага v72 (для апгрейженных,
     * после пересборки таблиц).
     */
    private void createPartialUniqueIndexes(SQLiteDatabase db) {
        createAccountNameIndex(db);
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS uq_currency_name ON "
                + WalletContract.CurrencyEntry.TABLE_NAME + " (" + WalletContract.CurrencyEntry.COLUMN_NAME
                + ") WHERE " + WalletContract.CurrencyEntry.COLUMN_DELETED_AT + " IS NULL");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS uq_currency_iso_code ON "
                + WalletContract.CurrencyEntry.TABLE_NAME + " (" + WalletContract.CurrencyEntry.COLUMN_ISO_NAME
                + ") WHERE " + WalletContract.CurrencyEntry.COLUMN_DELETED_AT + " IS NULL");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS uq_currency_pair_symbol ON "
                + WalletContract.CurrencyPairEntry.TABLE_NAME + " (" + WalletContract.CurrencyPairEntry.COLUMN_SYMBOL
                + ") WHERE " + WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT + " IS NULL");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS uq_icon_name ON "
                + WalletContract.IconEntry.TABLE_NAME + " (" + WalletContract.IconEntry.COLUMN_NAME
                + ") WHERE " + WalletContract.IconEntry.COLUMN_DELETED_AT + " IS NULL");
    }

    /**
     * v80: имя счёта уникально по {@link AccountNames} (без учёта регистра, «ё» = «е»),
     * а не побайтово — то же правило, что у сервера. Индекс по выражению, а не по колонке.
     */
    private static void createAccountNameIndex(SQLiteDatabase db) {
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS uq_account_name ON "
                + WalletContract.AccountEntry.TABLE_NAME + " ("
                + AccountNames.keySql(WalletContract.AccountEntry.COLUMN_NAME)
                + ") WHERE " + WalletContract.AccountEntry.COLUMN_DELETED_AT + " IS NULL");
    }

    /**
     * Шаг v80: старый индекс сравнивал имена побайтово, так что «Кафе» и «кафе» могли
     * жить рядом. Перед новым индексом такие дубли переименовываются: первый по _id
     * остаётся, остальные получают « (2)», « (3)»… Триггер dirty пометит их к отправке.
     */
    static void migrateAccountNameIndex(SQLiteDatabase db) {
        db.execSQL("DROP INDEX IF EXISTS uq_account_name");
        // Сначала все живые ключи, потом дубли — новое имя « (2)» не должно совпасть
        // ни с одним существующим, даже с тем, что идёт в списке позже.
        Set<String> taken = new HashSet<>();
        List<Long> dupIds = new ArrayList<>();
        List<String> dupNames = new ArrayList<>();
        // Ключ — тем же SQL-выражением, что у индекса: иначе дубль, который увидит
        // CREATE UNIQUE INDEX, мог бы остаться нераспознанным и уронить апгрейд.
        Cursor c = db.rawQuery("SELECT " + WalletContract.AccountEntry._ID + ", "
                + WalletContract.AccountEntry.COLUMN_NAME + ", "
                + AccountNames.keySql(WalletContract.AccountEntry.COLUMN_NAME)
                + " FROM " + WalletContract.AccountEntry.TABLE_NAME
                + " WHERE " + WalletContract.AccountEntry.COLUMN_DELETED_AT + " IS NULL"
                + " ORDER BY " + WalletContract.AccountEntry._ID, null);
        try {
            while (c.moveToNext()) {
                if (!taken.add(c.getString(2))) {
                    dupIds.add(c.getLong(0));
                    dupNames.add(c.getString(1));
                }
            }
        } finally {
            c.close();
        }
        for (int i = 0; i < dupIds.size(); i++) {
            String candidate;
            int n = 2;
            do {
                candidate = dupNames.get(i) + " (" + n++ + ")";
            } while (!taken.add(AccountNames.key(db, candidate)));
            ContentValues cv = new ContentValues();
            cv.put(WalletContract.AccountEntry.COLUMN_NAME, candidate);
            db.update(WalletContract.AccountEntry.TABLE_NAME, cv,
                    WalletContract.AccountEntry._ID + "=?", new String[]{String.valueOf(dupIds.get(i))});
        }
        createAccountNameIndex(db);
    }

    /**
     * Индекс на uuid для каждой из 9 синхронизируемых таблиц — понадобится
     * движку синка (org/docs/TODO_mobile_sync.md, Фаза 3, п. 4, ещё не
     * реализован): применяя входящую pull-строку, он первым делом ищет "есть
     * ли уже локальная строка с этим uuid" — без индекса это полный скан
     * таблицы на каждую входящую строку. IF NOT EXISTS — как и
     * createPartialUniqueIndexes, вызывается и из onCreate, и из шага v72.
     */
    private void createUuidIndexes(SQLiteDatabase db) {
        createUuidIndex(db, WalletContract.AccountEntry.TABLE_NAME);
        createUuidIndex(db, WalletContract.AccountOperatingEntry.TABLE_NAME);
        createUuidIndex(db, WalletContract.CurrencyEntry.TABLE_NAME);
        createUuidIndex(db, WalletContract.CurrencyPairEntry.TABLE_NAME);
        createUuidIndex(db, WalletContract.CurrencyPairRateEntry.TABLE_NAME);
        createUuidIndex(db, WalletContract.CurrencyConversionEntry.TABLE_NAME);
        createUuidIndex(db, WalletContract.IconEntry.TABLE_NAME);
        createUuidIndex(db, WalletContract.OperationEntry.TABLE_NAME);
        createUuidIndex(db, WalletContract.OperationLinkEntry.TABLE_NAME);
    }

    private void createUuidIndex(SQLiteDatabase db, String table) {
        db.execSQL("CREATE INDEX IF NOT EXISTS ix_" + table + "_uuid ON " + table + " (uuid)");
    }

    /**
     * Пересобирает таблицу, чтобы снять её "врождённый" column-level UNIQUE —
     * SQLite не даёт убрать его через ALTER TABLE, autoindex-ограничение
     * недоступно для DROP INDEX. Стандартная процедура SQLite для того, чего
     * не умеет ALTER TABLE (официальная "12-шаговая" последовательность):
     * переименовать старую таблицу в сторону, создать новую под старым именем
     * (без inline UNIQUE — его заменяет createPartialUniqueIndexes() выше),
     * перенести данные явным списком колонок, поправить sqlite_sequence
     * (иначе AUTOINCREMENT после пересборки начал бы выдавать уже
     * использованные id), удалить старую.
     * <p>
     * Внешние ключи этому не мешают: во время onUpgrade это соединение ещё не
     * успело включить {@code PRAGMA foreign_keys} — {@link #getWritableDatabase()}
     * включает его только ПОСЛЕ onCreate/onUpgrade, когда управление уже
     * вернулось из {@code super.getWritableDatabase()} — поэтому проверки
     * внешних ключей здесь не активны и порядок между таблицами не важен
     * (используется дружелюбный к зависимостям порядок — icon, currency,
     * currency_pair, account — но не потому, что без него что-то сломается).
     * Дублирующихся имён среди переносимых строк тоже не бывает: старая
     * таблица держала обычный (не частичный) UNIQUE на всём своём протяжении
     * жизни, а deleted_at ещё не мог быть проставлен ни одной строке до этой
     * миграции (soft-delete — тоже новшество v72).
     * <p>
     * Триггеры на старой таблице SQLite роняет вместе с DROP TABLE — их
     * пересоздаёт последующий createSyncTriggers(db) в конце шага v72,
     * здесь заботиться не нужно.
     *
     * @param table           имя таблицы
     * @param columns         ВСЕ колонки в порядке, соответствующем
     *                        newTableBodySql — используется и для явного
     *                        списка в INSERT...SELECT, и как есть для
     *                        sqlite_sequence
     * @param newTableBodySql тело CREATE TABLE (без "CREATE TABLE x (" и ")") —
     *                        то же, что вернул бы соответствующий xxxTableBody()
     */
    private void rebuildTableWithoutInlineUnique(SQLiteDatabase db, String table, String[] columns,
                                                  String newTableBodySql) {
        String oldTable = table + "_pre_v72";
        String columnList = TextUtils.join(", ", columns);

        db.execSQL("ALTER TABLE " + table + " RENAME TO " + oldTable);
        db.execSQL("CREATE TABLE " + table + " (" + newTableBodySql + ")");
        db.execSQL("INSERT INTO " + table + " (" + columnList + ") SELECT " + columnList
                + " FROM " + oldTable);

        // Все 4 пересобираемые таблицы — INTEGER PRIMARY KEY AUTOINCREMENT по _id.
        db.execSQL("INSERT INTO sqlite_sequence (name, seq) "
                + "SELECT '" + table + "', COALESCE((SELECT MAX(_id) FROM " + table + "), 0) "
                + "WHERE NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name='" + table + "')");
        db.execSQL("UPDATE sqlite_sequence SET seq = COALESCE((SELECT MAX(_id) FROM " + table + "), 0) "
                + "WHERE name='" + table + "'");

        db.execSQL("DROP TABLE " + oldTable);
    }

    /**
     * Триггеры "не забыл ли код-путь проставить dirty=1" (org/docs/TODO_mobile_sync.md,
     * Фаза 3 — решено использовать триггеры, а не патчить каждое место записи:
     * единого узкого CRUD-слоя на самом деле нет, минимум 4 места пишут в обход
     * WalletDbCRUD напрямую — TransactionActivity/IconEditDialog/RateEditDialog/
     * MoreActivity). {@code WHEN NEW.dirty = OLD.dirty} — единственный надёжный
     * признак "этот UPDATE не упоминал колонку dirty вообще" (в SQLite: если SET
     * не трогает колонку, NEW.<col> остаётся равен OLD.<col>) — именно тогда триггер
     * форсит dirty=1. Явный {@code SET dirty=0} по строке, где и так был 0, триггер
     * от «забыли» НЕ отличает (NEW.dirty = OLD.dirty) — поэтому синк-движок пишет
     * входящие с сервера изменения в два шага через dirty=2 (см.
     * {@code SyncEngine.updateFromServer}), иначе каждая обновлённая строка уходила
     * бы обратно на сервер. {@code AND dirty <> 1} в теле
     * останавливает рекурсию — вложенный UPDATE тоже попадает под этот же AFTER
     * UPDATE, но второй раз строка уже dirty=1 и под условие не подходит.
     */
    private void createSyncTriggers(SQLiteDatabase db) {
        createDirtyTrigger(db, WalletContract.AccountEntry.TABLE_NAME, WalletContract.AccountEntry._ID);
        createDirtyTrigger(db, WalletContract.AccountOperatingEntry.TABLE_NAME, WalletContract.AccountOperatingEntry._ID);
        createDirtyTrigger(db, WalletContract.CurrencyEntry.TABLE_NAME, WalletContract.CurrencyEntry._ID);
        createDirtyTrigger(db, WalletContract.CurrencyPairEntry.TABLE_NAME, WalletContract.CurrencyPairEntry._ID);
        createDirtyTrigger(db, WalletContract.IconEntry.TABLE_NAME, WalletContract.IconEntry._ID);
        createDirtyTrigger(db, WalletContract.OperationEntry.TABLE_NAME, WalletContract.OperationEntry._ID);
        createDirtyTrigger(db, WalletContract.CurrencyPairRateEntry.TABLE_NAME,
                WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE,
                WalletContract.CurrencyPairRateEntry.COLUMN_DATE);
        createDirtyTrigger(db, WalletContract.CurrencyConversionEntry.TABLE_NAME,
                WalletContract.CurrencyConversionEntry.COLUMN_FROM_CURRENCY_ID,
                WalletContract.CurrencyConversionEntry.COLUMN_TO_CURRENCY_ID,
                WalletContract.CurrencyConversionEntry.COLUMN_NUM);
        createDirtyTrigger(db, WalletContract.OperationLinkEntry.TABLE_NAME,
                WalletContract.OperationLinkEntry.COLUMN_MAIN_ID,
                WalletContract.OperationLinkEntry.COLUMN_LINK_ID);
    }

    private void createDirtyTrigger(SQLiteDatabase db, String table, String... keyColumns) {
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < keyColumns.length; i++) {
            if (i > 0) where.append(" AND ");
            where.append(keyColumns[i]).append(" = NEW.").append(keyColumns[i]);
        }
        db.execSQL("CREATE TRIGGER IF NOT EXISTS trg_" + table + "_dirty\n"
                + "AFTER UPDATE ON " + table + "\n"
                + "WHEN NEW.dirty = OLD.dirty\n"
                + "BEGIN\n"
                + "  UPDATE " + table + " SET dirty = 1 WHERE " + where + " AND dirty <> 1;\n"
                + "END;");
    }

    /**
     * ALTER TABLE ADD COLUMN для уже существующей (v68-71) базы. В отличие от
     * CREATE TABLE (см. SYNC_COLUMNS_DDL) здесь НЕЛЬЗЯ использовать выражение
     * {@code lower(hex(randomblob(16)))} как DEFAULT для uuid — SQLite разрешает
     * в ADD COLUMN только константный DEFAULT (dirty=1/deleted_at=NULL — константы,
     * тем и отделались; uuid — нет). Поэтому uuid добавляется БЕЗ значения по
     * умолчанию и сразу заполняется построчно — см. backfillUuid ниже.
     */
    private void addSyncColumns(SQLiteDatabase db, String table) {
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN uuid TEXT");
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1");
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN deleted_at INTEGER");
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN updated_by_device TEXT");
    }

    /**
     * Заполняет uuid для строк, уже существовавших до ALTER TABLE (см.
     * addSyncColumns) — генерирует новый случайный uuid на каждую найденную
     * строку. keyColumns — колонки первичного ключа (одна для таблиц с _id,
     * несколько для составного PK, см. currency_pair_rate/currency_conversion/
     * operation_link).
     */
    /**
     * Есть ли колонка в таблице — нужно шагу v75 (см. onUpgrade): апгрейд из ОЧЕНЬ
     * старой базы (oldVersion&lt;72) успевает пересобрать {@code account} веткой v72
     * (rebuildTableWithoutInlineUnique) уже ТЕКУЩИМ accountTableBody(), в котором
     * v75 добавил {@code system_kind} безусловно — то есть колонка там уже есть, и
     * последующий {@code ALTER TABLE ADD COLUMN} упал бы на "duplicate column name".
     */
    private boolean columnExists(SQLiteDatabase db, String table, String column) {
        Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
        try {
            int nameIdx = c.getColumnIndex("name");
            while (c.moveToNext()) {
                if (column.equalsIgnoreCase(c.getString(nameIdx))) return true;
            }
            return false;
        } finally {
            c.close();
        }
    }

    private void backfillUuid(SQLiteDatabase db, String table, String... keyColumns) {
        String keyList = TextUtils.join(", ", keyColumns);
        Cursor cursor = db.rawQuery("SELECT " + keyList + " FROM " + table, null);
        try {
            StringBuilder where = new StringBuilder();
            for (int i = 0; i < keyColumns.length; i++) {
                if (i > 0) where.append(" AND ");
                where.append(keyColumns[i]).append(" = ?");
            }
            while (cursor.moveToNext()) {
                ContentValues values = new ContentValues();
                values.put("uuid", UUID.randomUUID().toString());
                String[] whereArgs = new String[keyColumns.length];
                for (int i = 0; i < keyColumns.length; i++) {
                    whereArgs[i] = cursor.getString(i);
                }
                db.update(table, values, where.toString(), whereArgs);
            }
        } finally {
            cursor.close();
        }
    }

    /**
     * Бэкфилл uuid для {@code account_operating} на апгрейде — НЕ случайный (в
     * отличие от {@link #backfillUuid}), а всегда равный uuid соответствующего
     * {@code account} (тот же {@code _id}). Инвариант с сервера
     * (wallet-api/schema/table/account_operating.sql: "uuid здесь всегда равен
     * account.uuid" — общий первичный ключ на уровне синка, а не только
     * локального {@code _id}) — иначе push уже существующих на апгрейженной базе
     * кошельков падал бы FK-ошибкой {@code fk_account_operating_account} на
     * сервере (там uuid — тоже часть внешнего ключа на account, не просто
     * колонка для галочки).
     */
    private void backfillAccountOperatingUuid(SQLiteDatabase db) {
        db.execSQL("UPDATE " + WalletContract.AccountOperatingEntry.TABLE_NAME
                + " SET uuid = (SELECT uuid FROM " + WalletContract.AccountEntry.TABLE_NAME
                + " WHERE " + WalletContract.AccountEntry.TABLE_NAME + "." + WalletContract.AccountEntry._ID
                + " = " + WalletContract.AccountOperatingEntry.TABLE_NAME + "." + WalletContract.AccountOperatingEntry._ID + ")");
    }

    /**
     * v73 — устраняет легаси-дубли {@code currency_pair_rate}: несколько строк
     * на один и тот же (пара, календарная дата), появившиеся из-за старого кода
     * (и {@code InsertCurrencyPairRate}, и {@code RateEditDialog.saveRate} раньше
     * писали {@code date} с временем, "yyyy-MM-dd HH:mm" — см. комментарий у
     * {@link #InsertCurrencyPairRate} через {@code WalletFillData}, там же откуда
     * взят приём substr-сравнения). Оба места это уже не делают, но существующие
     * дубли, накопленные ДО правки, никуда не делись — а на сервере они бьются об
     * {@code uq_currency_pair_rate_ledger}/{@code uq_currency_pair_rate_reference}
     * (23505) при первом же push, откатывая ВСЮ транзакцию батча (не только эту
     * таблицу, см. push_batch в sync_repo.cpp) — и раз обе конфликтующие строки
     * остаются {@code dirty=1} после отката, следующий цикл синка повторяет ровно
     * тот же конфликт бесконечно.
     * <p>
     * Для каждой группы (совпадают {@code currency_pair_id} и первые 10 символов
     * {@code date}) оставляем ОДНУ строку — с МАКСИМАЛЬНЫМ {@code rowid}, то есть
     * реально ПОСЛЕДНЮЮ вставленную физически (SQLite растит {@code rowid} строго
     * с каждым {@code INSERT}). Не по значению самого {@code date}: время в
     * легаси-строке — не момент ввода, а что угодно, что нёс исходный
     * {@code java.util.Date} (импорт CSV, ручной ввод) — оно может оказаться
     * МЕНЬШЕ у физически более новой строки, так что сравнивать нужно порядок
     * вставки, а не содержимое даты. Остальные — HARD DELETE, не soft-delete: это
     * не отдельные пользовательские записи, а чистый артефакт бага, tombstone на
     * них хранить незачем (курс — не проводка, ни один экран/отчёт не показывает
     * историю ПРАВОК одной даты). Дата выжившей строки нормализуется до
     * "yyyy-MM-dd", и она форсится {@code dirty=1} — она либо ещё не доезжала до
     * сервера (обе строки коллизировали в одном батче с самого начала), либо
     * доезжала под старым, содержащим время значением даты, которое теперь всё
     * равно поменялось.
     */
    private void dedupeCurrencyPairRateDuplicates(SQLiteDatabase db) {
        String table = WalletContract.CurrencyPairRateEntry.TABLE_NAME;
        String pairCol = WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE;
        String dateCol = WalletContract.CurrencyPairRateEntry.COLUMN_DATE;

        Cursor groups = db.rawQuery(
                "SELECT " + pairCol + ", substr(" + dateCol + ", 1, 10) AS day, COUNT(*) AS c "
                        + "FROM " + table + " GROUP BY " + pairCol + ", day HAVING c > 1", null);
        try {
            while (groups.moveToNext()) {
                long pairId = groups.getLong(0);
                String day = groups.getString(1);

                // rowid, не "date" — см. javadoc метода, почему именно так.
                long survivorRowId;
                String survivorDate;
                Cursor rows = db.rawQuery(
                        "SELECT rowid, " + dateCol + " FROM " + table + " WHERE " + pairCol + " = ? "
                                + "AND substr(" + dateCol + ", 1, 10) = ? ORDER BY rowid DESC",
                        new String[]{String.valueOf(pairId), day});
                try {
                    rows.moveToFirst();
                    survivorRowId = rows.getLong(0);
                    survivorDate = rows.getString(1);
                } finally {
                    rows.close();
                }

                int deleted = db.delete(table,
                        pairCol + " = ? AND substr(" + dateCol + ", 1, 10) = ? AND rowid <> ?",
                        new String[]{String.valueOf(pairId), day, String.valueOf(survivorRowId)});
                Log.i(LOG_TAG, "dedupeCurrencyPairRateDuplicates: пара _id=" + pairId + ", дата " + day
                        + " — удалено дублей: " + deleted + ", оставлена rowid=" + survivorRowId
                        + " (была date=" + survivorDate + ")");

                ContentValues cv = new ContentValues();
                cv.put(dateCol, day); // нормализуем до чистой даты, без времени
                cv.put("dirty", 1);
                db.update(table, cv, "rowid = ?", new String[]{String.valueOf(survivorRowId)});
            }
        } finally {
            groups.close();
        }
    }

    /**
     * v74 — чинит {@code account_operating.uuid}, который какое-то время (до
     * коммита "openapi", 2026-09-05) получал СВОЙ независимый случайный uuid
     * вместо uuid счёта того же {@code _id}: пути импорта
     * ({@code WalletImportData.ParseAndInsertAccount}), сидирования
     * ({@code WalletFillData.InsertSeedAccounts}, тогда ещё {@code InsertAccountsFromCsv}) и
     * {@code WalletDbCRUD.InsertOperatingAccount} нарушали инвариант
     * "{@code account_operating.uuid} = {@code account.uuid} того же счёта"
     * (см. javadoc {@link #backfillAccountOperatingUuid}, откуда взят сам
     * инвариант, и {@code wallet-api/schema/table/account_operating.sql} — там это
     * общий первичный ключ синка, часть внешнего ключа на {@code account}, а
     * не просто колонка для галочки).
     * <p>
     * Строка со сторонним uuid уходила в push как есть — {@code dirty=1}
     * проставлялся исправно (через {@code DEFAULT}/триггер), баг был не в
     * outbox, а в самом значении uuid, — но сервер не находил {@code account}
     * с таким uuid ВООБЩЕ (это значение никогда и не было чьим-либо
     * {@code account.uuid}): {@code 23503 fk_account_operating_account},
     * откатывающий ВЕСЬ батч (реальный инцидент, {@code POST /sync/push},
     * 2026-09-05). Раз откат не сбрасывает {@code dirty} ни у одной строки
     * батча (см. {@code SyncEngine.pushDirty}), то же самое сломанное значение
     * уходило бы в КАЖДЫЙ следующий цикл синка — вечный затор для всего
     * устройства, не только для этой одной строки.
     * <p>
     * Тем же приёмом, что {@link #backfillAccountOperatingUuid} на v72, но
     * точечно (только там, где uuid реально разошёлся с {@code account}) —
     * уже корректные строки трогать незачем, лишний повторный push им не
     * нужен.
     */
    private void repairAccountOperatingUuidMismatch(SQLiteDatabase db) {
        Cursor cursor = db.rawQuery(
                "SELECT ao." + WalletContract.AccountOperatingEntry._ID + ", a.uuid "
                        + "FROM " + WalletContract.AccountOperatingEntry.TABLE_NAME + " ao "
                        + "JOIN " + WalletContract.AccountEntry.TABLE_NAME + " a "
                        + "  ON a." + WalletContract.AccountEntry._ID + " = ao." + WalletContract.AccountOperatingEntry._ID
                        + " WHERE ao.uuid <> a.uuid", null);
        int fixed = 0;
        try {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String correctUuid = cursor.getString(1);

                ContentValues values = new ContentValues();
                values.put("uuid", correctUuid);
                values.put("dirty", 1);
                db.update(WalletContract.AccountOperatingEntry.TABLE_NAME, values,
                        WalletContract.AccountOperatingEntry._ID + " = ?",
                        new String[]{String.valueOf(id)});
                fixed++;
            }
        } finally {
            cursor.close();
        }
        Log.i(LOG_TAG, "repairAccountOperatingUuidMismatch: исправлено строк account_operating: " + fixed);
    }

    private void CreateRate(SQLiteDatabase db) {

        db.execSQL("DROP table IF EXISTS "+ WalletContract.CurrencyPairEntry.TABLE_NAME);

        db.execSQL("CREATE TABLE " + WalletContract.CurrencyPairEntry.TABLE_NAME + " (" + currencyPairTableBody() + ");");

        db.execSQL("DROP table IF EXISTS "+ WalletContract.CurrencyPairRateEntry.TABLE_NAME);

        db.execSQL("CREATE TABLE " + WalletContract.CurrencyPairRateEntry.TABLE_NAME + " (\n"
                + WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE + " integer, \n"
                + WalletContract.CurrencyPairRateEntry.COLUMN_DATE  + " DATETIME, \n"
                + WalletContract.CurrencyPairRateEntry.COLUMN_RATE + " DECIMAL(12,5), \n"
                + SYNC_COLUMNS_DDL + ", \n"
                + " CONSTRAINT pk_currency_pair_rate PRIMARY KEY  (\n"
                + WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE
                +", "+ WalletContract.CurrencyPairRateEntry.COLUMN_DATE
                + "), \n"
                + " CONSTRAINT fk_currency_pair_rate_currency_pair_id FOREIGN KEY (\n"
                + WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE
                + ") REFERENCES "+WalletContract.CurrencyPairEntry.TABLE_NAME+" (\n"
                + WalletContract.CurrencyPairEntry._ID+")"
                + ");");

        db.execSQL("DROP table IF EXISTS "+ WalletContract.CurrencyConversionEntry.TABLE_NAME);

        db.execSQL("CREATE TABLE " + WalletContract.CurrencyConversionEntry.TABLE_NAME + " (\n"
                + WalletContract.CurrencyConversionEntry.COLUMN_FROM_CURRENCY_ID + " integer, \n"
                + WalletContract.CurrencyConversionEntry.COLUMN_TO_CURRENCY_ID + " integer, \n"
                + WalletContract.CurrencyConversionEntry.COLUMN_NUM  + " integer, \n"
                + WalletContract.CurrencyConversionEntry.COLUMN_CURRENCY_PAIR_ID + " integer, \n"
                + WalletContract.CurrencyConversionEntry.COLUMN_IS_REVERSE + " integer, \n"
                + SYNC_COLUMNS_DDL + ", \n"
                + " CONSTRAINT pk_currency_conversion PRIMARY KEY  (\n"
                + WalletContract.CurrencyConversionEntry.COLUMN_FROM_CURRENCY_ID +", "
                + WalletContract.CurrencyConversionEntry.COLUMN_TO_CURRENCY_ID +", "
                + WalletContract.CurrencyConversionEntry.COLUMN_NUM
                + "), \n"
                + " CONSTRAINT fk_currency_conversion_from_currency_id FOREIGN KEY (\n"
                + WalletContract.CurrencyConversionEntry.COLUMN_FROM_CURRENCY_ID
                + ") REFERENCES "+WalletContract.CurrencyEntry.TABLE_NAME+" (\n"
                + WalletContract.CurrencyPairEntry._ID+"),\n"
                + " CONSTRAINT fk_currency_conversion_to_currency_id FOREIGN KEY (\n"
                + WalletContract.CurrencyConversionEntry.COLUMN_TO_CURRENCY_ID
                + ") REFERENCES "+WalletContract.CurrencyEntry.TABLE_NAME+" (\n"
                + WalletContract.CurrencyPairEntry._ID+"),\n"
                + " CONSTRAINT fk_currency_conversion_currency_pair_id FOREIGN KEY (\n"
                + WalletContract.CurrencyConversionEntry.COLUMN_CURRENCY_PAIR_ID
                + ") REFERENCES "+WalletContract.CurrencyPairEntry.TABLE_NAME+" (\n"
                + WalletContract.CurrencyPairEntry._ID+")\n"
                + ");");
    }

    private void CreateViews(SQLiteDatabase db) {

        // v72 (org/docs/TODO_mobile_sync.md, Фаза 3, п.1): все представления ниже
        // получили фильтр deleted_at IS NULL — мягко удалённая строка не должна
        // быть видна как живая, но должна дожить до синка как обычное изменение
        // (см. SYNC_COLUMNS_DDL выше). Зеркально серверным вьюхам
        // (wallet-api/schema/view/*.sql) с одним отличием, тоже зеркальным: v_operation
        // НЕ фильтрует сами account/account_operating по deleted_at — операция на
        // мягко удалённый счёт обязана остаться видимой в истории (см. комментарий
        // в wallet-api/schema/view/v_operation.sql), фильтруется только сама операция.

        db.execSQL("DROP view IF EXISTS v_account_expanse");
        db.execSQL("CREATE VIEW v_account_expanse\n"
                +"as select a.*\n"
                +"from " + WalletContract.AccountEntry.TABLE_NAME + " a\n"
                +"left join " + WalletContract.AccountOperatingEntry.TABLE_NAME +" ao\n"
                +"on ao."+ WalletContract.AccountOperatingEntry._ID +" = a."+ WalletContract.AccountEntry._ID +"\n"
                +"and ao.deleted_at is null\n"
                +"where a.deleted_at is null\n"
                +"and ao._id is null");

        db.execSQL("DROP view IF EXISTS v_account_operating");

        db.execSQL("CREATE VIEW v_account_operating\n"
                +"as select a._id, a.name, a.comment, a.icon_id , ao.currency_id \n"
                +"from " + WalletContract.AccountEntry.TABLE_NAME + " a\n"
                +"left join " + WalletContract.AccountOperatingEntry.TABLE_NAME +" ao\n"
                +"on ao."+ WalletContract.AccountOperatingEntry._ID +" = a."+ WalletContract.AccountEntry._ID +"\n"
                +"and ao.deleted_at is null\n"
                +"where a.deleted_at is null");

        db.execSQL("DROP VIEW IF EXISTS v_currency_conversion");

        db.execSQL("CREATE VIEW v_currency_conversion\n" +
                "as \n" +
                "select fc._id from_currency_id, fc.name from_currency_name, fc.iso_code from_currency_iso,\n" +
                "tc._id to_currency_id, tc.name to_currency_name, tc.iso_code to_currency_iso,\n" +
                "COALESCE(cc.currency_pair_id, fk.currency_pair_id) currency_pair_id, \n" +
                "COALESCE(cc.num, fk.num) num, \n" +
                "COALESCE(cc.is_reverse, fk.is_reverse) is_reverse\n" +
                "from (\n" +
                "select \"_id\" currency_pair_id, base_currency_id from_currency_id, quote_currency_id to_currency_id, 1 num, 0 is_reverse\n" +
                "from currency_pair cp \n" +
                "where cp.deleted_at is null\n" +
                "union \n" +
                "select \"_id\" currency_pair_id, quote_currency_id from_currency_id, base_currency_id to_currency_id, 1 num, 1 is_reverse\n" +
                "from currency_pair cp\n" +
                "where cp.deleted_at is null ) fk\n" +
                "left join currency_conversion cc\n" +
                "on cc.from_currency_id = fk.from_currency_id and cc.to_currency_id == fk.to_currency_id and cc.deleted_at is null\n" +
                "join currency fc\n" +
                "on fc.\"_id\" =COALESCE(cc.from_currency_id, fk.from_currency_id) and fc.deleted_at is null\n" +
                "join currency tc\n" +
                "on tc.\"_id\" =COALESCE(cc.to_currency_id,fk.to_currency_id) and tc.deleted_at is null;");

        db.execSQL("drop VIEW IF EXISTS v_currency_conversion_rate;\n");

        db.execSQL("CREATE VIEW v_currency_conversion_rate\n" +
                "as\n" +
                "select \n" +
                "from_currency_id, from_currency_name, from_currency_iso,\n" +
                "to_currency_id, to_currency_name, to_currency_iso,\n" +
                "date,\n" +
                "case when is_reverse = 1 then 1.0/rate else rate end rate \n" +
                "from v_currency_conversion cc\n" +
                "join currency_pair_rate r\n" +
                "on r.currency_pair_id = cc.currency_pair_id and r.deleted_at is null\n" +
                "join currency_pair p\n" +
                "on p._id = cc.currency_pair_id and p.deleted_at is null\n" +
                "union select _id, name, iso_code , _id, name, iso_code , '2000-01-01', 1 from currency c where c.deleted_at is null;");

        db.execSQL("drop VIEW IF EXISTS v_operation;\n");

        db.execSQL("create view v_operation\n" +
                "as \n" +
                "select o._id, o.date, o.acc_fr_id, o.acc_to_id, o.amount, o.comment , acf.name acc_fr_name, act.name acc_to_name, COALESCE(aof.currency_id , aot.currency_id ) currency_id\n" +
                ", case when aof._id is not null then 1 else 0 end acc_from_is_operating\n" +
                ", case when aot._id is not null then 1 else 0 end acc_to_is_operating\n" +
                "from operation o \n" +
                "join account acf\n" +
                "on acf._id = o.acc_fr_id \n" +
                "join account act \n" +
                "on act._id = o.acc_to_id \n" +
                "left join account_operating aof\n" +
                "on aof._id  = acf._id and aof.deleted_at is null\n" +
                "left join account_operating aot \n" +
                "on  aot._id  =  act._id and aot.deleted_at is null\n" +
                "where o.deleted_at is null;");

        db.execSQL("drop VIEW IF EXISTS v_operation_eq;\n");

        db.execSQL("CREATE VIEW v_operation_eq\n" +
                "as\n" +
                "select o._id, o.date, o.acc_fr_id, o.acc_to_id, o.amount, o.comment , o.acc_fr_name, o.acc_from_is_operating, o.acc_to_name, o.acc_to_is_operating,o.currency_id, o.to_currency_id, \n" +
                "o.max_date_rate, r.rate , round(r.rate*o.amount,2) amount_eq \n" +
                "from\n" +
                "(\n" +
                "select o._id, o.date, o.acc_fr_id, o.acc_to_id, o.amount, o.comment , o.acc_fr_name, o.acc_from_is_operating,\n" +
                "o.acc_to_name, o.acc_to_is_operating, o.currency_id, cc._id to_currency_id, max(rld.date) max_date_rate , cc._id \n" +
                "  from v_operation o\n" +
                "  cross join currency cc \n" +
                "  left join v_currency_conversion_rate rld\n" +
                "    on rld.from_currency_id = o.currency_id and rld.to_currency_id =cc._id and o.date >= rld.date\n" +
                " where cc.deleted_at is null\n" +
                " group by o._id, o.date, o.acc_fr_id, o.acc_to_id, o.amount, o.comment , o.acc_fr_name, o.acc_from_is_operating, o.acc_to_name, o.acc_to_is_operating, \n" +
                " o.currency_id,  cc._id \n" +
                " ) o \n" +
                "left join v_currency_conversion_rate r\n" +
                "  on r.to_currency_id =o.to_currency_id and r.from_currency_id = o.currency_id and o.max_date_rate = r.date;\n");

    }




    @Override
    public SQLiteDatabase getWritableDatabase() {
        SQLiteDatabase db = super.getWritableDatabase();
        db.execSQL("PRAGMA foreign_keys = ON;");
        return db;
    }

    private void CreateCurrencyEntry(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + WalletContract.CurrencyEntry.TABLE_NAME + " (" + currencyTableBody() + ");");
/*CREATE TABLE currency (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, iso_code TEXT NOT NULL, icon_id INTEGER NOT NULL,
CONSTRAINT fk_currency_icon_id FOREIGN KEY (icon_id) REFERENCES icons (_id));
*/
    }

    private void CreateAccountOperatingEntry(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + WalletContract.AccountOperatingEntry.TABLE_NAME + "\n ("
                + WalletContract.AccountOperatingEntry._ID + " INTEGER PRIMARY KEY, \n"
                + WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID + " INTEGER NOT NULL, \n"
                + SYNC_COLUMNS_DDL + ",\n"
                +"CONSTRAINT fk_account_operating_currencies_id FOREIGN KEY ("
                 +WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID
                +") REFERENCES "+WalletContract.CurrencyEntry.TABLE_NAME+" ("
                +WalletContract.CurrencyEntry._ID
                +")\n, CONSTRAINT fk_account_operating_id FOREIGN KEY ("
                +WalletContract.AccountOperatingEntry._ID
                +") REFERENCES "+WalletContract.AccountEntry.TABLE_NAME+" ("
                +WalletContract.AccountEntry._ID
                +"));");
/*CREATE TABLE account_operating (_id INTEGER PRIMARY KEY, currency_id TEXT NOT NULL,
CONSTRAINT fk_currencies_id FOREIGN KEY (currency_id) REFERENCES currencies (_id));*/
    }

    private void InsertAccountOperatingEntry(SQLiteDatabase db, long accId, long curId) {
        ContentValues values = new ContentValues();
        values.put(WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID, curId);
        values.put(WalletContract.AccountOperatingEntry._ID, accId);
        // uuid = account.uuid того же счёта — см. javadoc backfillAccountOperatingUuid ниже,
        // тот же инвариант (wallet-api/schema/table/account_operating.sql).
        Cursor c = db.rawQuery("SELECT uuid FROM " + WalletContract.AccountEntry.TABLE_NAME
                + " WHERE " + WalletContract.AccountEntry._ID + "=?", new String[]{String.valueOf(accId)});
        try {
            if (c.moveToFirst()) values.put(WalletContract.AccountOperatingEntry.COLUMN_UUID, c.getString(0));
        } finally {
            c.close();
        }

        long newRowId = db.insert(WalletContract.AccountOperatingEntry.TABLE_NAME, null, values);
    }


    private void CreateOperationEntry_v_3(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + WalletContract.OperationEntry.TABLE_NAME + " (\n"
                + WalletContract.OperationEntry._ID + " INTEGER PRIMARY KEY AUTOINCREMENT, \n"
                + WalletContract.OperationEntry.COLUMN_DATE + " DATETIME NOT NULL, \n"
                + WalletContract.OperationEntry.COLUMN_FROM + " INTEGER NOT NULL REFERENCES "+WalletContract.AccountEntry.TABLE_NAME+" ("+WalletContract.AccountEntry._ID+"), \n"
                + WalletContract.OperationEntry.COLUMN_TO + " INTEGER NOT NULL REFERENCES "+WalletContract.AccountEntry.TABLE_NAME+" ("+WalletContract.AccountEntry._ID+"), \n"
                + WalletContract.OperationEntry.COLUMN_AMOUNT + " DECIMAL(15,2) NOT NULL, \n"
                + WalletContract.OperationEntry.COLUMN_COMMENT + " TEXT NOT NULL, \n"
                + SYNC_COLUMNS_DDL + ");");

/*CREATE TABLE transaction (
_id INTEGER PRIMARY KEY AUTOINCREMENT,
date DATETIME NOT NULL,
acc_fr_id INTEGER NOT NULL REFERENCES accounts (_id),
acc_to_id INTEGER NOT NULL REFERENCES accounts (_id),
amount_rc DECIMAL(15,2) NULL,
amount_fc DECIMAL(15,2),
amount_tc DECIMAL(15,2), comment TEXT NOT NULL);
-- operation definition*/
    }



    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 68) {
            // Full recreate for very old versions — falls through to drop+create below
        } else {
            // Incremental migrations for 68+
            if (oldVersion < 69) {
                db.execSQL("ALTER TABLE " + WalletContract.CurrencyEntry.TABLE_NAME
                        + " ADD COLUMN " + WalletContract.CurrencyEntry.COLUMN_PRECISION
                        + " INTEGER NOT NULL DEFAULT 2");
            }
            if (oldVersion < 70) {
                db.execSQL("ALTER TABLE " + WalletContract.CurrencyPairEntry.TABLE_NAME
                        + " ADD COLUMN " + WalletContract.CurrencyPairEntry.COLUMN_PRECISION
                        + " INTEGER NOT NULL DEFAULT 5");
            }
            if (oldVersion < 71) {
                // Только добавляем таблицу связей — операции трогать нельзя, их тут тысячи.
                CreateOperationLink(db);
            }
            if (oldVersion < 72) {
                // org/docs/TODO_mobile_sync.md, Фаза 3, п.1 — см. javadoc
                // addSyncColumns/backfillUuid/createSyncTriggers выше, почему это
                // несколько шагов на таблицу, а не единый ALTER.
                addSyncColumns(db, WalletContract.AccountEntry.TABLE_NAME);
                addSyncColumns(db, WalletContract.AccountOperatingEntry.TABLE_NAME);
                addSyncColumns(db, WalletContract.CurrencyEntry.TABLE_NAME);
                addSyncColumns(db, WalletContract.CurrencyPairEntry.TABLE_NAME);
                addSyncColumns(db, WalletContract.CurrencyPairRateEntry.TABLE_NAME);
                addSyncColumns(db, WalletContract.CurrencyConversionEntry.TABLE_NAME);
                addSyncColumns(db, WalletContract.IconEntry.TABLE_NAME);
                addSyncColumns(db, WalletContract.OperationEntry.TABLE_NAME);
                if (oldVersion >= 71) {
                    // oldVersion < 71 уже создал operation_link ЗАНОВО чуть выше
                    // через CreateOperationLink() — она сразу включает
                    // SYNC_COLUMNS_DDL, повторный ALTER здесь упал бы на
                    // "duplicate column name".
                    addSyncColumns(db, WalletContract.OperationLinkEntry.TABLE_NAME);
                }

                backfillUuid(db, WalletContract.AccountEntry.TABLE_NAME, WalletContract.AccountEntry._ID);
                // НЕ generic backfillUuid — account_operating.uuid обязан совпасть с
                // account.uuid того же счёта (см. javadoc ниже), а не быть своим
                // случайным значением.
                backfillAccountOperatingUuid(db);
                backfillUuid(db, WalletContract.CurrencyEntry.TABLE_NAME, WalletContract.CurrencyEntry._ID);
                backfillUuid(db, WalletContract.CurrencyPairEntry.TABLE_NAME, WalletContract.CurrencyPairEntry._ID);
                backfillUuid(db, WalletContract.CurrencyPairRateEntry.TABLE_NAME,
                        WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE,
                        WalletContract.CurrencyPairRateEntry.COLUMN_DATE);
                backfillUuid(db, WalletContract.CurrencyConversionEntry.TABLE_NAME,
                        WalletContract.CurrencyConversionEntry.COLUMN_FROM_CURRENCY_ID,
                        WalletContract.CurrencyConversionEntry.COLUMN_TO_CURRENCY_ID,
                        WalletContract.CurrencyConversionEntry.COLUMN_NUM);
                backfillUuid(db, WalletContract.IconEntry.TABLE_NAME, WalletContract.IconEntry._ID);
                backfillUuid(db, WalletContract.OperationEntry.TABLE_NAME, WalletContract.OperationEntry._ID);
                // Безопасно всегда: для oldVersion<71 таблица только что создана
                // пустой (backfill — no-op), для oldVersion>=71 реально заполняет
                // уже существующие строки.
                backfillUuid(db, WalletContract.OperationLinkEntry.TABLE_NAME,
                        WalletContract.OperationLinkEntry.COLUMN_MAIN_ID,
                        WalletContract.OperationLinkEntry.COLUMN_LINK_ID);

                // Снимаем обычный (не частичный) column-level UNIQUE с
                // account.name/currency.name/currency.iso_code/currency_pair.symbol/
                // icon.name — иначе tombstone (см. soft-delete выше) навсегда занимал
                // бы своё имя. ALTER TABLE такое не умеет — только пересборка
                // (см. javadoc rebuildTableWithoutInlineUnique). Порядок —
                // icon → currency → currency_pair → account, по зависимостям FK
                // (не обязателен по факту, см. тот же javadoc, но нагляднее).
                rebuildTableWithoutInlineUnique(db, WalletContract.IconEntry.TABLE_NAME,
                        new String[]{WalletContract.IconEntry._ID, WalletContract.IconEntry.COLUMN_NAME,
                                WalletContract.IconEntry.COLUMN_IMAGE, WalletContract.IconEntry.COLUMN_ICON_TYPE,
                                "uuid", "dirty", "deleted_at", "updated_by_device"},
                        iconTableBody());
                rebuildTableWithoutInlineUnique(db, WalletContract.CurrencyEntry.TABLE_NAME,
                        new String[]{WalletContract.CurrencyEntry._ID, WalletContract.CurrencyEntry.COLUMN_NAME,
                                WalletContract.CurrencyEntry.COLUMN_ISO_NAME, WalletContract.CurrencyEntry.COLUMN_ICON_ID,
                                WalletContract.CurrencyEntry.COLUMN_SYMBOL, WalletContract.CurrencyEntry.COLUMN_PRECISION,
                                "uuid", "dirty", "deleted_at", "updated_by_device"},
                        currencyTableBody());
                rebuildTableWithoutInlineUnique(db, WalletContract.CurrencyPairEntry.TABLE_NAME,
                        new String[]{WalletContract.CurrencyPairEntry._ID, WalletContract.CurrencyPairEntry.COLUMN_SYMBOL,
                                WalletContract.CurrencyPairEntry.COLUMN_BASE_CURRECNCY_ID,
                                WalletContract.CurrencyPairEntry.COLUMN_QUOTE_CURRECNCY_ID,
                                WalletContract.CurrencyPairEntry.COLUMN_PRECISION,
                                "uuid", "dirty", "deleted_at", "updated_by_device"},
                        currencyPairTableBody());
                rebuildTableWithoutInlineUnique(db, WalletContract.AccountEntry.TABLE_NAME,
                        new String[]{WalletContract.AccountEntry._ID, WalletContract.AccountEntry.COLUMN_NAME,
                                WalletContract.AccountEntry.COLUMN_COMMENT, WalletContract.AccountEntry.COLUMN_ICON_ID,
                                WalletContract.AccountEntry.COLUMN_IS_ARCHIVED,
                                "uuid", "dirty", "deleted_at", "updated_by_device"},
                        accountTableBody());
                createPartialUniqueIndexes(db);
                createUuidIndexes(db);

                // Представления не патчатся инкрементально сами по себе (CreateViews
                // раньше вызывалась только из onCreate/full-recreate) — пересоздаём
                // явно; CreateViews сама начинает каждый блок с DROP VIEW IF EXISTS,
                // так что безопасно звать её здесь поверх уже существующих вьюх.
                CreateViews(db);
                createSyncTriggers(db);
            }
            if (oldVersion < 73) {
                // org/docs/TODO_mobile_sync.md — легаси-дубли currency_pair_rate,
                // см. dedupeCurrencyPairRateDuplicates(): на v72 backfillUuid ещё
                // не различал их от настоящих отдельных строк (natural key был
                // full timestamp, не календарная дата), так что каждая такая
                // строка уже получила СВОЙ uuid и без этого шага так и продолжит
                // валить push на uq_currency_pair_rate_ledger/_reference (23505),
                // откатывая ВЕСЬ батч целиком — не только эту таблицу.
                dedupeCurrencyPairRateDuplicates(db);
            }
            if (oldVersion < 74) {
                // Реальная находка (2026-09-05, POST /sync/push, 23503
                // fk_account_operating_account) — см. javadoc
                // repairAccountOperatingUuidMismatch().
                repairAccountOperatingUuidMismatch(db);
            }
            if (oldVersion < 75) {
                // v75: явный признак системности вместо распознавания по имени
                // (AccountItem.isSystemName) — см. javadoc AccountItem.SystemKind.
                // Бэкфилл здесь — ЕДИНСТВЕННОЕ оставшееся легитимное место сравнения
                // по имени: ровно момент превращения старого неявного состояния в
                // новое явное, дальше по коду имя больше нигде не участвует в
                // определении системности.
                if (!columnExists(db, WalletContract.AccountEntry.TABLE_NAME,
                        WalletContract.AccountEntry.COLUMN_SYSTEM_KIND)) {
                    // Апгрейд из oldVersion>=72: колонки ещё нет — добавляем. Апгрейд
                    // из ОЧЕНЬ старой базы (oldVersion<72) её уже завёл шаг v72 чуть
                    // выше через rebuildTableWithoutInlineUnique(accountTableBody()) —
                    // тот код уже видит текущий accountTableBody() с system_kind, так
                    // что здесь ALTER TABLE упал бы на "duplicate column name".
                    db.execSQL("ALTER TABLE " + WalletContract.AccountEntry.TABLE_NAME
                            + " ADD COLUMN " + WalletContract.AccountEntry.COLUMN_SYSTEM_KIND
                            + " INTEGER NOT NULL DEFAULT 0");
                }
                db.execSQL("UPDATE " + WalletContract.AccountEntry.TABLE_NAME
                        + " SET " + WalletContract.AccountEntry.COLUMN_SYSTEM_KIND + " = "
                        + AccountItem.SystemKind.TRANSFER.getValue()
                        + " WHERE " + WalletContract.AccountEntry.COLUMN_NAME
                        + " = '" + AccountItem.SYSTEM_TRANSFER_NAME + "' COLLATE NOCASE");
                db.execSQL("UPDATE " + WalletContract.AccountEntry.TABLE_NAME
                        + " SET " + WalletContract.AccountEntry.COLUMN_SYSTEM_KIND + " = "
                        + AccountItem.SystemKind.TRANSFER_COMMISSION.getValue()
                        + " WHERE " + WalletContract.AccountEntry.COLUMN_NAME
                        + " = '" + AccountItem.SYSTEM_COMMISSION_NAME + "' COLLATE NOCASE");
            }
            if (oldVersion < 76) {
                // v76: явный признак «кошелёк или категория» вместо вычисления через
                // JOIN на account_operating — см. javadoc AccountItem.Kind и
                // wallet-api/schema/table/account.sql (обсуждение 2026-09-09). Бэкфилл
                // здесь — по наличию ЖИВОЙ строки в account_operating: ровно то же
                // правило, что уже применяет WalletDbLoader.LoadAccounts (curId != 0)
                // и v_account_operating, так что результат для уже существующих
                // счетов не меняется, просто перестаёт вычисляться на лету.
                if (!columnExists(db, WalletContract.AccountEntry.TABLE_NAME,
                        WalletContract.AccountEntry.COLUMN_KIND)) {
                    // Апгрейд из oldVersion>=72: колонки ещё нет — добавляем. Апгрейд
                    // из ОЧЕНЬ старой базы (oldVersion<72) её уже завёл шаг v72 чуть
                    // выше через rebuildTableWithoutInlineUnique(accountTableBody()) —
                    // тот код уже видит текущий accountTableBody() с kind, так что
                    // здесь ALTER TABLE упал бы на "duplicate column name".
                    db.execSQL("ALTER TABLE " + WalletContract.AccountEntry.TABLE_NAME
                            + " ADD COLUMN " + WalletContract.AccountEntry.COLUMN_KIND
                            + " INTEGER NOT NULL DEFAULT 0");
                }
                db.execSQL("UPDATE " + WalletContract.AccountEntry.TABLE_NAME
                        + " SET " + WalletContract.AccountEntry.COLUMN_KIND + " = "
                        + AccountItem.Kind.WALLET.getValue()
                        + " WHERE " + WalletContract.AccountEntry._ID + " IN ("
                        + "SELECT " + WalletContract.AccountOperatingEntry._ID
                        + " FROM " + WalletContract.AccountOperatingEntry.TABLE_NAME
                        + " WHERE " + WalletContract.AccountOperatingEntry.COLUMN_DELETED_AT + " IS NULL)");
            }
            if (oldVersion < 77) {
                // v77: явная роль связки operation_link (нога валютного перевода / комиссия)
                // вместо вывода по system_kind счёта-источника (обсуждение — план "Б" по
                // docs/TODO_transfers.md, дальше появятся другие виды связок, которые в окне
                // операции нужно будет отображать иначе, а угадывать по счетам их будет
                // уже не с чем сравнивать).
                if (!columnExists(db, WalletContract.OperationLinkEntry.TABLE_NAME,
                        WalletContract.OperationLinkEntry.COLUMN_TYPE)) {
                    // Апгрейд из oldVersion>=71: колонки ещё нет — добавляем. Апгрейд из
                    // ОЧЕНЬ старой базы (oldVersion<71) уже завела таблицу целиком чуть выше
                    // (CreateOperationLink(db)) ТЕКУЩИМ телом, где type уже есть — здесь
                    // ALTER TABLE упал бы на "duplicate column name".
                    db.execSQL("ALTER TABLE " + WalletContract.OperationLinkEntry.TABLE_NAME
                            + " ADD COLUMN " + WalletContract.OperationLinkEntry.COLUMN_TYPE
                            + " INTEGER NOT NULL DEFAULT 0");
                }
                // Бэкфилл — единственное легитимное место вывода type из system_kind:
                // ровно момент превращения старого неявного состояния в новое явное. DEFAULT
                // уже поставил всем LEG (0); здесь донастраиваем на COMMISSION (1) те связки,
                // чья проводка списывается не с системной категории «Transfer».
                db.execSQL("UPDATE " + WalletContract.OperationLinkEntry.TABLE_NAME
                        + " SET " + WalletContract.OperationLinkEntry.COLUMN_TYPE + " = "
                        + OperationItem.LinkType.COMMISSION.getValue()
                        + " WHERE " + WalletContract.OperationLinkEntry.COLUMN_TYPE + " = "
                        + OperationItem.LinkType.LEG.getValue()
                        + " AND " + WalletContract.OperationLinkEntry.COLUMN_LINK_ID + " IN ("
                        + "SELECT o." + WalletContract.OperationEntry._ID
                        + " FROM " + WalletContract.OperationEntry.TABLE_NAME + " o"
                        + " JOIN " + WalletContract.AccountEntry.TABLE_NAME + " a"
                        + " ON a." + WalletContract.AccountEntry._ID + " = o." + WalletContract.OperationEntry.COLUMN_FROM
                        + " WHERE a." + WalletContract.AccountEntry.COLUMN_SYSTEM_KIND + " <> "
                        + AccountItem.SystemKind.TRANSFER.getValue() + ")");
            }
            if (oldVersion < 78) {
                // v78: реальная находка на живом устройстве (2026-09-11) — более старая
                // версия WalletFillData.ResetIcons мягко удаляла ВСЕ иконки без разбора,
                // даже используемые живым счётом/валютой; icon_id оставался указывать на
                // теперь невидимую строку, и часть экранов подставляла вместо неё системную
                // иконку Android, не из каталога приложения (реальный отчёт: кошелёк
                // "Kenian", валюта "Хорватские куны" — не единственные пострадавшие).
                // ResetIcons сама больше так не делает (см. её javadoc), но уже испорченные
                // ссылки на уже пострадавших устройствах это не чинит — чинит здесь.
                WalletFillData.RepairOrphanedIconLinks(db, mContext);
            }
            if (oldVersion < 79) {
                // v79: правила повторяющихся операций — новая таблица, существующие не трогаем.
                CreateRecurringOperation(db);
            }
            if (oldVersion < 80) {
                // v80: имя счёта уникально без учёта регистра, «ё» = «е» (AccountNames).
                migrateAccountNameIndex(db);
            }
            return;
        }
        // Full recreate for pre-68 databases
        db.execSQL("DROP VIEW IF EXISTS v_operation_eq");
        db.execSQL("DROP VIEW IF EXISTS v_operation");
        db.execSQL("DROP VIEW IF EXISTS v_currency_conversion_rate");
        db.execSQL("DROP VIEW IF EXISTS v_currency_conversion");
        db.execSQL("DROP VIEW IF EXISTS v_account_operating");
        db.execSQL("DROP VIEW IF EXISTS v_account_expanse");
        db.execSQL("DROP TABLE IF EXISTS " + WalletContract.CurrencyConversionEntry.TABLE_NAME);
        db.execSQL("DROP TABLE IF EXISTS " + WalletContract.CurrencyPairRateEntry.TABLE_NAME);
        db.execSQL("DROP TABLE IF EXISTS " + WalletContract.CurrencyPairEntry.TABLE_NAME);
        db.execSQL("DROP TABLE IF EXISTS " + WalletContract.AccountOperatingEntry.TABLE_NAME);
        db.execSQL("DROP TABLE IF EXISTS " + WalletContract.OperationEntry.TABLE_NAME);
        db.execSQL("DROP TABLE IF EXISTS " + WalletContract.AccountEntry.TABLE_NAME);
        db.execSQL("DROP TABLE IF EXISTS " + WalletContract.CurrencyEntry.TABLE_NAME);
        db.execSQL("DROP TABLE IF EXISTS " + WalletContract.IconEntry.TABLE_NAME);
        onCreate(db);
    }

    /**
     * Связи проводок одной операции — см. {@link WalletContract.OperationLinkEntry}.
     * ON DELETE CASCADE: удалили проводку — связь на неё не должна пережить её саму
     * (внешние ключи включены в {@link #getWritableDatabase()}).
     */
    private void CreateOperationLink(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + WalletContract.OperationLinkEntry.TABLE_NAME + " (\n"
                + WalletContract.OperationLinkEntry.COLUMN_MAIN_ID + " INTEGER NOT NULL, \n"
                + WalletContract.OperationLinkEntry.COLUMN_LINK_ID + " INTEGER NOT NULL, \n"
                // v77 — явная роль связки вместо вывода по system_kind счёта-источника,
                // см. javadoc WalletContract.OperationLinkEntry.COLUMN_TYPE и шаг v77 в
                // onUpgrade ниже.
                + WalletContract.OperationLinkEntry.COLUMN_TYPE + " INTEGER NOT NULL DEFAULT 0, \n"
                + SYNC_COLUMNS_DDL + ", \n"
                + " PRIMARY KEY (" + WalletContract.OperationLinkEntry.COLUMN_MAIN_ID + ", "
                + WalletContract.OperationLinkEntry.COLUMN_LINK_ID + "), \n"
                + " CONSTRAINT fk_operation_link_main FOREIGN KEY ("
                + WalletContract.OperationLinkEntry.COLUMN_MAIN_ID + ") REFERENCES "
                + WalletContract.OperationEntry.TABLE_NAME + " (" + WalletContract.OperationEntry._ID
                + ") ON DELETE CASCADE, \n"
                + " CONSTRAINT fk_operation_link_link FOREIGN KEY ("
                + WalletContract.OperationLinkEntry.COLUMN_LINK_ID + ") REFERENCES "
                + WalletContract.OperationEntry.TABLE_NAME + " (" + WalletContract.OperationEntry._ID
                + ") ON DELETE CASCADE);");
    }

    /**
     * Правила повторяющихся операций — см. {@link WalletContract.RecurringOperationEntry}.
     * ON DELETE CASCADE: счёт удалили физически — правило
     * на него без него смысла не имеет. Мягко удалённый счёт правило не убирает, его
     * просто пропускает генератор ({@code RecurringOperationsDb.generateDue}).
     */
    private void CreateRecurringOperation(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + WalletContract.RecurringOperationEntry.TABLE_NAME + " (\n"
                + WalletContract.RecurringOperationEntry._ID + " INTEGER PRIMARY KEY AUTOINCREMENT, \n"
                + WalletContract.RecurringOperationEntry.COLUMN_FROM + " INTEGER NOT NULL REFERENCES "
                + WalletContract.AccountEntry.TABLE_NAME + " (" + WalletContract.AccountEntry._ID + ") ON DELETE CASCADE, \n"
                + WalletContract.RecurringOperationEntry.COLUMN_TO + " INTEGER NOT NULL REFERENCES "
                + WalletContract.AccountEntry.TABLE_NAME + " (" + WalletContract.AccountEntry._ID + ") ON DELETE CASCADE, \n"
                + WalletContract.RecurringOperationEntry.COLUMN_AMOUNT + " DECIMAL(15,2) NOT NULL, \n"
                + WalletContract.RecurringOperationEntry.COLUMN_COMMENT + " TEXT NOT NULL DEFAULT '', \n"
                + WalletContract.RecurringOperationEntry.COLUMN_FREQUENCY + " INTEGER NOT NULL, \n"
                + WalletContract.RecurringOperationEntry.COLUMN_INTERVAL + " INTEGER NOT NULL DEFAULT 1, \n"
                + WalletContract.RecurringOperationEntry.COLUMN_WEEK_DAYS + " INTEGER NOT NULL DEFAULT 0, \n"
                + WalletContract.RecurringOperationEntry.COLUMN_START_DATE + " TEXT NOT NULL, \n"
                + WalletContract.RecurringOperationEntry.COLUMN_END_DATE + " TEXT, \n"
                + WalletContract.RecurringOperationEntry.COLUMN_IS_ACTIVE + " INTEGER NOT NULL DEFAULT 1, \n"
                + WalletContract.RecurringOperationEntry.COLUMN_LAST_DATE + " TEXT);");
    }

    private void CreateCategory_v_3(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + WalletContract.AccountEntry.TABLE_NAME + " (" + accountTableBody() + ");");

 /*  CREATE TABLE accounts (_id INTEGER PRIMARY KEY AUTOINCREMENT,
name TEXT NOT NULL, comment TEXT NOT NULL, icon_id INT NOT NULL,
CONSTRAINT fk_accounts_icon_id FOREIGN KEY (icon_id)
REFERENCES icons (_id));*/
    }



}
