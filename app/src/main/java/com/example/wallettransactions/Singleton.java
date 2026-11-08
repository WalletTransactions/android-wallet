package com.example.wallettransactions;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import com.example.wallettransactions.DataItems.Accounts.AccountList;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList;
import com.example.wallettransactions.DataItems.IconList;
import com.example.wallettransactions.Data.WalletDbHelper;
import com.example.wallettransactions.Data.WalletDbLoader;
import com.example.wallettransactions.Data.WalletFillData;

public class Singleton {
    public static final Singleton instance = new Singleton();

    private WalletDbHelper dbHelper;
    private SQLiteDatabase readableDatabase;
    private SQLiteDatabase writeableDatabase;

    private IconList iconList;
    private AccountList accounts;
    private CurrencyList currencyList;
    private CurrencyPairList currencyPairList = new CurrencyPairList();


    // synchronized: базу открывают и главный поток, и фоновые (DbInitActivity, воркеры
    // WorkManager). Без него два потока могли завести по своему WalletDbHelper.
    public synchronized SQLiteDatabase getReadableDatabase(Context context){
        if (readableDatabase == null){
            readableDatabase = getDbHelper(context).getReadableDatabase();
        }
        return readableDatabase;
    }

    public synchronized SQLiteDatabase getWritableDatabase(Context context){
        if (writeableDatabase == null){
            writeableDatabase = getDbHelper(context).getWritableDatabase();
        }
        return writeableDatabase;
    }

    public IconList getIconList(Context context){
        if (iconList == null) {
            iconList = loadIconList(context);
        }

        return iconList;
    }

    public void reloadIconList(Context context) {
        if (iconList != null) {
            iconList = loadIconList(context);
        }
    }

    /**
     * Иконки из БД плюс гарантия заглушки "unknown": её {@link IconList#getUnknownIcon}
     * берут экраны для пустого слота (TransactionActivity.bindSlot и др.) и пишут её
     * {@code _id} в {@code icon_id} новых счетов/валют. Без неё в {@code icon} — после
     * «Удалить ВСЁ» без пересева справочников — getUnknownIcon() отдавал null и экран
     * новой операции падал NPE (issue #18). Заводим её в БД, а не только в памяти: id
     * должен проходить FK. Своя (ORIGIN_LOCAL) — синк её не дублирует, см. SyncEngine.applyIcon.
     */
    private IconList loadIconList(Context context) {
        IconList list = new IconList();
        WalletDbLoader.LoadIcons(getReadableDatabase(context), list);
        if (list.getUnknownIcon() == null
                && WalletFillData.EnsureUnknownIcon(getWritableDatabase(context), context)) {
            list = new IconList();
            WalletDbLoader.LoadIcons(getReadableDatabase(context), list);
        }
        return list;
    }


    public AccountList getAccounts(Context context){
        if (accounts == null){
            accounts = new AccountList();

            WalletDbLoader.LoadAccounts(getReadableDatabase(context), accounts,
                    getCurrencyList(context),
                    getIconList(context), null);
        }
        return accounts;
    }

    /**
     * Перечитывает счета из БД в тот же экземпляр списка.
     *
     * Новый {@link AccountList} тут заводить нельзя: на текущий объект подписаны адаптеры
     * ({@code onListChanged}), поэтому список чистится и заполняется на месте.
     *
     * Звать обязательно после любой правки таблицы {@code account} в обход {@code AccountList} —
     * иначе кэш держит счета, которых в БД уже нет, и импорт молча уходит в UPDATE по мёртвому id.
     */
    public void reloadAccounts(Context context) {
        if (accounts == null) return;

        accounts.clear();
        WalletDbLoader.LoadAccounts(getReadableDatabase(context), accounts,
                getCurrencyList(context),
                getIconList(context), null);
        // notifyObservers() отсюда не зовём: импорт крутится в фоновом потоке, а экраны
        // и так перечитывают данные в onResume.
    }

    public CurrencyList getCurrencyList(Context context){
        if (currencyList == null){
            currencyList = new CurrencyList();

            WalletDbLoader.LoadCurrencyList(getReadableDatabase(context), context);
        }
        return currencyList;
    }

    /**
     * Перечитывает валюты из БД в тот же экземпляр списка — тот же приём,
     * что {@link #reloadAccounts} (не заводим новый {@link CurrencyList},
     * чтобы не оторвать держателей уже полученной ссылки). Звать после
     * любой правки таблицы {@code currency} в обход обычных экранов —
     * сейчас единственный вызывающий {@code SyncEngine.pullAll} (см.
     * org/docs/TODO_mobile_sync.md, "Открытые вопросы"): без reload фоновый
     * pull не обновил бы то, что уже показано на экране, до перезапуска
     * приложения.
     * <p>
     * {@code CurrencyList.clear()} — унаследованный {@code ArrayList.clear()},
     * чистит только сам список; собственный {@code Hashtable} (для
     * {@code getCurrencyById}) он не трогает — и на этом держится перечитывание
     * на месте: {@code addCurrency} находит в нём объект с тем же id и
     * обновляет его поля, а не заводит новый (android-wallet#8). Поэтому
     * счета, пары и валюта эквивалента видят правку без собственного reload.
     * По мягко удалённым валютам (их LoadCurrencyList больше не вернёт) в нём
     * остаётся недостижимая по спискам запись; если это текущая валюта
     * эквивалента, её заменит {@code PeriodFilterState.onCurrenciesReloaded}.
     * <p>
     * Звать и после записи валюты на самом телефоне ({@code CurrencyFormActivity}):
     * {@code onCurrenciesReloaded} поднимает ревизию, и табы перерисуют шапку.
     */
    public void reloadCurrencyList(Context context) {
        if (currencyList == null) return;

        currencyList.clear();
        WalletDbLoader.LoadCurrencyList(getReadableDatabase(context), context);
        PeriodFilterState.get(context).onCurrenciesReloaded(context);
    }

    public synchronized WalletDbHelper getDbHelper(Context context) {
        if (dbHelper == null)
            dbHelper = new WalletDbHelper(context.getApplicationContext());

        return dbHelper;
    }

    public CurrencyPairList getCurrencyPairList(Context context) {
        if (currencyPairList == null)
            currencyPairList = new CurrencyPairList();

        WalletDbLoader.LoadCurrencyPairList(getReadableDatabase(context), getCurrencyList(context), currencyPairList);

        return currencyPairList;
    }

    /**
     * Перечитывает пары/курсы из БД в тот же экземпляр списка — см.
     * {@link #reloadCurrencyList}, тот же довод. {@code currencyPairList}
     * заведён сразу в поле (не лениво, в отличие от остальных кэшей) —
     * поэтому здесь нет проверки на null, всегда безопасно перезалить.
     */
    public void reloadCurrencyPairList(Context context) {
        currencyPairList.clear();
        WalletDbLoader.LoadCurrencyPairList(getReadableDatabase(context), getCurrencyList(context),
                currencyPairList);
    }
}
