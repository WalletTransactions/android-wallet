package com.example.wallettransactions.Activities;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.ExpandableListView;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.wallettransactions.DataItems.Icon;
import com.example.wallettransactions.DataItems.IconList;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IconManagerActivity extends AppCompatActivity {

    private static final int REQUEST_GALLERY = 1001;
    private static final int REQUEST_CROP = 1002;

    private ExpandableListView expandableListView;
    private IconList iconList;
    private Map<Icon.IconType, List<Icon>> typedIcons;
    private List<Icon.IconType> types;
    private IconCategoryAdapter adapter;
    private IconEditDialog currentDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_icon_manager);

        ((TextView) findViewById(R.id.screenTitle)).setText("Иконки и категории");

        expandableListView = findViewById(R.id.expandableListView);
        findViewById(R.id.fabAddIcon).setOnClickListener(v -> openAddIconDialog());

        iconList = Singleton.instance.getIconList(this);
        loadTypedIcons();

        adapter = new IconCategoryAdapter(this, types, typedIcons);
        expandableListView.setAdapter(adapter);
    }

    private void openAddIconDialog() {
        currentDialog = new IconEditDialog(this, null, new IconEditDialog.OnIconSavedListener() {
            @Override
            public void onIconSaved(Icon icon) {
                refreshIconList();
            }

            @Override
            public void onIconDeleted(int iconId) {
                refreshIconList();
            }
        });
        currentDialog.show();
    }

    /** Re-reads icons from the DB cache and rebuilds the grouped view. */
    private void refreshIconList() {
        iconList = Singleton.instance.getIconList(this);
        loadTypedIcons();
        adapter = new IconCategoryAdapter(this, types, typedIcons);
        expandableListView.setAdapter(adapter);
    }

    private void loadTypedIcons() {
        typedIcons = iconList.getGroupedByType();
        types = new ArrayList<>();
        for (Icon.IconType type : Icon.IconType.values()) {
            if (!typedIcons.get(type).isEmpty()) {
                types.add(type);
            }
        }
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        com.example.wallettransactions.security.AppLock.allowNextLeave();
        startActivityForResult(intent, REQUEST_GALLERY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (currentDialog != null) {
            currentDialog.onActivityResult(requestCode, resultCode, data);
        }
    }

    public void btnUp(View view) {
        finish();
    }

    private class IconCategoryAdapter extends BaseExpandableListAdapter {
        private android.content.Context context;
        private List<Icon.IconType> types;
        private Map<Icon.IconType, List<Icon>> icons;

        IconCategoryAdapter(android.content.Context context, List<Icon.IconType> types,
                           Map<Icon.IconType, List<Icon>> icons) {
            this.context = context;
            this.types = types;
            this.icons = icons;
        }

        @Override
        public int getGroupCount() {
            return types.size();
        }

        @Override
        public int getChildrenCount(int groupPosition) {
            Icon.IconType type = types.get(groupPosition);
            List<Icon> typeIcons = icons.get(type);
            return (typeIcons.size() + 5) / 6; // 6 icons per row
        }

        @Override
        public Object getGroup(int groupPosition) {
            return types.get(groupPosition);
        }

        @Override
        public Object getChild(int groupPosition, int childPosition) {
            Icon.IconType type = types.get(groupPosition);
            return icons.get(type);
        }

        @Override
        public long getGroupId(int groupPosition) {
            return groupPosition;
        }

        @Override
        public long getChildId(int groupPosition, int childPosition) {
            return childPosition;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public View getGroupView(int groupPosition, boolean isExpanded,
                                View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(
                        android.R.layout.simple_expandable_list_item_1, parent, false);
            }

            TextView tv = (TextView) convertView.findViewById(android.R.id.text1);
            tv.setText(types.get(groupPosition).getLabel());
            tv.setTextSize(18);
            tv.setPadding(40, 20, 0, 20);

            return convertView;
        }

        @Override
        public View getChildView(int groupPosition, int childPosition, boolean isLastChild,
                                View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(
                        R.layout.grid_icons_row, parent, false);
            }

            GridView gridView = (GridView) convertView.findViewById(R.id.gridIcons);
            Icon.IconType type = types.get(groupPosition);
            List<Icon> typeIcons = icons.get(type);

            int startIndex = childPosition * 6;
            int endIndex = Math.min(startIndex + 6, typeIcons.size());

            List<Icon> rowIcons = new ArrayList<>(typeIcons.subList(startIndex, endIndex));

            IconGridAdapter gridAdapter = new IconGridAdapter(context, rowIcons);
            gridView.setAdapter(gridAdapter);
            gridView.setNumColumns(6);

            return convertView;
        }

        @Override
        public boolean isChildSelectable(int groupPosition, int childPosition) {
            return true;
        }
    }

    private class IconGridAdapter extends android.widget.ArrayAdapter<Icon> {
        IconGridAdapter(android.content.Context context, List<Icon> icons) {
            super(context, 0, icons);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(
                        R.layout.item_icon_grid, parent, false);
            }

            Icon icon = getItem(position);
            ImageView imageView = convertView.findViewById(R.id.imgIcon);
            imageView.setImageBitmap(icon.getBitmap());

            convertView.setOnClickListener(v -> {
                currentDialog = new IconEditDialog(IconManagerActivity.this, icon,
                        new IconEditDialog.OnIconSavedListener() {
                    @Override
                    public void onIconSaved(Icon updated) {
                        refreshIconList();
                    }

                    @Override
                    public void onIconDeleted(int iconId) {
                        refreshIconList();
                    }
                });
                currentDialog.show();
            });

            return convertView;
        }
    }
}
