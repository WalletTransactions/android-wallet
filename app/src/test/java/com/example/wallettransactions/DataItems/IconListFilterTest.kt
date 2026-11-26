package com.example.wallettransactions.DataItems

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** openspec unified-reference-list-layout, спека icon-library: поиск иконок по названию. */
class IconListFilterTest {

    private fun icon(id: Int, name: String, type: Icon.IconType): Icon = Icon(id, name, null, type)

    private fun grouped(): Map<Icon.IconType, List<Icon>> {
        val m = HashMap<Icon.IconType, MutableList<Icon>>()
        for (t in Icon.IconType.values()) m[t] = ArrayList()
        m[Icon.IconType.CATEGORY]!!.add(icon(1, "Еда", Icon.IconType.CATEGORY))
        m[Icon.IconType.CATEGORY]!!.add(icon(2, "Ёлка", Icon.IconType.CATEGORY))
        m[Icon.IconType.CATEGORY]!!.add(icon(3, "Транспорт", Icon.IconType.CATEGORY))
        m[Icon.IconType.FLAG]!!.add(icon(4, "Беларусь", Icon.IconType.FLAG))
        return m
    }

    private fun ids(icons: List<Icon>): List<Int> {
        val r = ArrayList<Int>()
        for (i in icons) r.add(i.id)
        return r
    }

    @Test
    fun empty_query_keeps_everything() {
        var r = IconList.filterGroupedByName(grouped(), "")
        assertEquals(3, r[Icon.IconType.CATEGORY]!!.size)
        assertEquals(1, r[Icon.IconType.FLAG]!!.size)
        r = IconList.filterGroupedByName(grouped(), null)
        assertEquals(3, r[Icon.IconType.CATEGORY]!!.size)
    }

    @Test
    fun matches_substring_ignoring_case() {
        val r = IconList.filterGroupedByName(grouped(), "ЕДА")
        assertEquals(listOf(1), ids(r[Icon.IconType.CATEGORY]!!))
        assertTrue(r[Icon.IconType.FLAG]!!.isEmpty())
    }

    @Test
    fun matches_inside_the_name_and_trims_edge_spaces() {
        val r = IconList.filterGroupedByName(grouped(), "  порт ")
        assertEquals(listOf(3), ids(r[Icon.IconType.CATEGORY]!!))
    }

    @Test
    fun yo_equals_ye() {
        var r = IconList.filterGroupedByName(grouped(), "елка")
        assertEquals(listOf(2), ids(r[Icon.IconType.CATEGORY]!!))
        r = IconList.filterGroupedByName(grouped(), "ёда")
        assertEquals(listOf(1), ids(r[Icon.IconType.CATEGORY]!!))
    }

    @Test
    fun group_without_matches_is_empty_and_nothing_found_is_all_empty() {
        var r = IconList.filterGroupedByName(grouped(), "беларусь")
        assertTrue(r[Icon.IconType.CATEGORY]!!.isEmpty())
        assertEquals(listOf(4), ids(r[Icon.IconType.FLAG]!!))

        r = IconList.filterGroupedByName(grouped(), "zzz")
        assertTrue(r[Icon.IconType.CATEGORY]!!.isEmpty())
        assertTrue(r[Icon.IconType.FLAG]!!.isEmpty())
    }

    @Test
    fun special_characters_are_plain_text() {
        val r = IconList.filterGroupedByName(grouped(), "(")
        assertTrue(r[Icon.IconType.CATEGORY]!!.isEmpty())
    }
}
