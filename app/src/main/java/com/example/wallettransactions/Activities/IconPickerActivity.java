package com.example.wallettransactions.Activities;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.wallettransactions.DataItems.Icon;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

import java.util.ArrayList;
import java.util.List;

/**
 * Сетка иконок (activity_icon_picker.xml, прототип 2a — блок iconPickOpen).
 *
 * В прототипе это буквенные глифы, но в приложении иконки — настоящие битмапы
 * из IconList, поэтому показываем их, отфильтровав по типу: для валют — флаги.
 */
public class IconPickerActivity extends AppCompatActivity {

    public static final String ARG_TITLE = "IconPicker.Title";
    public static final String ARG_ICON_TYPE = "IconPicker.IconType";
    public static final String ARG_CURRENT_ID = "IconPicker.CurrentId";
    public static final String RESULT_ICON_ID = "IconPicker.ResultIconId";

    public static Intent buildIntent(Context ctx, String title, Icon.IconType type, int currentId) {
        Intent intent = new Intent(ctx, IconPickerActivity.class);
        intent.putExtra(ARG_TITLE, title);
        intent.putExtra(ARG_ICON_TYPE, type.getValue());
        intent.putExtra(ARG_CURRENT_ID, currentId);
        return intent;
    }

    private int currentIconId;
    private Icon.IconType type;
    private GridView grid;
    private IconEditDialog currentDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_icon_picker);

        String title = getIntent().getStringExtra(ARG_TITLE);
        if (title != null) ((TextView) findViewById(R.id.pickerTitle)).setText(title);

        currentIconId = getIntent().getIntExtra(ARG_CURRENT_ID, -1);

        type = Icon.IconType.fromValue(
                getIntent().getIntExtra(ARG_ICON_TYPE, Icon.IconType.CATEGORY.getValue()));

        grid = findViewById(R.id.gridIcons);
        fillGrid();
    }

    private void fillGrid() {
        List<Icon> icons = new ArrayList<>();
        for (Icon icon : Singleton.instance.getIconList(this).getAll()) {
            if (icon.getType() == type) icons.add(icon);
        }

        grid.setAdapter(new IconAdapter(this, icons));
        grid.setOnItemClickListener((parent, view, position, id) -> pickIcon(icons.get(position).getId()));
    }

    private void pickIcon(int iconId) {
        Intent result = new Intent();
        result.putExtra(RESULT_ICON_ID, iconId);
        setResult(RESULT_OK, result);
        finish();
    }

    /** Заводит иконку прямо из выбора: тип берётся из того, за чем сюда пришли. */
    public void onNewIcon(View view) {
        currentDialog = IconEditDialog.newIcon(this, type, new IconEditDialog.OnIconSavedListener() {
            @Override
            public void onIconSaved(Icon icon) {
                // Её и создавали ради этого выбора — возвращаем сразу.
                pickIcon(icon.getId());
            }

            @Override
            public void onIconDeleted(int iconId) {
                fillGrid();
            }
        });
        currentDialog.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // Диалог сам ходит в фотопикер и в обрезку — коды запросов его.
        if (currentDialog != null) currentDialog.onActivityResult(requestCode, resultCode, data);
    }

    public void btnUp(View view) {
        finish();
    }

    private class IconAdapter extends ArrayAdapter<Icon> {

        IconAdapter(Context context, List<Icon> icons) {
            super(context, R.layout.icon_picker_item_layout, icons);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            View cell = convertView;
            if (cell == null) {
                cell = LayoutInflater.from(getContext())
                        .inflate(R.layout.icon_picker_item_layout, parent, false);
            }

            Icon icon = getItem(position);
            ImageView image = cell.findViewById(R.id.iconCell);
            image.setImageBitmap(icon.getBitmap());
            image.setBackgroundResource(icon.getId() == currentIconId
                    ? R.drawable.bg_icon_cell_selected : R.drawable.bg_icon_cell);

            return cell;
        }
    }
}
