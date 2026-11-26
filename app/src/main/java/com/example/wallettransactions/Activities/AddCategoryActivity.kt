package com.example.wallettransactions.Activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.wallettransactions.CategoryKindChips
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.Data.WalletDbHelper
import com.example.wallettransactions.Data.WalletDbLoader
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.Icon
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton

class AddCategoryActivity : AppCompatActivity() {

    private lateinit var imgIconPreview: ImageView
    private lateinit var iconPickerHint: View
    private lateinit var editName: EditText
    private lateinit var editComment: EditText

    /** -1 — не выбрана: при сохранении подставим «неизвестную». */
    private var selectedIconId = -1

    /** Вид новой категории (category-kind); по умолчанию «Расход». */
    private lateinit var kindChips: CategoryKindChips

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_category)

        findViewById<TextView>(R.id.screenTitle).setText(R.string.account_new_category)

        imgIconPreview = findViewById(R.id.imgIconPreview)
        iconPickerHint = findViewById(R.id.iconPickerHint)
        editName = findViewById(R.id.editCategoryName)
        editComment = findViewById(R.id.editCategoryComment)
        kindChips = CategoryKindChips(this, AccountItem.CategoryKind.EXPENSE, null)
    }

    /**
     * Открываем справочник иконок, а не галерею: у категории должна быть одна из
     * иконок приложения. Новую картинку заводят там же кнопкой «+ Новая иконка» —
     * тогда она попадает в справочник, а не остаётся дублем на одну категорию.
     */
    fun pickIconClick(view: View) {
        val current = if (selectedIconId > 0) selectedIconId
        else Singleton.instance.getIconList(this).unknownIcon!!.id
        startActivityForResult(
            IconPickerActivity.buildIntent(this, getString(R.string.icon_title_category), Icon.IconType.CATEGORY, current),
            REQUEST_PICK_ICON
        )
    }

    fun btnUp(view: View) {
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_ICON || resultCode != RESULT_OK || data == null) return

        selectedIconId = data.getIntExtra(IconPickerActivity.RESULT_ICON_ID, selectedIconId)
        renderIcon()
    }

    private fun renderIcon() {
        val icon = Singleton.instance.getIconList(this).getIconById(selectedIconId) ?: return

        imgIconPreview.setImageBitmap(icon.bitmap)
        imgIconPreview.visibility = View.VISIBLE
        iconPickerHint.visibility = View.GONE
    }

    fun btnSaveClick(view: View) {
        val name = editName.text.toString().trim()
        if (name.isEmpty()) {
            editName.error = getString(R.string.account_error_name_empty)
            return
        }

        val db = WalletDbHelper(this).writableDatabase
        if (WalletDbCRUD.IsAccountNameTaken(db, name, -1)) {
            editName.error = getString(R.string.account_error_name_taken)
            return
        }

        val iconId = if (selectedIconId > 0) selectedIconId
        else Singleton.instance.getIconList(this).unknownIcon!!.id

        WalletDbCRUD.InsertAccount(
            db, name, editComment.text.toString().trim(), iconId, false,
            kindChips.selected
        )

        // Reload Singleton so the new icon and account are visible immediately
        val rdb = WalletDbHelper(this).readableDatabase
        WalletDbLoader.LoadIcons(rdb, Singleton.instance.getIconList(this))
        WalletDbLoader.LoadAccounts(
            rdb,
            Singleton.instance.getAccounts(this),
            Singleton.instance.getCurrencyList(this),
            Singleton.instance.getIconList(this),
            null
        )

        setResult(RESULT_OK)
        finish()
    }

    private companion object {
        const val REQUEST_PICK_ICON = 1
    }
}
