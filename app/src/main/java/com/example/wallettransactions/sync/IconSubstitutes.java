package com.example.wallettransactions.sync;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.wallettransactions.Data.WalletContract;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Иконка-заместитель для своей иконки (openspec server-managed-icons, design п. 3).
 * <p>
 * Свои иконки ({@code icon.origin = 1}) на сервер не уходят, а строки {@code account}/
 * {@code currency} на сервере обязаны ссылаться на существующую иконку
 * ({@code 409 missing_reference}, wallet-api). Поэтому в push вместо своей иконки
 * подставляется иконка «с сервера» ({@code origin = 0}, живая), по порядку:
 * <ol>
 *   <li>та же картинка — SHA-256 байт совпадает (хеш картинки иконки с сервера равен её
 *   {@code content_sha256}, хранить его отдельно не нужно);</li>
 *   <li>то же имя без учёта регистра;</li>
 *   <li>иконка {@code unknown} (на телефоне общие и свои серверные иконки не различаются —
 *   это просто иконка с сервера с таким именем).</li>
 * </ol>
 * Нет ни одного — {@code null}: строку откладывает вызывающий код.
 * <p>
 * Индекс строится один раз — на раунд push или на страницу pull ({@link #load}).
 * ~150 иконок по ~10 КиБ хешируются за миллисекунды.
 */
final class IconSubstitutes {

    static final String UNKNOWN_NAME = "unknown";

    private final Map<String, String> bySha256 = new HashMap<>();
    private final Map<String, String> byName = new HashMap<>();

    /** Пустой индекс; иконки с сервера добавляются {@link #addServerIcon}. */
    IconSubstitutes() {}

    /**
     * Иконка с сервера как кандидат в заместители. При совпадении ключа выигрывает
     * добавленная первой — {@link #load} идёт по {@code _id}, выбор детерминирован.
     */
    void addServerIcon(String uuid, String name, String sha256) {
        if (uuid == null) return;
        if (sha256 != null) bySha256.putIfAbsent(sha256, uuid);
        if (name != null) byName.putIfAbsent(nameKey(name), uuid);
    }

    /** uuid заместителя для своей иконки с этим именем и хешем картинки, или {@code null}. */
    String substituteFor(String name, String sha256) {
        if (sha256 != null) {
            String same = bySha256.get(sha256);
            if (same != null) return same;
        }
        if (name != null) {
            String sameName = byName.get(nameKey(name));
            if (sameName != null) return sameName;
        }
        return byName.get(UNKNOWN_NAME);
    }

    String substituteFor(String name, byte[] image) {
        return substituteFor(name, image == null ? null : sha256Hex(image));
    }

    /**
     * Индекс по иконкам с сервера из локальной базы: живым и удалённым только на телефоне
     * (android-wallet#50). Иконки в push не уходят, поэтому {@code deleted_at} с
     * {@code dirty = 1} — локальное удаление (прежняя версия «Удалить данные» удаляла и
     * иконки с сервера), на сервере иконка жива и ссылка на неё проходит FK. Tombstone из
     * pull приходит с {@code dirty = 0} — такая иконка на сервере удалена и не берётся.
     */
    static IconSubstitutes load(SQLiteDatabase db) {
        IconSubstitutes subs = new IconSubstitutes();
        Cursor c = db.rawQuery("SELECT " + WalletContract.IconEntry.COLUMN_UUID
                + ", " + WalletContract.IconEntry.COLUMN_NAME
                + ", " + WalletContract.IconEntry.COLUMN_IMAGE
                + " FROM " + WalletContract.IconEntry.TABLE_NAME
                + " WHERE " + WalletContract.IconEntry.COLUMN_ORIGIN + " = " + WalletContract.IconEntry.ORIGIN_SERVER
                + " AND (" + WalletContract.IconEntry.COLUMN_DELETED_AT + " IS NULL"
                + " OR " + WalletContract.IconEntry.COLUMN_DIRTY + " = 1)"
                + " ORDER BY " + WalletContract.IconEntry._ID, null);
        try {
            while (c.moveToNext()) {
                byte[] image = c.getBlob(2);
                subs.addServerIcon(c.getString(0), c.getString(1), image == null ? null : sha256Hex(image));
            }
        } finally {
            c.close();
        }
        return subs;
    }

    /** Один и тот же uuid в разных написаниях (с дефисами и без, см. {@code SyncEngine.localUuid}). */
    static boolean sameUuid(String a, String b) {
        if (a == null || b == null) return false;
        return a.toLowerCase(Locale.ROOT).replace("-", "").equals(b.toLowerCase(Locale.ROOT).replace("-", ""));
    }

    /** Тот же SHA-256, что считает сервер для {@code content_sha256} (wallet-api, blob.cpp). */
    static String sha256Hex(byte[] bytes) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e); // SHA-256 гарантирован на любой Android
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest(bytes)) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    private static String nameKey(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
