package com.example.wallettransactions

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.wallettransactions.Data.DerivedRates
import com.example.wallettransactions.Data.WalletDbHelper
import com.example.wallettransactions.Data.WalletDbLoader
import com.example.wallettransactions.Data.WalletFillData
import com.example.wallettransactions.DataItems.Accounts.AccountList
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList
import com.example.wallettransactions.DataItems.IconList

class Singleton {
    private var dbHelper: WalletDbHelper? = null
    private var readableDatabase: SQLiteDatabase? = null
    private var writeableDatabase: SQLiteDatabase? = null

    private var iconList: IconList? = null
    private var accounts: AccountList? = null
    private var currencyList: CurrencyList? = null
    private var currencyPairList: CurrencyPairList? = CurrencyPairList()

    // synchronized: базу открывают и главный поток, и фоновые (DbInitActivity, воркеры
    // WorkManager). Без него два потока могли завести по своему WalletDbHelper.
    @Synchronized
    fun getReadableDatabase(context: Context): SQLiteDatabase {
        var db = readableDatabase
        if (db == null) {
            db = getDbHelper(context).readableDatabase
            readableDatabase = db
        }
        // Кэш составных курсов (v93): хэндл живёт долго, а курсы/пары/маршруты могли измениться
        // после последнего открытия — перестраиваем кэш, если триггеры пометили его устаревшим.
        DerivedRates.ensureFresh(db)
        return db!!
    }

    @Synchronized
    fun getWritableDatabase(context: Context): SQLiteDatabase {
        var db = writeableDatabase
        if (db == null) {
            db = getDbHelper(context).writableDatabase
            writeableDatabase = db
        }
        DerivedRates.ensureFresh(db)
        return db!!
    }

    fun getIconList(context: Context): IconList {
        var list = iconList
        if (list == null) {
            list = loadIconList(context)
            iconList = list
        }
        return list
    }

    fun reloadIconList(context: Context) {
        if (iconList != null) {
            iconList = loadIconList(context)
        }
    }

    /**
     * Иконки из БД плюс гарантия заглушки "unknown": её [IconList.getUnknownIcon]
     * берут экраны для пустого слота (TransactionActivity.bindSlot и др.) и пишут её
     * `_id` в `icon_id` новых счетов/валют. Без неё в `icon` — после
     * «Удалить ВСЁ» без пересева справочников — getUnknownIcon() отдавал null и экран
     * новой операции падал NPE (issue #18). Заводим её в БД, а не только в памяти: id
     * должен проходить FK. Своя (ORIGIN_LOCAL) — синк её не дублирует, см. SyncEngine.applyIcon.
     */
    private fun loadIconList(context: Context): IconList {
        var list = IconList()
        WalletDbLoader.LoadIcons(getReadableDatabase(context), list)
        if (list.unknownIcon == null
            && WalletFillData.EnsureUnknownIcon(getWritableDatabase(context), context)
        ) {
            list = IconList()
            WalletDbLoader.LoadIcons(getReadableDatabase(context), list)
        }
        return list
    }

    fun getAccounts(context: Context): AccountList {
        var list = accounts
        if (list == null) {
            list = AccountList()
            accounts = list

            WalletDbLoader.LoadAccounts(
                getReadableDatabase(context), list,
                getCurrencyList(context),
                getIconList(context), null
            )
        }
        return list
    }

    /**
     * Перечитывает счета из БД в тот же экземпляр списка.
     *
     * Новый [AccountList] тут заводить нельзя: на текущий объект подписаны адаптеры
     * (`onListChanged`), поэтому список чистится и заполняется на месте.
     *
     * Звать обязательно после любой правки таблицы `account` в обход `AccountList` —
     * иначе кэш держит счета, которых в БД уже нет, и импорт молча уходит в UPDATE по мёртвому id.
     */
    fun reloadAccounts(context: Context) {
        val list = accounts ?: return

        list.clear()
        WalletDbLoader.LoadAccounts(
            getReadableDatabase(context), list,
            getCurrencyList(context),
            getIconList(context), null
        )
        // notifyObservers() отсюда не зовём: импорт крутится в фоновом потоке, а экраны
        // и так перечитывают данные в onResume.
    }

    /**
     * Ставит флаг архива в кэше счетов (если он уже загружен) — без Context и без перечитывания:
     * вызывается из `DebtAutoArchive`, которому после записи флага в БД нужно, чтобы экраны
     * «Долги» и «Кошельки», читающие кэш, сразу увидели архивный заём.
     */
    fun markAccountsArchived(ids: Collection<Int>) {
        val list = accounts ?: return
        for (id in ids) {
            val item = list.getAccountById(id)
            if (item != null) item.setIsArchived(true)
        }
    }

    fun getCurrencyList(context: Context): CurrencyList {
        var list = currencyList
        if (list == null) {
            list = CurrencyList()
            currencyList = list

            WalletDbLoader.LoadCurrencyList(getReadableDatabase(context), context)
        }
        return list
    }

    /**
     * Перечитывает валюты из БД в тот же экземпляр списка — тот же приём,
     * что [reloadAccounts] (не заводим новый [CurrencyList],
     * чтобы не оторвать держателей уже полученной ссылки). Звать после
     * любой правки таблицы `currency` в обход обычных экранов —
     * сейчас единственный вызывающий `SyncEngine.pullAll` (см.
     * org/docs/TODO_mobile_sync.md, "Открытые вопросы"): без reload фоновый
     * pull не обновил бы то, что уже показано на экране, до перезапуска
     * приложения.
     *
     * `CurrencyList.clear()` — унаследованный `ArrayList.clear()`,
     * чистит только сам список; собственный `Hashtable` (для
     * `getCurrencyById`) он не трогает — и на этом держится перечитывание
     * на месте: `addCurrency` находит в нём объект с тем же id и
     * обновляет его поля, а не заводит новый (android-wallet#8). Поэтому
     * счета, пары и валюта эквивалента видят правку без собственного reload.
     * По мягко удалённым валютам (их LoadCurrencyList больше не вернёт) в нём
     * остаётся недостижимая по спискам запись; если это текущая валюта
     * эквивалента, её заменит `PeriodFilterState.onCurrenciesReloaded`.
     *
     * Звать и после записи валюты на самом телефоне (`CurrencyFormActivity`):
     * `onCurrenciesReloaded` поднимает ревизию, и табы перерисуют шапку.
     */
    fun reloadCurrencyList(context: Context) {
        val list = currencyList ?: return

        list.clear()
        WalletDbLoader.LoadCurrencyList(getReadableDatabase(context), context)
        PeriodFilterState.get(context).onCurrenciesReloaded(context)
    }

    @Synchronized
    fun getDbHelper(context: Context): WalletDbHelper {
        var helper = dbHelper
        if (helper == null) {
            helper = WalletDbHelper(context.applicationContext)
            dbHelper = helper
        }
        return helper
    }

    fun getCurrencyPairList(context: Context): CurrencyPairList {
        var list = currencyPairList
        if (list == null) {
            list = CurrencyPairList()
            currencyPairList = list
        }

        WalletDbLoader.LoadCurrencyPairList(getReadableDatabase(context), getCurrencyList(context), list)

        return list
    }

    /**
     * Перечитывает пары/курсы из БД в тот же экземпляр списка — см.
     * [reloadCurrencyList], тот же довод. `currencyPairList`
     * заведён сразу в поле (не лениво, в отличие от остальных кэшей) —
     * поэтому здесь нет проверки на null, всегда безопасно перезалить.
     */
    fun reloadCurrencyPairList(context: Context) {
        val list = currencyPairList!!
        list.clear()
        WalletDbLoader.LoadCurrencyPairList(
            getReadableDatabase(context), getCurrencyList(context),
            list
        )
    }

    companion object {
        @JvmField
        val instance = Singleton()
    }
}
