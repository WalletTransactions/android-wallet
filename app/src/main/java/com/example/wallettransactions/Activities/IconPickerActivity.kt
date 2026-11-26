package com.example.wallettransactions.Activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

import com.example.wallettransactions.DataItems.Icon
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton

/**
 * Сетка иконок (activity_icon_picker.xml, прототип 2a — блок iconPickOpen).
 *
 * В прототипе это буквенные глифы, но в приложении иконки — настоящие битмапы
 * из IconList. Тип иконки («Категория», «Флаг») — только каталог: любую иконку можно
 * поставить и кошельку, и валюте, поэтому показываем все, каталогами с заголовками;
 * тип из extra лишь ставит свой каталог первым и задаёт тип для «+ Новая иконка».
 */
class IconPickerActivity : AppCompatActivity() {

    companion object {
        const val ARG_TITLE = "IconPicker.Title"
        const val ARG_ICON_TYPE = "IconPicker.IconType"
        const val ARG_CURRENT_ID = "IconPicker.CurrentId"
        private const val SPAN = 5
        private const val TYPE_HEADER = 0
        private const val TYPE_CELL = 1
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
    private lateinit var grid: RecyclerView
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
        val grouped = Singleton.instance.getIconList(this).groupedByType
        // Каталог, за которым пришли, — первым; остальные в порядке объявления типов.
        val order = ArrayList<Icon.IconType>()
        order.add(type)
        for (t in Icon.IconType.values()) if (t != type) order.add(t)

        val rows = ArrayList<Row>()
        for (t in order) {
            val icons = grouped[t] ?: continue
            if (icons.isEmpty()) continue
            rows.add(Row.Header(t))
            for (icon in icons) rows.add(Row.Cell(icon))
        }

        val layout = GridLayoutManager(this, SPAN)
        layout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (rows[position] is Row.Header) SPAN else 1
        }
        grid.layoutManager = layout
        grid.adapter = IconAdapter(rows)
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

    private sealed class Row {
        class Header(val type: Icon.IconType) : Row()
        class Cell(val icon: Icon) : Row()
    }

    private inner class IconAdapter(private val rows: List<Row>) :
            RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.Header) TYPE_HEADER else TYPE_CELL

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == TYPE_HEADER) {
                val title = TextView(parent.context)
                title.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                val d = resources.displayMetrics.density
                title.setPadding(0, (14 * d).toInt(), 0, (6 * d).toInt())
                title.textSize = 13f
                title.setTextColor(ContextCompat.getColor(parent.context, R.color.colorTextSecondary))
                return object : RecyclerView.ViewHolder(title) {}
            }
            val cell = LayoutInflater.from(parent.context)
                .inflate(R.layout.icon_picker_item_layout, parent, false)
            return object : RecyclerView.ViewHolder(cell) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder.itemView as TextView).setText(row.type.label)
                is Row.Cell -> {
                    val icon = row.icon
                    val image = holder.itemView.findViewById<ImageView>(R.id.iconCell)
                    image.setImageBitmap(icon.bitmap)
                    image.setBackgroundResource(if (icon.id == currentIconId)
                        R.drawable.bg_icon_cell_selected else R.drawable.bg_icon_cell)
                    // Отступы между ячейками: у сетки RecyclerView своих spacing нет.
                    val m = (5 * resources.displayMetrics.density).toInt()
                    (holder.itemView.layoutParams as ViewGroup.MarginLayoutParams)
                        .setMargins(m, m, m, m)
                    holder.itemView.setOnClickListener { pickIcon(icon.id) }
                }
            }
        }
    }
}
