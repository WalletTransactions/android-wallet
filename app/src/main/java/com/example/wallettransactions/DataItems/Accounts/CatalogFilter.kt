package com.example.wallettransactions.DataItems.Accounts

import com.example.wallettransactions.DataItems.Accounts.AccountItem.CategoryKind
import java.util.EnumSet

/**
 * Фильтры справочника «Кошельки и категории» (openspec catalog-filters-panel, спека
 * account-screens «Панель фильтров справочника» и «Фильтр вида категорий в справочнике»).
 * <p>
 * Чистое состояние и правила без Android: решение о видимости строки принимается по значениям,
 * чтобы его проверял JUnit без устройства. Не путать с [CategoryKindFilter]: тот решает, какие
 * категории предлагает слот «Статья расхода/дохода» (там «Расход» включает «Оба» и выбранная
 * категория остаётся), а здесь совпадение с видом точное.
 * <p>
 * Фильтры живут, пока открыт справочник, и не сохраняются; исходное состояние самой панели
 * задают настройки приложения, а не этот класс.
 */
class CatalogFilter {

    /** Тип кошелька в справочнике; «Все» — без отбора. */
    enum class WalletType { ALL, REGULAR, ASSET, DEBT }

    /** Чип «Показывать архивные»: общий для обеих вкладок. */
    var showArchived: Boolean = false

    /** Вкладка «Кошельки»: одиночный выбор. */
    var walletType: WalletType = WalletType.ALL

    /** Вкладка «Категории»: мультивыбор, логика ИЛИ; пусто — все категории. */
    private val kinds: MutableSet<CategoryKind> = EnumSet.noneOf(CategoryKind::class.java)

    fun hasKind(kind: CategoryKind): Boolean = kinds.contains(kind)

    /** Тап по чипу вида: добавляет вид или убирает его. Возвращает, выбран ли он теперь. */
    fun toggleKind(kind: CategoryKind): Boolean {
        if (!kinds.remove(kind)) {
            kinds.add(kind)
            return true
        }
        return false
    }

    /**
     * Показывать ли категорию. Без выбранных видов — все; иначе только обычная категория,
     * чей вид равен одному из выбранных (точное совпадение: «Расход» не включает «Оба»).
     * Системные категории вида не имеют и при выбранном виде скрываются.
     */
    fun matchesCategory(kind: CategoryKind?, isSystem: Boolean): Boolean {
        if (kinds.isEmpty()) return true
        if (isSystem) return false
        return kind != null && kinds.contains(kind)
    }

    /** Показывать ли кошелёк при выбранном типе: «Обычные» — не актив и не заём. */
    fun matchesWallet(isAsset: Boolean, isDebt: Boolean): Boolean = when (walletType) {
        WalletType.ALL -> true
        WalletType.ASSET -> isAsset
        WalletType.DEBT -> isDebt
        WalletType.REGULAR -> !isAsset && !isDebt
    }

    /**
     * Число включённых нестандартных условий для бейджа кнопки «Фильтры»: архив (общий) плюс
     * группа текущей вкладки. Группа — одно условие, сколько бы чипов в ней ни нажали; условия
     * скрытой вкладки не считаются.
     */
    fun activeCount(onWallets: Boolean): Int {
        var count = if (showArchived) 1 else 0
        count += if (onWallets) {
            if (walletType != WalletType.ALL) 1 else 0
        } else {
            if (kinds.isNotEmpty()) 1 else 0
        }
        return count
    }

    fun reset() {
        showArchived = false
        walletType = WalletType.ALL
        kinds.clear()
    }
}
