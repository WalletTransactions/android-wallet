package com.example.wallettransactions.Activities

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import com.example.wallettransactions.DataItems.Icon
import com.example.wallettransactions.Data.WalletDbHelper
import com.example.wallettransactions.Data.WalletContract
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import com.example.wallettransactions.security.AppLock

import android.database.sqlite.SQLiteConstraintException

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * «Добавить/Редактировать иконку» — один лист dialog_add_edit_icon.xml на оба
 * режима, как RateEditDialog: заголовок и подпись кнопки выбора меняются,
 * btnDelete скрыт для новой иконки. Иконка с сервера ([Icon.isFromServer])
 * открывается только для просмотра — см. [showReadOnly].
 */
class IconEditDialog(private val context: Context, private val icon: Icon?,
                     private val listener: OnIconSavedListener) {

    companion object {
        private const val REQUEST_GALLERY = 2001
        private const val REQUEST_CROP = 2002

        /**
         * Stored icon edge in pixels. The largest on-screen use is a 48dp grid cell
         * (192px at xxxhdpi) and Icon.getBitmap64() at 164px, so 256 leaves headroom
         * without wasting rows in the BLOB column.
         */
        private const val ICON_SIZE_PX = 256

        /** WEBP keeps alpha and is roughly an order of magnitude smaller than PNG. */
        private val ICON_FORMAT = Bitmap.CompressFormat.WEBP
        private const val ICON_QUALITY = 85

        /**
         * Новая иконка заранее известного типа — им открывается диалог из пикера,
         * где тип задан экраном («Иконка категории», «Иконка валюты»).
         */
        @JvmStatic
        fun newIcon(context: Context, type: Icon.IconType, listener: OnIconSavedListener): IconEditDialog {
            val dialog = IconEditDialog(context, null, listener)
            dialog.selectedType = type
            return dialog
        }
    }

    private var selectedBitmap: Bitmap? = null
    private lateinit var edtName: EditText
    private lateinit var chipCategory: TextView
    private lateinit var chipFlag: TextView
    private var selectedType: Icon.IconType = Icon.IconType.CATEGORY
    private lateinit var imgPreview: ImageView
    private var dialog: AlertDialog? = null

    interface OnIconSavedListener {
        fun onIconSaved(icon: Icon)
        fun onIconDeleted(iconId: Int)
    }

    fun show() {
        val isNew = icon == null

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.dialog_add_edit_icon, null)

        edtName = view.findViewById(R.id.edtIconName)
        imgPreview = view.findViewById(R.id.imgIconPreview)
        chipCategory = view.findViewById(R.id.chipCategory)
        chipFlag = view.findViewById(R.id.chipFlag)

        val btnSelect = view.findViewById<TextView>(R.id.btnSelectIcon)
        val btnDelete = view.findViewById<View>(R.id.btnDelete)

        view.findViewById<TextView>(R.id.dialogTitle)
                .setText(if (isNew) R.string.icon_dialog_add else R.string.icon_dialog_edit)
        btnSelect.setText(if (isNew) R.string.icon_dialog_pick_image else R.string.icon_dialog_change_image)
        btnDelete.visibility = if (isNew) View.GONE else View.VISIBLE

        if (icon != null) {
            edtName.setText(icon.name)
            selectedBitmap = icon.bitmap
            selectedType = icon.type
            showPreview(selectedBitmap)
        }
        setupTypeChips()

        val builder = AlertDialog.Builder(context)
        builder.setView(view)
        val dlg = builder.create()
        dialog = dlg
        // Окно диалога прозрачное — скругление и фон рисует сам bg_dialog_card.
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        view.findViewById<View>(R.id.btnCancel).setOnClickListener { dlg.dismiss() }
        if (icon != null && icon.isFromServer) {
            showReadOnly(view)
            dlg.show()
            return
        }

        btnSelect.setOnClickListener { openGallery() }
        imgPreview.setOnClickListener { openGallery() }
        btnDelete.setOnClickListener { deleteIcon() }
        view.findViewById<View>(R.id.btnSave).setOnClickListener {
            if (isNew) saveNewIcon() else updateIcon()
        }

        dlg.show()
    }

    /**
     * Иконка с сервера (openspec server-managed-icons): её ведёт администратор на сайте,
     * на телефоне — только просмотр. Видны картинка, имя и подсказка; полей правки, выбора
     * картинки, «Удалить» и «Сохранить» нет.
     */
    private fun showReadOnly(view: View) {
        view.findViewById<TextView>(R.id.dialogTitle).setText(R.string.icon_dialog_title)
        val name = view.findViewById<TextView>(R.id.txtIconNameReadOnly)
        name.text = icon!!.name
        name.visibility = View.VISIBLE
        view.findViewById<View>(R.id.txtServerIconHint).visibility = View.VISIBLE
        for (id in intArrayOf(R.id.btnSelectIcon, R.id.lblIconName, R.id.edtIconName,
                R.id.lblIconType, R.id.iconTypeChips, R.id.btnDelete, R.id.btnSave)) {
            view.findViewById<View>(id).visibility = View.GONE
        }
        imgPreview.isClickable = false
        val close = view.findViewById<TextView>(R.id.btnCancel)
        close.setText(R.string.action_close)
        (close.layoutParams as LinearLayout.LayoutParams).marginEnd = 0
    }

    private fun setupTypeChips() {
        chipCategory.setText(Icon.IconType.CATEGORY.label)
        chipFlag.setText(Icon.IconType.FLAG.label)
        chipCategory.setOnClickListener { selectType(Icon.IconType.CATEGORY) }
        chipFlag.setOnClickListener { selectType(Icon.IconType.FLAG) }
        selectType(selectedType)
    }

    private fun selectType(type: Icon.IconType) {
        selectedType = type
        paintChip(chipCategory, type == Icon.IconType.CATEGORY)
        paintChip(chipFlag, type == Icon.IconType.FLAG)
    }

    /** Тот же способ подсветки чипа, что на экране операции. */
    private fun paintChip(chip: TextView, active: Boolean) {
        chip.setBackgroundResource(if (active) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
        chip.setTextColor(ContextCompat.getColor(context,
                if (active) R.color.colorSurface else R.color.colorTextSecondary))
    }

    /** Показывает выбранную картинку вместо плашки «+»: круг заполняется целиком. */
    private fun showPreview(bitmap: Bitmap?) {
        imgPreview.setPadding(0, 0, 0, 0)
        // Снимаем android:tint с плашки «+», иначе картинка перекрасится в него.
        imgPreview.imageTintList = null
        imgPreview.setImageBitmap(bitmap)
    }

    /**
     * Системный фотопикер (Android 13+), иначе SAF. Оба сами выдают доступ на
     * чтение выбранного файла — в отличие от ACTION_PICK, который опирался на
     * READ_EXTERNAL_STORAGE, а оно на targetSdk 34 больше не действует: оттуда
     * и брались URI, которые потом не открывались.
     */
    private fun openGallery() {
        val intent: Intent
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent = Intent(MediaStore.ACTION_PICK_IMAGES)
            intent.type = "image/*"
        } else {
            intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "image/*"
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        try {
            AppLock.allowNextLeave()
            @Suppress("DEPRECATION")
            (context as Activity).startActivityForResult(intent, REQUEST_GALLERY)
        } catch (e: Exception) {
            Toast.makeText(context, R.string.icon_dialog_no_gallery, Toast.LENGTH_SHORT).show()
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_GALLERY) {
            if (resultCode == Activity.RESULT_OK && data != null && data.data != null)
                launchCrop(data.data!!)
            return
        }

        if (requestCode == REQUEST_CROP) {
            if (resultCode == Activity.RESULT_OK && data != null)
                loadCroppedFile(data.getStringExtra(IconCropActivity.EXTRA_RESULT_FILE))
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
    private fun launchCrop(imageUri: Uri) {
        val intent = Intent(context, IconCropActivity::class.java)
        intent.data = imageUri
        intent.putExtra(IconCropActivity.EXTRA_SOURCE_URI, imageUri)
        intent.putExtra(IconCropActivity.EXTRA_OUTPUT_SIZE, ICON_SIZE_PX)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        @Suppress("DEPRECATION")
        (context as Activity).startActivityForResult(intent, REQUEST_CROP)
    }

    private fun loadCroppedFile(path: String?) {
        if (path == null) return

        val file = File(path)
        try {
            val cropped = BitmapFactory.decodeFile(file.absolutePath)
            if (cropped == null) {
                Toast.makeText(context, R.string.icon_dialog_crop_unreadable, Toast.LENGTH_SHORT).show()
                return
            }
            selectedBitmap = cropped
            showPreview(selectedBitmap)
        } finally {
            file.delete()
        }
    }

    /** Encodes the icon for the BLOB column. */
    private fun encodeIcon(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(ICON_FORMAT, ICON_QUALITY, stream)
        return stream.toByteArray()
    }

    private fun saveNewIcon() {
        val name = edtName.text.toString().trim()
        val bitmap = selectedBitmap
        if (name.isEmpty() || bitmap == null) {
            Toast.makeText(context, R.string.icon_dialog_fill_all, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val db = WalletDbHelper(context).writableDatabase

            val values = ContentValues()
            values.put(WalletContract.IconEntry.COLUMN_NAME, name)
            values.put(WalletContract.IconEntry.COLUMN_IMAGE, encodeIcon(bitmap))
            values.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, selectedType.value)
            // uuid — v72 (org/docs/TODO_mobile_sync.md, Фаза 3), у всех синхронизируемых строк.
            values.put(WalletContract.IconEntry.COLUMN_UUID, UUID.randomUUID().toString())
            // Своя иконка (server-managed-icons): на сервер не уходит, в push вместо неё —
            // иконка-заместитель с сервера (SyncEngine.PushIconRefs).
            values.put(WalletContract.IconEntry.COLUMN_ORIGIN, WalletContract.IconEntry.ORIGIN_LOCAL)

            val id = db.insert(WalletContract.IconEntry.TABLE_NAME, null, values)
            db.close()

            if (id > 0) {
                // Reload icons cache from database
                Singleton.instance.reloadIconList(context)

                val newIcon = Icon(id.toInt(), name, bitmap, selectedType)
                listener.onIconSaved(newIcon)
                dialog?.dismiss()
                Toast.makeText(context, R.string.icon_dialog_added, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.icon_dialog_save_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateIcon() {
        val name = edtName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(context, R.string.icon_dialog_name_empty, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val icon = icon!!
            val db = WalletDbHelper(context).writableDatabase

            val values = ContentValues()
            values.put(WalletContract.IconEntry.COLUMN_NAME, name)
            values.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, selectedType.value)

            val bitmap = selectedBitmap
            if (bitmap != null && bitmap !== icon.bitmap)
                values.put(WalletContract.IconEntry.COLUMN_IMAGE, encodeIcon(bitmap))

            db.update(WalletContract.IconEntry.TABLE_NAME, values,
                    WalletContract.IconEntry._ID + " = ?",
                    arrayOf(icon.id.toString()))
            db.close()

            // Reload icons cache from database
            Singleton.instance.reloadIconList(context)

            val updated = Icon(icon.id, name,
                    bitmap ?: icon.bitmap, selectedType)
            listener.onIconSaved(updated)
            dialog?.dismiss()
            Toast.makeText(context, R.string.icon_dialog_updated, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.icon_dialog_update_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteIcon() {
        // Использование иконки в account/currency проверяет не код, а внешний ключ
        // (fk_account_icon_id / fk_currency_icon_id на icon._id): getWritableDatabase()
        // включает PRAGMA foreign_keys = ON, и delete ниже упадёт с
        // SQLiteConstraintException, если иконка ещё где-то используется.
        val icon = icon!!
        AlertDialog.Builder(context)
                .setTitle(R.string.icon_dialog_delete_question)
                .setMessage(icon.name)
                .setPositiveButton(R.string.action_delete) { _, _ ->
                    val dbWrite = WalletDbHelper(context).writableDatabase
                    try {
                        dbWrite.delete(WalletContract.IconEntry.TABLE_NAME,
                                WalletContract.IconEntry._ID + " = ?",
                                arrayOf(icon.id.toString()))

                        // Reload icons cache from database
                        Singleton.instance.reloadIconList(context)

                        listener.onIconDeleted(icon.id)
                        dialog?.dismiss()
                        Toast.makeText(context, R.string.icon_dialog_deleted, Toast.LENGTH_SHORT).show()
                    } catch (e: SQLiteConstraintException) {
                        Toast.makeText(context, R.string.icon_dialog_in_use, Toast.LENGTH_LONG).show()
                    } finally {
                        dbWrite.close()
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
    }
}
