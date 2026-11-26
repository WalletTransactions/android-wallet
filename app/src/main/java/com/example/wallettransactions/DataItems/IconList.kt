package com.example.wallettransactions.DataItems

import android.graphics.Bitmap

class IconList {
    private val fIconList = HashMap<Int, Icon>()

    private val cUnknownIconName = "unknown"
    private var cUnknownIcon: Icon? = null

    fun GetResource(iconId: Int): Bitmap? {
        if (iconId == 0) {
            return cUnknownIcon!!.bitmap
        } else
            return fIconList[iconId]!!.bitmap
    }

    val unknownIcon: Icon?
        get() = cUnknownIcon

    fun AddIcon(id: Int, name: String, bitmap: Bitmap?): Icon {
        val ic = Icon(id, name, bitmap)
        fIconList[id] = ic
        if (name == cUnknownIconName)
            cUnknownIcon = ic

        return ic
    }

    fun AddIcon(id: Int, name: String, bitmap: Bitmap?, type: Icon.IconType): Icon {
        return AddIcon(id, name, bitmap, type, false)
    }

    fun AddIcon(id: Int, name: String, bitmap: Bitmap?, type: Icon.IconType, fromServer: Boolean): Icon {
        val ic = Icon(id, name, bitmap, type, fromServer)
        fIconList[id] = ic
        if (name == cUnknownIconName)
            cUnknownIcon = ic

        return ic
    }

    fun getIconById(iconId: Int?): Icon? {
        return if (iconId == null) null else fIconList[iconId]
    }

    /**
     * Как {@link #getIconById}, но никогда не возвращает {@code null} — реальная находка
     * на устройстве (2026-09-11): {@code account.icon_id}/{@code currency.icon_id} — {@code
     * NOT NULL} FK (см. schema/table/account.sql, currency.sql), по всей базе кода
     * читается как инвариант "у счёта/валюты всегда есть живая иконка". Ссылка иногда всё
     * же оказывается испорченной (старый баг {@code WalletFillData.ResetIcons}, см. её
     * javadoc и {@code WalletDbHelper.onUpgrade}, v78, который чинит уже испорченное на
     * диске) — тогда {@link #getIconById} честно возвращает {@code null}, и что показать
     * вместо неё, решает уже каждый экран по-своему, вплоть до системной заглушки Android
     * (не из каталога приложения) или {@code NullPointerException}. Загрузчикам этих двух
     * сущностей (см. {@code WalletDbLoader.LoadAccounts}/{@code LoadCurrencyList}) нужен
     * именно этот метод — держит инвариант "иконка всегда есть" при ЗАГРУЗКЕ, не в каждом
     * потребителе отдельно. Пикерам (там, где {@code getIcon()==null} специально значит
     * "пользователь ещё не выбрал") эта замена не нужна — они держатся обычного
     * {@link #getIconById}.
     */
    fun getIconByIdOrUnknown(iconId: Int?): Icon? {
        val icon = getIconById(iconId)
        return icon ?: cUnknownIcon
    }

    fun getIconByName(name: String?): Icon? {
        for (icon in fIconList.entries) {
            if (icon.value.name == name)
                return icon.value
        }
        return null
    }

    val all: Collection<Icon>
        get() = fIconList.values

    fun size(): Int {
        return fIconList.size
    }

    val groupedByType: Map<Icon.IconType, List<Icon>>
        get() {
            val grouped = HashMap<Icon.IconType, MutableList<Icon>>()
            for (type in Icon.IconType.values()) {
                grouped[type] = ArrayList()
            }
            for (icon in fIconList.values) {
                grouped[icon.type]!!.add(icon)
            }
            return grouped
        }

    companion object {
        /**
         * Оставляет в группах только иконки, в названии которых есть строка поиска (openspec
         * unified-reference-list-layout, спека icon-library): подстрока без учёта регистра и
         * пробелов по краям, «ё» = «е» — то же правило, что у остальных справочников
         * ({@link com.example.wallettransactions.Data.AccountNames#matchesSearch}). Пустая строка
         * оставляет всё. Группы остаются в карте, но пустые — скрывает вызывающий экран.
         */
        @JvmStatic
        fun filterGroupedByName(
            grouped: Map<Icon.IconType, List<Icon>>, query: String?
        ): Map<Icon.IconType, List<Icon>> {
            val result = HashMap<Icon.IconType, List<Icon>>()
            for (e in grouped.entries) {
                val kept = ArrayList<Icon>()
                for (icon in e.value) {
                    if (com.example.wallettransactions.Data.AccountNames.matchesSearch(icon.name, query)) {
                        kept.add(icon)
                    }
                }
                result[e.key] = kept
            }
            return result
        }
    }

}
