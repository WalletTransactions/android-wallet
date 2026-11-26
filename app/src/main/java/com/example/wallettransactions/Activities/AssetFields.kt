package com.example.wallettransactions.Activities

import android.app.Activity
import android.view.View
import android.widget.CheckBox
import android.widget.RadioGroup
import com.example.wallettransactions.Data.WalletContract
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.R

/**
 * Поля признака капитального актива (`view_asset_fields.xml`, openspec capital-assets):
 * «Капитальный актив», «Тип актива» (по умолчанию «Недвижимость»), «Учитывать во „Всего с
 * капиталом“» (по умолчанию включён). Один и тот же код у «Нового кошелька» и карточки
 * кошелька; запись — `WalletDbCRUD.SetAccountAsset`/`ClearAccountAsset`.
 */
internal class AssetFields(
    activity: Activity,
    initial: AccountOperatingItem.AssetInfo?,
    showNetWorth: Boolean,
    onChange: Runnable?
) {

    private val checkIsAsset: CheckBox = activity.findViewById(R.id.checkIsAsset)
    private val details: View = activity.findViewById(R.id.assetDetails)
    private val typeGroup: RadioGroup = activity.findViewById(R.id.assetTypeGroup)
    private val checkInNetWorth: CheckBox = activity.findViewById(R.id.checkInNetWorth)

    init {
        checkInNetWorth.visibility = if (showNetWorth) View.VISIBLE else View.GONE

        if (initial != null) {
            checkIsAsset.isChecked = true
            typeGroup.check(radioFor(initial.type))
            checkInNetWorth.isChecked = initial.includeInNetWorth
        }
        details.visibility = if (checkIsAsset.isChecked) View.VISIBLE else View.GONE

        checkIsAsset.setOnCheckedChangeListener { _, checked ->
            details.visibility = if (checked) View.VISIBLE else View.GONE
            onChange?.run()
        }
        typeGroup.setOnCheckedChangeListener { _, _ -> onChange?.run() }
        checkInNetWorth.setOnCheckedChangeListener { _, _ -> onChange?.run() }
    }

    fun isAsset(): Boolean = checkIsAsset.isChecked

    fun type(): Int = when (typeGroup.checkedRadioButtonId) {
        R.id.assetTypeVehicle -> WalletContract.AccountAssetEntry.TYPE_VEHICLE
        R.id.assetTypeOther -> WalletContract.AccountAssetEntry.TYPE_OTHER
        else -> WalletContract.AccountAssetEntry.TYPE_REAL_ESTATE
    }

    fun includeInNetWorth(): Boolean = checkInNetWorth.isChecked

    /** Отличается ли выбор от `original` (null — не актив). */
    fun differsFrom(original: AccountOperatingItem.AssetInfo?): Boolean {
        if (original == null) return isAsset()
        if (!isAsset()) return true
        return type() != original.type || includeInNetWorth() != original.includeInNetWorth
    }

    private fun radioFor(type: Int): Int = when (type) {
        WalletContract.AccountAssetEntry.TYPE_VEHICLE -> R.id.assetTypeVehicle
        WalletContract.AccountAssetEntry.TYPE_OTHER -> R.id.assetTypeOther
        else -> R.id.assetTypeRealEstate
    }
}
