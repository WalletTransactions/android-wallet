package com.example.wallettransactions.Activities.Fragments

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.wallettransactions.R
import com.example.wallettransactions.security.AppLock

class FilePickerFragment : Fragment() {

    private var btnChooseFile: Button? = null
    private var btnFormatInfo: Button? = null
    private var tvItemPath: TextView? = null

    private var fileUri: Uri? = null
    private var filePath: String? = null

    /** Заголовок и текст диалога «Формат» — задаются извне (ImportActivity),
     *  пока не заданы, кнопка спрятана (см. fragment_file_picker.xml). */
    private var formatTitle: String? = null
    private var formatContent: CharSequence? = null

    fun GetFileUri(): Uri? {
        return fileUri
    }

    fun GetFileName(): String? {
        return filePath
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment
        val rootView = inflater.inflate(R.layout.fragment_file_picker, container, false)

        val chooseBtn = rootView.findViewById<Button>(R.id.btn_choose_file)
        val formatBtn = rootView.findViewById<Button>(R.id.btn_format_info)
        val pathView = rootView.findViewById<TextView>(R.id.tv_file_path)
        btnChooseFile = chooseBtn
        btnFormatInfo = formatBtn
        tvItemPath = pathView
        pathView.isEnabled = false
        pathView.focusable = View.NOT_FOCUSABLE

        chooseBtn.setOnClickListener {
            // "*/*" — эта же фрагмент-обёртка используется и для CSV, и для JSON-
            // импорта (ImportActivity); документ-провайдер не всегда отдаёт .json
            // как "text/*", а сама структура файла (заголовок CSV / JSONArray)
            // проверяется уже при разборе, не по MIME здесь.
            var chooseFile = Intent(Intent.ACTION_OPEN_DOCUMENT)
            chooseFile.type = "*/*"
            chooseFile = Intent.createChooser(chooseFile, "Open file")
            AppLock.allowNextLeave()
            startActivityForResult(chooseFile, PICKFILE_RESULT_CODE)
        }
        formatBtn.setOnClickListener { showFormatDialog() }
        // setFormatInfo() мог быть вызван раньше onCreateView (ImportActivity
        // вызывает его сразу после findFragmentById в своём onCreate, а порядок
        // относительно инфляции фрагмента не гарантирован) — досказать видимость.
        if (formatContent != null) formatBtn.visibility = View.VISIBLE
        return rootView
    }

    /**
     * Подключает кнопку «Формат»: показывает `title`/`content` в
     * копируемом диалоге вместо того, чтобы держать описание формата в общем
     * поле статуса импорта. `content` может нести цветные спаны
     * (см. ImportActivity — атрибуты JSON подсвечены) — TextView в диалоге их
     * отрисует как есть.
     */
    fun setFormatInfo(title: String?, content: CharSequence?) {
        this.formatTitle = title
        this.formatContent = content
        btnFormatInfo?.visibility = View.VISIBLE
    }

    private fun showFormatDialog() {
        val content = formatContent ?: return
        val ctx = requireContext()

        val text = TextView(ctx)
        text.text = content
        text.setTextIsSelectable(true)
        text.typeface = Typeface.MONOSPACE
        text.textSize = 13f
        text.setTextColor(ContextCompat.getColor(ctx, R.color.colorTextPrimary))
        val pad = (16 * resources.displayMetrics.density).toInt()
        text.setPadding(pad, pad, pad, pad)

        val scroll = ScrollView(ctx)
        scroll.addView(text)

        AlertDialog.Builder(ctx)
            .setTitle(formatTitle)
            .setView(scroll)
            .setPositiveButton(R.string.action_copy) { _, _ ->
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(formatTitle, content.toString()))
                Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    fun getPath(uri: Uri): String? {
        val path: String?
        val projection = arrayOf(MediaStore.Files.FileColumns.DATA)
        val cursor = activity!!.contentResolver.query(uri, projection, null, null, null)

        if (cursor == null) {
            path = uri.path
        } else {
            cursor.moveToFirst()
            val columnIndex = cursor.getColumnIndexOrThrow(projection[0])
            path = cursor.getString(columnIndex)
            cursor.close()
        }

        return if (path == null || path.isEmpty()) uri.path else path
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            PICKFILE_RESULT_CODE ->
                if (resultCode == -1) {
                    val uri = data!!.data
                    fileUri = uri
                    filePath = getPath(uri!!)

                    //filePath = filePath;//.substring(filePath.lastIndexOf(":")+1);; //fileUri.toString()  fileUri.getPath()
                    tvItemPath!!.text = filePath
                }
        }
    }

    companion object {
        const val PICKFILE_RESULT_CODE = 1
    }
}
