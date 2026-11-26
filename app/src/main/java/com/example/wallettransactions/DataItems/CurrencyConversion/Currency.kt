package com.example.wallettransactions.DataItems.CurrencyConversion


import com.example.wallettransactions.DataItems.Icon

class Currency(
    val id: Int,
    iso: String,
    name: String,
    icon: Icon?,
    symbol: String,
    precision: Int,
    /** v85: предлагать ли валюту в пикерах эквивалента (см. EquivalentCurrencies). */
    showInEquivalent: Boolean,
    /** v85: строка из общего справочника сервера — форма только для чтения. */
    shared: Boolean
) {
    var iso: String = iso
        private set
    private var storedName: String = name
    var icon: Icon? = icon
        private set
    var symbol: String = symbol
        private set
    var precision: Int = precision
        private set
    var isShowInEquivalent: Boolean = showInEquivalent
        private set
    var isShared: Boolean = shared
        private set

    /**
     * Показываемое имя: у общей валюты — название Android по коду на текущем языке
     * телефона (иначе с сервера), у своей — сохранённое, чтобы «Доллары нал» с кодом USD
     * не стали «US Dollar» (spec currency-directory, new-user-default-set design D10).
     * Хранимое имя общей валюты не меняется и на телефоне не правится.
     */
    val name: String
        get() = if (isShared) com.example.wallettransactions.Data.CurrencyNames.sharedDisplayName(iso, storedName) ?: storedName else storedName

    constructor(id: Int, iso: String, name: String, icon: Icon?, symbol: String, precision: Int) :
        this(id, iso, name, icon, symbol, precision, true, false)

    /**
     * Перезаписывает поля той же строки {@code currency} (id не меняется). Зовёт только
     * {@link CurrencyList#addCurrency} при перечитывании справочника — так счета, пары и
     * текущая валюта эквивалента, взявшие ссылку раньше, видят новые данные (android-wallet#8).
     */
    internal fun update(
        iso: String, name: String, icon: Icon?, symbol: String, precision: Int,
        showInEquivalent: Boolean, shared: Boolean
    ) {
        this.iso = iso
        this.storedName = name
        this.icon = icon
        this.symbol = symbol
        this.precision = precision
        this.isShowInEquivalent = showInEquivalent
        this.isShared = shared
    }
}
