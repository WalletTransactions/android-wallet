package com.example.wallettransactions.DataItems;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** openspec unified-reference-list-layout, спека icon-library: поиск иконок по названию. */
public class IconListFilterTest {

    private static Icon icon(int id, String name, Icon.IconType type) {
        return new Icon(id, name, null, type);
    }

    private static Map<Icon.IconType, List<Icon>> grouped() {
        Map<Icon.IconType, List<Icon>> m = new HashMap<>();
        for (Icon.IconType t : Icon.IconType.values()) m.put(t, new ArrayList<>());
        m.get(Icon.IconType.CATEGORY).add(icon(1, "Еда", Icon.IconType.CATEGORY));
        m.get(Icon.IconType.CATEGORY).add(icon(2, "Ёлка", Icon.IconType.CATEGORY));
        m.get(Icon.IconType.CATEGORY).add(icon(3, "Транспорт", Icon.IconType.CATEGORY));
        m.get(Icon.IconType.FLAG).add(icon(4, "Беларусь", Icon.IconType.FLAG));
        return m;
    }

    private static List<Integer> ids(List<Icon> icons) {
        List<Integer> r = new ArrayList<>();
        for (Icon i : icons) r.add(i.getId());
        return r;
    }

    @Test
    public void empty_query_keeps_everything() {
        Map<Icon.IconType, List<Icon>> r = IconList.filterGroupedByName(grouped(), "");
        assertEquals(3, r.get(Icon.IconType.CATEGORY).size());
        assertEquals(1, r.get(Icon.IconType.FLAG).size());
        r = IconList.filterGroupedByName(grouped(), null);
        assertEquals(3, r.get(Icon.IconType.CATEGORY).size());
    }

    @Test
    public void matches_substring_ignoring_case() {
        Map<Icon.IconType, List<Icon>> r = IconList.filterGroupedByName(grouped(), "ЕДА");
        assertEquals(java.util.Arrays.asList(1), ids(r.get(Icon.IconType.CATEGORY)));
        assertTrue(r.get(Icon.IconType.FLAG).isEmpty());
    }

    @Test
    public void matches_inside_the_name_and_trims_edge_spaces() {
        Map<Icon.IconType, List<Icon>> r = IconList.filterGroupedByName(grouped(), "  порт ");
        assertEquals(java.util.Arrays.asList(3), ids(r.get(Icon.IconType.CATEGORY)));
    }

    @Test
    public void yo_equals_ye() {
        Map<Icon.IconType, List<Icon>> r = IconList.filterGroupedByName(grouped(), "елка");
        assertEquals(java.util.Arrays.asList(2), ids(r.get(Icon.IconType.CATEGORY)));
        r = IconList.filterGroupedByName(grouped(), "ёда");
        assertEquals(java.util.Arrays.asList(1), ids(r.get(Icon.IconType.CATEGORY)));
    }

    @Test
    public void group_without_matches_is_empty_and_nothing_found_is_all_empty() {
        Map<Icon.IconType, List<Icon>> r = IconList.filterGroupedByName(grouped(), "беларусь");
        assertTrue(r.get(Icon.IconType.CATEGORY).isEmpty());
        assertEquals(java.util.Arrays.asList(4), ids(r.get(Icon.IconType.FLAG)));

        r = IconList.filterGroupedByName(grouped(), "zzz");
        assertTrue(r.get(Icon.IconType.CATEGORY).isEmpty());
        assertTrue(r.get(Icon.IconType.FLAG).isEmpty());
    }

    @Test
    public void special_characters_are_plain_text() {
        Map<Icon.IconType, List<Icon>> r = IconList.filterGroupedByName(grouped(), "(");
        assertTrue(r.get(Icon.IconType.CATEGORY).isEmpty());
    }
}
