package com.example.wallettransactions.Activities;

import android.app.Activity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.RadioGroup;

import com.example.wallettransactions.Data.WalletContract;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.R;

/**
 * Поля признака капитального актива ({@code view_asset_fields.xml}, openspec capital-assets):
 * «Капитальный актив», «Тип актива» (по умолчанию «Недвижимость»), «Учитывать во „Всего с
 * капиталом“» (по умолчанию включён). Один и тот же код у «Нового кошелька» и карточки
 * кошелька; запись — {@code WalletDbCRUD.SetAccountAsset}/{@code ClearAccountAsset}.
 */
final class AssetFields {

    private final CheckBox checkIsAsset;
    private final View details;
    private final RadioGroup typeGroup;
    private final CheckBox checkInNetWorth;

    AssetFields(Activity activity, AccountOperatingItem.AssetInfo initial, boolean showNetWorth, Runnable onChange) {
        checkIsAsset = activity.findViewById(R.id.checkIsAsset);
        details = activity.findViewById(R.id.assetDetails);
        typeGroup = activity.findViewById(R.id.assetTypeGroup);
        checkInNetWorth = activity.findViewById(R.id.checkInNetWorth);
        checkInNetWorth.setVisibility(showNetWorth ? View.VISIBLE : View.GONE);

        if (initial != null) {
            checkIsAsset.setChecked(true);
            typeGroup.check(radioFor(initial.type));
            checkInNetWorth.setChecked(initial.includeInNetWorth);
        }
        details.setVisibility(checkIsAsset.isChecked() ? View.VISIBLE : View.GONE);

        checkIsAsset.setOnCheckedChangeListener((b, checked) -> {
            details.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (onChange != null) onChange.run();
        });
        typeGroup.setOnCheckedChangeListener((g, id) -> { if (onChange != null) onChange.run(); });
        checkInNetWorth.setOnCheckedChangeListener((b, c) -> { if (onChange != null) onChange.run(); });
    }

    boolean isAsset() {
        return checkIsAsset.isChecked();
    }

    int type() {
        int id = typeGroup.getCheckedRadioButtonId();
        if (id == R.id.assetTypeVehicle) return WalletContract.AccountAssetEntry.TYPE_VEHICLE;
        if (id == R.id.assetTypeOther) return WalletContract.AccountAssetEntry.TYPE_OTHER;
        return WalletContract.AccountAssetEntry.TYPE_REAL_ESTATE;
    }

    boolean includeInNetWorth() {
        return checkInNetWorth.isChecked();
    }

    /** Отличается ли выбор от {@code original} (null — не актив). */
    boolean differsFrom(AccountOperatingItem.AssetInfo original) {
        if (original == null) return isAsset();
        if (!isAsset()) return true;
        return type() != original.type || includeInNetWorth() != original.includeInNetWorth;
    }

    private static int radioFor(int type) {
        if (type == WalletContract.AccountAssetEntry.TYPE_VEHICLE) return R.id.assetTypeVehicle;
        if (type == WalletContract.AccountAssetEntry.TYPE_OTHER) return R.id.assetTypeOther;
        return R.id.assetTypeRealEstate;
    }
}
