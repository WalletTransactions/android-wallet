package com.example.wallettransactions.DataItems;

import android.graphics.Bitmap;
import com.example.wallettransactions.DataItems.Icon;

import java.util.HashMap;
import java.util.Map;


public class IconList {
    private  HashMap<Integer, Icon> fIconList = new HashMap<Integer, Icon>();

    private String cUnknownIconName =  "unknown";
    private Icon cUnknownIcon;

    public Bitmap GetResource(Integer iconId)
    {
        if (iconId == 0)
        {
            return cUnknownIcon.getBitmap();
        }
        else
            return fIconList.get(iconId).getBitmap();
    }

    public Icon getUnknownIcon() {
        return cUnknownIcon;
    }

    public Icon AddIcon(int id, String name, Bitmap bitmap)
    {
        Icon ic = new Icon(id, name, bitmap);
        fIconList.put(id, ic);
        if (name.equals(cUnknownIconName))
            cUnknownIcon = ic;

        return ic;
    }

    public Icon AddIcon(int id, String name, Bitmap bitmap, Icon.IconType type)
    {
        Icon ic = new Icon(id, name, bitmap, type);
        fIconList.put(id, ic);
        if (name.equals(cUnknownIconName))
            cUnknownIcon = ic;

        return ic;
    }

    public Icon getIconById(Integer iconId){
        return fIconList.get(iconId);
    }

    /**
     * Как {@link #getIconById}, но никогда не возвращает {@code null} — реальная находка
     * на устройстве (2026-09-11): {@code account.icon_id}/{@code currency.icon_id} — {@code
     * NOT NULL} FK (см. schema/client/table/account.sql, currency.sql), по всей базе кода
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
    public Icon getIconByIdOrUnknown(Integer iconId) {
        Icon icon = getIconById(iconId);
        return icon != null ? icon : cUnknownIcon;
    }

    public Icon getIconByName(String name)
    {
        for (Map.Entry<Integer,Icon> icon: fIconList.entrySet())
        {
            if (icon.getValue().getName().equals(name))
                return icon.getValue();
        }
        return null;
    }

    public java.util.Collection<Icon> getAll() {
        return fIconList.values();
    }

    public int size() {
        return fIconList.size();
    }

    public java.util.Map<Icon.IconType, java.util.List<Icon>> getGroupedByType() {
        java.util.Map<Icon.IconType, java.util.List<Icon>> grouped = new java.util.HashMap<>();
        for (Icon.IconType type : Icon.IconType.values()) {
            grouped.put(type, new java.util.ArrayList<>());
        }
        for (Icon icon : fIconList.values()) {
            grouped.get(icon.getType()).add(icon);
        }
        return grouped;
    }

}
