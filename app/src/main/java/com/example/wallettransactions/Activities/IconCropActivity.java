package com.example.wallettransactions.Activities;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.example.wallettransactions.R;
import com.example.wallettransactions.Views.CircleCropView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Круглая обрезка выбранного изображения. Открывается из IconEditDialog,
 * возвращает путь к PNG в кеше: за кругом альфа 0.
 *
 * Обрезаем сами, а не чужим редактором по «com.android.camera.action.CROP» —
 * почему, расписано в IconEditDialog.launchCrop().
 */
public class IconCropActivity extends AppCompatActivity {

    public static final String EXTRA_SOURCE_URI = "source_uri";
    public static final String EXTRA_OUTPUT_SIZE = "output_size";
    public static final String EXTRA_RESULT_FILE = "result_file";

    /**
     * Потолок стороны для показа. Больше не нужно: в базу уходит 256px, а
     * 12-мегапиксельный снимок в ARGB_8888 — это 48 МБ и OutOfMemory там,
     * где раньше выпадала «не удалось открыть изображение».
     */
    private static final int MAX_PREVIEW_PX = 2048;

    private CircleCropView cropView;
    private int outputSize;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_icon_crop);

        ((TextView) findViewById(R.id.screenTitle)).setText("Обрезка иконки");
        cropView = findViewById(R.id.cropView);

        outputSize = getIntent().getIntExtra(EXTRA_OUTPUT_SIZE, 256);
        Uri source = getIntent().getParcelableExtra(EXTRA_SOURCE_URI);
        if (source == null) source = getIntent().getData();

        if (source == null) {
            failWith("Изображение не передано");
            return;
        }

        try {
            cropView.setImage(decodeForPreview(source));
        } catch (Exception | OutOfMemoryError e) {
            failWith("Не удалось открыть изображение: " + e.getMessage());
        }
    }

    /** Декодирует с прореживанием и разворачивает по EXIF. */
    private Bitmap decodeForPreview(Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream probe = getContentResolver().openInputStream(uri);
        try {
            BitmapFactory.decodeStream(probe, null, bounds);
        } finally {
            if (probe != null) probe.close();
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
            throw new IllegalArgumentException("формат не распознан");

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > MAX_PREVIEW_PX
                || bounds.outHeight / options.inSampleSize > MAX_PREVIEW_PX)
            options.inSampleSize *= 2;

        Bitmap bitmap;
        InputStream in = getContentResolver().openInputStream(uri);
        try {
            bitmap = BitmapFactory.decodeStream(in, null, options);
        } finally {
            if (in != null) in.close();
        }
        if (bitmap == null) throw new IllegalArgumentException("декодер вернул пусто");

        return applyExifOrientation(uri, bitmap);
    }

    /**
     * Снимки с камеры лежат в файле как есть, а «верх» задан тегом EXIF —
     * без этого портретное фото приезжает в обрезку лежащим на боку.
     */
    private Bitmap applyExifOrientation(Uri uri, Bitmap bitmap) {
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        InputStream in = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in != null)
                orientation = new ExifInterface(in).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception ignored) {
            // Нет EXIF (png, webp, скриншот) — оставляем как есть
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
        }

        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:      matrix.postRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180:     matrix.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270:     matrix.postRotate(270); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:   matrix.postScale(1, -1); break;
            default: return bitmap;
        }

        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) bitmap.recycle();
        return rotated;
    }

    public void onApplyCrop(View view) {
        if (!cropView.hasImage()) {
            failWith("Изображение не загружено");
            return;
        }

        try {
            Bitmap cropped = cropView.getCroppedBitmap(outputSize);

            // Через файл, а не через extra: Binder режет транзакцию около 1 МБ,
            // и большая иконка молча не доехала бы до диалога.
            File out = new File(getCacheDir(), "icon_crop_" + System.currentTimeMillis() + ".png");
            FileOutputStream stream = new FileOutputStream(out);
            try {
                // PNG, а не WEBP: обрезка промежуточная, терять край круга ни к чему.
                cropped.compress(Bitmap.CompressFormat.PNG, 100, stream);
            } finally {
                stream.close();
            }

            setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESULT_FILE, out.getAbsolutePath()));
            finish();
        } catch (Exception | OutOfMemoryError e) {
            failWith("Не удалось обрезать: " + e.getMessage());
        }
    }

    public void btnUp(View view) {
        setResult(RESULT_CANCELED);
        finish();
    }

    private void failWith(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        setResult(RESULT_CANCELED);
        finish();
    }
}
