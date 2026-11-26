package com.example.wallettransactions.Activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView

import com.example.wallettransactions.DataItems.Icon
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton

/**
 * Сетка иконок (activity_icon_picker.xml, прототип 2a — блок iconPickOpen).
 *
 * В прототипе это буквенные глифы, но в приложении иконки — настоящие битмапы
 * из IconList, поэтому показываем их, отфильтровав по типу: для валют — флаги.
 */
class IconPickerActivity : AppCompatActivity() {

    companion object {
        const val ARG_TITLE = "IconPicker.Title"
        const val ARG_ICON_TYPE = "IconPicker.IconType"
        const val ARG_CURRENT_ID = "IconPicker.CurrentId"
        const val RESULT_ICON_ID = "IconPicker.ResultIconId"

        @JvmStatic
        fun buildIntent(ctx: Context, title: String?, type: Icon.IconType, currentId: Int): Intent {
            val intent = Intent(ctx, IconPickerActivity::class.java)
            intent.putExtra(ARG_TITLE, title)
            intent.putExtra(ARG_ICON_TYPE, type.value)
            intent.putExtra(ARG_CURRENT_ID, currentId)
            return intent
        }
    }

    private var currentIconId = 0
    private lateinit var type: Icon.IconType
    private lateinit var grid: GridView
    private var currentDialog: IconEditDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_icon_picker)

        val title = intent.getStringExtra(ARG_TITLE)
        if (title != null) findViewById<TextView>(R.id.pickerTitle).text = title

        currentIconId = intent.getIntExtra(ARG_CURRENT_ID, -1)

        type = Icon.IconType.fromValue(
                intent.getIntExtra(ARG_ICON_TYPE, Icon.IconType.CATEGORY.value))

        grid = findViewById(R.id.gridIcons)
        fillGrid()
    }

    private fun fillGrid() {
        val icons = ArrayList<Icon>()
        for (icon in Singleton.instance.getIconList(this).all) {
            if (icon.type == type) icons.add(icon)
        }

        grid.adapter = IconAdapter(this, icons)
        grid.setOnItemClickListener { _, _, position, _ -> pickIcon(icons[position].id) }
    }

    private fun pickIcon(iconId: Int) {
        val result = Intent()
        result.putExtra(RESULT_ICON_ID, iconId)
        setResult(RESULT_OK, result)
        finish()
    }

    /** Заводит иконку прямо из выбора: тип берётся из того, за чем сюда пришли. */
    fun onNewIcon(view: View) {
        val dialog = IconEditDialog.newIcon(this, type, object : IconEditDialog.OnIconSavedListener {
            override fun onIconSaved(icon: Icon) {
                // Её и создавали ради этого выбора — возвращаем сразу.
                pickIcon(icon.id)
            }

            override fun onIconDeleted(iconId: Int) {
                fillGrid()
            }
        })
        currentDialog = dialog
        dialog.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Диалог сам ходит в фотопикер и в обрезку — коды запросов его.
        currentDialog?.onActivityResult(requestCode, resultCode, data)
    }

    fun btnUp(view: View) {
        finish()
    }

    private inner class IconAdapter(context: Context, icons: List<Icon>)
        : ArrayAdapter<Icon>(context, R.layout.icon_picker_item_layout, icons) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.icon_picker_item_layout, parent, false)

            val icon = getItem(position)!!
            val image = cell.findViewById<ImageView>(R.id.iconCell)
            image.setImageBitmap(icon.bitmap)
            image.setBackgroundResource(if (icon.id == currentIconId)
                R.drawable.bg_icon_cell_selected else R.drawable.bg_icon_cell)

            return cell
        }
    }
}
