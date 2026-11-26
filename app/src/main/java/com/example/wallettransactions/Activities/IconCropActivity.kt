package com.example.wallettransactions.Activities

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.widget.TextView
import android.widget.Toast

import com.example.wallettransactions.R
import com.example.wallettransactions.Views.CircleCropView

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Круглая обрезка выбранного изображения. Открывается из IconEditDialog,
 * возвращает путь к PNG в кеше: за кругом альфа 0.
 *
 * Обрезаем сами, а не чужим редактором по «com.android.camera.action.CROP» —
 * почему, расписано в IconEditDialog.launchCrop().
 */
class IconCropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SOURCE_URI = "source_uri"
        const val EXTRA_OUTPUT_SIZE = "output_size"
        const val EXTRA_RESULT_FILE = "result_file"

        /**
         * Потолок стороны для показа. Больше не нужно: в базу уходит 256px, а
         * 12-мегапиксельный снимок в ARGB_8888 — это 48 МБ и OutOfMemory там,
         * где раньше выпадала «не удалось открыть изображение».
         */
        private const val MAX_PREVIEW_PX = 2048
    }

    private lateinit var cropView: CircleCropView
    private var outputSize = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_icon_crop)

        findViewById<TextView>(R.id.screenTitle).setText(R.string.icon_crop_title)
        cropView = findViewById(R.id.cropView)

        outputSize = intent.getIntExtra(EXTRA_OUTPUT_SIZE, 256)
        @Suppress("DEPRECATION")
        var source: Uri? = intent.getParcelableExtra(EXTRA_SOURCE_URI)
        if (source == null) source = intent.data

        if (source == null) {
            failWith(getString(R.string.icon_crop_no_image))
            return
        }

        try {
            cropView.setImage(decodeForPreview(source))
        } catch (e: Exception) {
            failWith(getString(R.string.icon_crop_open_failed, e.message))
        } catch (e: OutOfMemoryError) {
            failWith(getString(R.string.icon_crop_open_failed, e.message))
        }
    }

    /** Декодирует с прореживанием и разворачивает по EXIF. */
    @Throws(Exception::class)
    private fun decodeForPreview(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        val probe = contentResolver.openInputStream(uri)
        try {
            BitmapFactory.decodeStream(probe, null, bounds)
        } finally {
            probe?.close()
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
            throw IllegalArgumentException(getString(R.string.icon_crop_unknown_format))

        val options = BitmapFactory.Options()
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        options.inSampleSize = 1
        while (bounds.outWidth / options.inSampleSize > MAX_PREVIEW_PX
                || bounds.outHeight / options.inSampleSize > MAX_PREVIEW_PX)
            options.inSampleSize *= 2

        val bitmap: Bitmap?
        val stream = contentResolver.openInputStream(uri)
        try {
            bitmap = BitmapFactory.decodeStream(stream, null, options)
        } finally {
            stream?.close()
        }
        if (bitmap == null) throw IllegalArgumentException(getString(R.string.icon_crop_decode_empty))

        return applyExifOrientation(uri, bitmap)
    }

    /**
     * Снимки с камеры лежат в файле как есть, а «верх» задан тегом EXIF —
     * без этого портретное фото приезжает в обрезку лежащим на боку.
     */
    private fun applyExifOrientation(uri: Uri, bitmap: Bitmap): Bitmap {
        var orientation = ExifInterface.ORIENTATION_NORMAL
        var stream: InputStream? = null
        try {
            stream = contentResolver.openInputStream(uri)
            if (stream != null)
                orientation = ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (ignored: Exception) {
            // Нет EXIF (png, webp, скриншот) — оставляем как есть
        } finally {
            try { stream?.close() } catch (ignored: Exception) { }
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }

        val rotated = Bitmap.createBitmap(bitmap, 0, 0,
                bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    fun onApplyCrop(view: View) {
        if (!cropView.hasImage()) {
            failWith(getString(R.string.icon_crop_not_loaded))
            return
        }

        try {
            applyCrop()
        } catch (e: Exception) {
            failWith(getString(R.string.icon_crop_failed, e.message))
        } catch (e: OutOfMemoryError) {
            failWith(getString(R.string.icon_crop_failed, e.message))
        }
    }

    @Throws(Exception::class)
    private fun applyCrop() {
        val cropped = cropView.getCroppedBitmap(outputSize)!!

        // Через файл, а не через extra: Binder режет транзакцию около 1 МБ,
        // и большая иконка молча не доехала бы до диалога.
        val out = File(cacheDir, "icon_crop_" + System.currentTimeMillis() + ".png")
        val stream = FileOutputStream(out)
        try {
            // PNG, а не WEBP: обрезка промежуточная, терять край круга ни к чему.
            cropped.compress(Bitmap.CompressFormat.PNG, 100, stream)
        } finally {
            stream.close()
        }

        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_FILE, out.absolutePath))
        finish()
    }

    fun btnUp(view: View) {
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun failWith(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        setResult(RESULT_CANCELED)
        finish()
    }
}
