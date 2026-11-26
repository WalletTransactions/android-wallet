package com.example.wallettransactions.Activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseExpandableListAdapter
import android.widget.ExpandableListView
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView

import com.example.wallettransactions.DataItems.Icon
import com.example.wallettransactions.DataItems.IconList
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import com.example.wallettransactions.security.AppLock

class IconManagerActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_GALLERY = 1001
        private const val REQUEST_CROP = 1002
    }

    private lateinit var expandableListView: ExpandableListView
    private lateinit var iconList: IconList
    private lateinit var typedIcons: Map<Icon.IconType, List<Icon>>
    private lateinit var types: MutableList<Icon.IconType>
    private lateinit var adapter: IconCategoryAdapter
    private var currentDialog: IconEditDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_icon_manager)

        findViewById<TextView>(R.id.screenTitle).setText(R.string.icon_manager_title)

        expandableListView = findViewById(R.id.expandableListView)
        findViewById<View>(R.id.btnAddIcon).setOnClickListener { openAddIconDialog() }

        iconList = Singleton.instance.getIconList(this)
        // Строка поиска живёт на экране; список перестраивается на каждый символ
        // (иконок на телефоне десятки, фильтр в памяти).
        findViewById<EditText>(R.id.iconSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s.toString()
                showIcons()
            }
        })
        showIcons()
    }

    /** Строка поиска по названию иконки (спека icon-library «Поиск по названию»). */
    private var searchQuery = ""

    /** Перечитывает группы с учётом поиска, пересобирает адаптер и пустое состояние. */
    private fun showIcons() {
        loadTypedIcons()
        adapter = IconCategoryAdapter(this, types, typedIcons)
        expandableListView.setAdapter(adapter)
        // Найденное должно быть видно сразу, а не за тапом по группе.
        if (searchQuery.trim().isNotEmpty()) {
            for (i in 0 until adapter.groupCount) expandableListView.expandGroup(i)
        }
        val nothing = types.isEmpty()
        findViewById<View>(R.id.iconsEmpty).visibility = if (nothing) View.VISIBLE else View.GONE
        expandableListView.visibility = if (nothing) View.GONE else View.VISIBLE
    }

    private fun openAddIconDialog() {
        val dialog = IconEditDialog(this, null, object : IconEditDialog.OnIconSavedListener {
            override fun onIconSaved(icon: Icon) {
                refreshIconList()
            }

            override fun onIconDeleted(iconId: Int) {
                refreshIconList()
            }
        })
        currentDialog = dialog
        dialog.show()
    }

    /** Re-reads icons from the DB cache and rebuilds the grouped view. */
    private fun refreshIconList() {
        iconList = Singleton.instance.getIconList(this)
        showIcons()
    }

    private fun loadTypedIcons() {
        typedIcons = IconList.filterGroupedByName(iconList.groupedByType, searchQuery)
        types = ArrayList()
        for (type in Icon.IconType.values()) {
            if (!typedIcons[type]!!.isEmpty()) {
                types.add(type)
            }
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        AppLock.allowNextLeave()
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_GALLERY)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        currentDialog?.onActivityResult(requestCode, resultCode, data)
    }

    fun btnUp(view: View) {
        finish()
    }

    private inner class IconCategoryAdapter(
            private val context: Context,
            private val types: List<Icon.IconType>,
            private val icons: Map<Icon.IconType, List<Icon>>) : BaseExpandableListAdapter() {

        override fun getGroupCount(): Int = types.size

        override fun getChildrenCount(groupPosition: Int): Int {
            val type = types[groupPosition]
            val typeIcons = icons[type]!!
            return (typeIcons.size + 5) / 6 // 6 icons per row
        }

        override fun getGroup(groupPosition: Int): Any = types[groupPosition]

        override fun getChild(groupPosition: Int, childPosition: Int): Any {
            val type = types[groupPosition]
            return icons[type]!!
        }

        override fun getGroupId(groupPosition: Int): Long = groupPosition.toLong()

        override fun getChildId(groupPosition: Int, childPosition: Int): Long = childPosition.toLong()

        override fun hasStableIds(): Boolean = true

        override fun getGroupView(groupPosition: Int, isExpanded: Boolean,
                                  convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(
                    android.R.layout.simple_expandable_list_item_1, parent, false)

            val tv = view.findViewById<TextView>(android.R.id.text1)
            tv.setText(types[groupPosition].label)
            tv.textSize = 18f
            tv.setPadding(40, 20, 0, 20)

            return view
        }

        override fun getChildView(groupPosition: Int, childPosition: Int, isLastChild: Boolean,
                                  convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(
                    R.layout.grid_icons_row, parent, false)

            val gridView = view.findViewById<GridView>(R.id.gridIcons)
            val type = types[groupPosition]
            val typeIcons = icons[type]!!

            val startIndex = childPosition * 6
            val endIndex = Math.min(startIndex + 6, typeIcons.size)

            val rowIcons = ArrayList(typeIcons.subList(startIndex, endIndex))

            val gridAdapter = IconGridAdapter(context, rowIcons)
            gridView.adapter = gridAdapter
            gridView.numColumns = 6

            return view
        }

        override fun isChildSelectable(groupPosition: Int, childPosition: Int): Boolean = true
    }

    private inner class IconGridAdapter(context: Context, icons: List<Icon>)
        : ArrayAdapter<Icon>(context, 0, icons) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(
                    R.layout.item_icon_grid, parent, false)

            val icon = getItem(position)!!
            val imageView = view.findViewById<ImageView>(R.id.imgIcon)
            imageView.setImageBitmap(icon.bitmap)

            view.setOnClickListener {
                val dialog = IconEditDialog(this@IconManagerActivity, icon,
                        object : IconEditDialog.OnIconSavedListener {
                    override fun onIconSaved(icon: Icon) {
                        refreshIconList()
                    }

                    override fun onIconDeleted(iconId: Int) {
                        refreshIconList()
                    }
                })
                currentDialog = dialog
                dialog.show()
            }

            return view
        }
    }
}
