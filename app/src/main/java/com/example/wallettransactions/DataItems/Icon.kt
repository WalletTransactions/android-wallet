package com.example.wallettransactions.DataItems

import android.graphics.Bitmap
import android.graphics.Matrix

class Icon @JvmOverloads constructor(
    val id: Int,
    val name: String,
    val bitmap: Bitmap?,
    var type: IconType = IconType.CATEGORY,
    /** {@code icon.origin = 0} (openspec server-managed-icons): только просмотр, правит администратор на сайте. */
    val isFromServer: Boolean = false
) {
    private fun getResizedBitmap(bm: Bitmap, newWidth: Int, newHeight: Int): Bitmap {
        val width = bm.width
        val height = bm.height
        val scaleWidth = (newWidth.toFloat()) / width
        val scaleHeight = (newHeight.toFloat()) / height
        // CREATE A MATRIX FOR THE MANIPULATION
        val matrix = Matrix()
        // RESIZE THE BIT MAP
        matrix.postScale(scaleWidth, scaleHeight)

        // "RECREATE" THE NEW BITMAP
        val resizedBitmap = Bitmap.createBitmap(
            bm, 0, 0, width, height, matrix, false
        )
        return resizedBitmap
    }

    enum class IconType(
        val value: Int,
        /** Ресурс подписи типа (openspec android-i18n). */
        val label: Int
    ) {
        CATEGORY(0, com.example.wallettransactions.R.string.icon_type_category),
        FLAG(1, com.example.wallettransactions.R.string.icon_type_flag);

        companion object {
            @JvmStatic
            fun fromValue(value: Int): IconType {
                if (value == FLAG.value) return FLAG
                return CATEGORY
            }
        }
    }

    private var fBitmap32: Bitmap? = null
    private var fBitmap64: Bitmap? = null

    val bitmap32: Bitmap
        get() {
            if (fBitmap32 == null)
                fBitmap32 = getResizedBitmap(bitmap!!, 42, 42)
            return fBitmap32!!
        }

    val bitmap64: Bitmap
        get() {
            if (fBitmap64 == null)
                fBitmap64 = getResizedBitmap(bitmap!!, 164, 164)
            return fBitmap64!!
        }
}
