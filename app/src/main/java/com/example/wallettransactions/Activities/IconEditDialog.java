package com.example.wallettransactions.Activities;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.provider.MediaStore;
import androidx.core.content.ContextCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.wallettransactions.DataItems.Icon;
import com.example.wallettransactions.Data.WalletDbHelper;
import com.example.wallettransactions.Data.WalletContract;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;

import java.io.ByteArrayOutputStream;
import java.io.File;

/**
 * «Добавить/Редактировать иконку» — один лист dialog_add_edit_icon.xml на оба
 * режима, как RateEditDialog: заголовок и подпись кнопки выбора меняются,
 * btnDelete скрыт для новой иконки.
 */
public class IconEditDialog {

    private static final int REQUEST_GALLERY = 2001;
    private static final int REQUEST_CROP = 2002;

    /**
     * Stored icon edge in pixels. The largest on-screen use is a 48dp grid cell
     * (192px at xxxhdpi) and Icon.getBitmap64() at 164px, so 256 leaves headroom
     * without wasting rows in the BLOB column.
     */
    private static final int ICON_SIZE_PX = 256;

    /** WEBP keeps alpha and is roughly an order of magnitude smaller than PNG. */
    private static final Bitmap.CompressFormat ICON_FORMAT = Bitmap.CompressFormat.WEBP;
    private static final int ICON_QUALITY = 85;

    private Context context;
    private Icon icon;
    private Bitmap selectedBitmap;
    private EditText edtName;
    private TextView chipCategory;
    private TextView chipFlag;
    private Icon.IconType selectedType = Icon.IconType.CATEGORY;
    private ImageView imgPreview;
    private OnIconSavedListener listener;
    private AlertDialog dialog;

    public interface OnIconSavedListener {
        void onIconSaved(Icon icon);
        void onIconDeleted(int iconId);
    }

    public IconEditDialog(Context context, Icon icon, OnIconSavedListener listener) {
        this.context = context;
        this.icon = icon;
        this.listener = listener;
    }

    /**
     * Новая иконка заранее известного типа — им открывается диалог из пикера,
     * где тип задан экраном («Иконка категории», «Иконка валюты»).
     */
    public static IconEditDialog newIcon(Context context, Icon.IconType type, OnIconSavedListener listener) {
        IconEditDialog dialog = new IconEditDialog(context, null, listener);
        dialog.selectedType = type;
        return dialog;
    }

    public void show() {
        boolean isNew = icon == null;

        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.dialog_add_edit_icon, null);

        edtName = view.findViewById(R.id.edtIconName);
        imgPreview = view.findViewById(R.id.imgIconPreview);
        chipCategory = view.findViewById(R.id.chipCategory);
        chipFlag = view.findViewById(R.id.chipFlag);

        TextView btnSelect = view.findViewById(R.id.btnSelectIcon);
        View btnDelete = view.findViewById(R.id.btnDelete);

        ((TextView) view.findViewById(R.id.dialogTitle))
                .setText(isNew ? "Добавить иконку" : "Редактировать иконку");
        btnSelect.setText(isNew ? "Выбрать изображение" : "Изменить изображение");
        btnDelete.setVisibility(isNew ? View.GONE : View.VISIBLE);

        if (!isNew) {
            edtName.setText(icon.getName());
            selectedBitmap = icon.getBitmap();
            selectedType = icon.getType();
            showPreview(selectedBitmap);
        }
        setupTypeChips();

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setView(view);
        dialog = builder.create();
        // Окно диалога прозрачное — скругление и фон рисует сам bg_dialog_card.
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        btnSelect.setOnClickListener(v -> openGallery());
        imgPreview.setOnClickListener(v -> openGallery());
        btnDelete.setOnClickListener(v -> deleteIcon());
        view.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.btnSave).setOnClickListener(v -> {
            if (isNew) saveNewIcon(); else updateIcon();
        });

        dialog.show();
    }

    private void setupTypeChips() {
        chipCategory.setText(Icon.IconType.CATEGORY.getLabel());
        chipFlag.setText(Icon.IconType.FLAG.getLabel());
        chipCategory.setOnClickListener(v -> selectType(Icon.IconType.CATEGORY));
        chipFlag.setOnClickListener(v -> selectType(Icon.IconType.FLAG));
        selectType(selectedType);
    }

    private void selectType(Icon.IconType type) {
        selectedType = type;
        paintChip(chipCategory, type == Icon.IconType.CATEGORY);
        paintChip(chipFlag, type == Icon.IconType.FLAG);
    }

    /** Тот же способ подсветки чипа, что на экране операции. */
    private void paintChip(TextView chip, boolean active) {
        chip.setBackgroundResource(active ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        chip.setTextColor(ContextCompat.getColor(context,
                active ? R.color.colorSurface : R.color.colorTextSecondary));
    }

    /** Показывает выбранную картинку вместо плашки «+»: круг заполняется целиком. */
    private void showPreview(Bitmap bitmap) {
        imgPreview.setPadding(0, 0, 0, 0);
        // Снимаем android:tint с плашки «+», иначе картинка перекрасится в него.
        imgPreview.setImageTintList(null);
        imgPreview.setImageBitmap(bitmap);
    }

    /**
     * Системный фотопикер (Android 13+), иначе SAF. Оба сами выдают доступ на
     * чтение выбранного файла — в отличие от ACTION_PICK, который опирался на
     * READ_EXTERNAL_STORAGE, а оно на targetSdk 34 больше не действует: оттуда
     * и брались URI, которые потом не открывались.
     */
    private void openGallery() {
        Intent intent;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
            intent.setType("image/*");
        } else {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            ((Activity) context).startActivityForResult(intent, REQUEST_GALLERY);
        } catch (Exception e) {
            Toast.makeText(context, "Не найдено приложение для выбора изображения",
                    Toast.LENGTH_SHORT).show();
        }
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_GALLERY) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null)
                launchCrop(data.getData());
            return;
        }

        if (requestCode == REQUEST_CROP) {
            if (resultCode == Activity.RESULT_OK && data != null)
                loadCroppedFile(data.getStringExtra(IconCropActivity.EXTRA_RESULT_FILE));
        }
    }

    /**
     * Обрезаем своим экраном, а не чужим редактором через
     * «com.android.camera.action.CROP». Тот интент никогда не был частью API,
     * и на него приходило «невозможно отредактировать это изображение» сразу по
     * трём причинам: FLAG_GRANT_WRITE_URI_PERMISSION распространяется только на
     * data интента, а не на URI в extra MediaStore.EXTRA_OUTPUT — писать
     * результат чужому приложению было некуда; на Android 11+ без <queries>
     * приложение вообще не видит редактор; а Галерея с Google Фото отказывают
     * на HEIC и на снимках, которых нет локально. Плюс прямоугольная обрезка
     * не умеет прозрачность за кругом.
     */
    private void launchCrop(Uri imageUri) {
        Intent intent = new Intent(context, IconCropActivity.class);
        intent.setData(imageUri);
        intent.putExtra(IconCropActivity.EXTRA_SOURCE_URI, imageUri);
        intent.putExtra(IconCropActivity.EXTRA_OUTPUT_SIZE, ICON_SIZE_PX);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ((Activity) context).startActivityForResult(intent, REQUEST_CROP);
    }

    private void loadCroppedFile(String path) {
        if (path == null) return;

        File file = new File(path);
        try {
            Bitmap cropped = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (cropped == null) {
                Toast.makeText(context, "Обрезка не читается", Toast.LENGTH_SHORT).show();
                return;
            }
            selectedBitmap = cropped;
            showPreview(selectedBitmap);
        } finally {
            file.delete();
        }
    }

    /** Encodes the icon for the BLOB column. */
    private byte[] encodeIcon(Bitmap bitmap) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(ICON_FORMAT, ICON_QUALITY, stream);
        return stream.toByteArray();
    }

    private void saveNewIcon() {
        String name = edtName.getText().toString().trim();
        if (name.isEmpty() || selectedBitmap == null) {
            Toast.makeText(context, "Заполните все поля и выберите иконку", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            SQLiteDatabase db = new WalletDbHelper(context).getWritableDatabase();

            android.content.ContentValues values = new android.content.ContentValues();
            values.put(WalletContract.IconEntry.COLUMN_NAME, name);
            values.put(WalletContract.IconEntry.COLUMN_IMAGE, encodeIcon(selectedBitmap));
            values.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, selectedType.getValue());
            // uuid — v72 (servers/TODO_mobile_sync.md, Фаза 3): нужен для pull (icon
            // приходит от сервера по uuid), хотя push иконок пока не реализован.
            values.put(WalletContract.IconEntry.COLUMN_UUID, java.util.UUID.randomUUID().toString());

            long id = db.insert(WalletContract.IconEntry.TABLE_NAME, null, values);
            db.close();

            if (id > 0) {
                // Reload icons cache from database
                Singleton.instance.reloadIconList(context);

                Icon newIcon = new Icon((int) id, name, selectedBitmap, selectedType);
                listener.onIconSaved(newIcon);
                if (dialog != null) dialog.dismiss();
                Toast.makeText(context, "Иконка добавлена", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(context, "Ошибка при сохранении: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void updateIcon() {
        String name = edtName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(context, "Введите имя иконки", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            SQLiteDatabase db = new WalletDbHelper(context).getWritableDatabase();

            android.content.ContentValues values = new android.content.ContentValues();
            values.put(WalletContract.IconEntry.COLUMN_NAME, name);
            values.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, selectedType.getValue());

            if (selectedBitmap != null && selectedBitmap != icon.getBitmap())
                values.put(WalletContract.IconEntry.COLUMN_IMAGE, encodeIcon(selectedBitmap));

            db.update(WalletContract.IconEntry.TABLE_NAME, values,
                    WalletContract.IconEntry._ID + " = ?",
                    new String[]{String.valueOf(icon.getId())});
            db.close();

            // Reload icons cache from database
            Singleton.instance.reloadIconList(context);

            Icon updated = new Icon(icon.getId(), name,
                    selectedBitmap != null ? selectedBitmap : icon.getBitmap(), selectedType);
            listener.onIconSaved(updated);
            if (dialog != null) dialog.dismiss();
            Toast.makeText(context, "Иконка обновлена", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(context, "Ошибка при обновлении: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteIcon() {
        // Использование иконки в account/currency проверяет не код, а внешний ключ
        // (fk_account_icon_id / fk_currency_icon_id на icon._id): getWritableDatabase()
        // включает PRAGMA foreign_keys = ON, и delete ниже упадёт с
        // SQLiteConstraintException, если иконка ещё где-то используется.
        new AlertDialog.Builder(context)
                .setTitle("Удалить иконку?")
                .setMessage(icon.getName())
                .setPositiveButton("Удалить", (d, w) -> {
                    SQLiteDatabase dbWrite = new WalletDbHelper(context).getWritableDatabase();
                    try {
                        dbWrite.delete(WalletContract.IconEntry.TABLE_NAME,
                                WalletContract.IconEntry._ID + " = ?",
                                new String[]{String.valueOf(icon.getId())});

                        // Reload icons cache from database
                        Singleton.instance.reloadIconList(context);

                        listener.onIconDeleted(icon.getId());
                        if (dialog != null) dialog.dismiss();
                        Toast.makeText(context, "Иконка удалена", Toast.LENGTH_SHORT).show();
                    } catch (SQLiteConstraintException e) {
                        Toast.makeText(context,
                                "Иконка используется в счетах или валютах. Удаление невозможно",
                                Toast.LENGTH_LONG).show();
                    } finally {
                        dbWrite.close();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }
}
