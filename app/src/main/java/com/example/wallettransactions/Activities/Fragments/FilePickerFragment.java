package com.example.wallettransactions.Activities.Fragments;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import androidx.fragment.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.example.wallettransactions.R;

import java.util.List;

public class FilePickerFragment extends Fragment {

    public static final int PICKFILE_RESULT_CODE = 1;

    private Button btnChooseFile;
    private Button btnFormatInfo;
    private TextView tvItemPath;

    private Uri fileUri;
    private String filePath;

    /** Заголовок и текст диалога «Формат» — задаются извне (ImportActivity),
     *  пока не заданы, кнопка спрятана (см. fragment_file_picker.xml). */
    private String formatTitle;
    private CharSequence formatContent;

    public  Uri GetFileUri()
    {
        return fileUri;

    };

    public String GetFileName()
    {
        return filePath;
    }

    public FilePickerFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View rootView = inflater.inflate(R.layout.fragment_file_picker, container, false);

        btnChooseFile = (Button) rootView.findViewById(R.id.btn_choose_file);
        btnFormatInfo = (Button) rootView.findViewById(R.id.btn_format_info);
        tvItemPath = (TextView) rootView.findViewById(R.id.tv_file_path);
        tvItemPath.setEnabled(false);
        tvItemPath.setFocusable(View.NOT_FOCUSABLE);

        btnChooseFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // "*/*" — эта же фрагмент-обёртка используется и для CSV, и для JSON-
                // импорта (ImportActivity); документ-провайдер не всегда отдаёт .json
                // как "text/*", а сама структура файла (заголовок CSV / JSONArray)
                // проверяется уже при разборе, не по MIME здесь.
                Intent chooseFile = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                chooseFile.setType("*/*");
                chooseFile = Intent.createChooser(chooseFile, "Open file");
                com.example.wallettransactions.security.AppLock.allowNextLeave();
                startActivityForResult(chooseFile, PICKFILE_RESULT_CODE);
            }
        });
        btnFormatInfo.setOnClickListener(v -> showFormatDialog());
        // setFormatInfo() мог быть вызван раньше onCreateView (ImportActivity
        // вызывает его сразу после findFragmentById в своём onCreate, а порядок
        // относительно инфляции фрагмента не гарантирован) — досказать видимость.
        if (formatContent != null) btnFormatInfo.setVisibility(View.VISIBLE);
        return rootView;
    }

    /**
     * Подключает кнопку «Формат»: показывает {@code title}/{@code content} в
     * копируемом диалоге вместо того, чтобы держать описание формата в общем
     * поле статуса импорта. {@code content} может нести цветные спаны
     * (см. ImportActivity — атрибуты JSON подсвечены) — TextView в диалоге их
     * отрисует как есть.
     */
    public void setFormatInfo(String title, CharSequence content) {
        this.formatTitle = title;
        this.formatContent = content;
        if (btnFormatInfo != null) btnFormatInfo.setVisibility(View.VISIBLE);
    }

    private void showFormatDialog() {
        if (formatContent == null) return;
        Context ctx = requireContext();

        TextView text = new TextView(ctx);
        text.setText(formatContent);
        text.setTextIsSelectable(true);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setTextSize(13);
        text.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.colorTextPrimary));
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad, pad, pad);

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(text);

        new AlertDialog.Builder(ctx)
                .setTitle(formatTitle)
                .setView(scroll)
                .setPositiveButton("Копировать", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText(formatTitle, formatContent.toString()));
                    Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    public String getPath(Uri uri) {

        String path = null;
        String[] projection = { MediaStore.Files.FileColumns.DATA };
        Cursor cursor = getActivity().getContentResolver().query(uri, projection, null, null, null);

        if(cursor == null){
            path = uri.getPath();
        }
        else{
            cursor.moveToFirst();
            int column_index = cursor.getColumnIndexOrThrow(projection[0]);
            path = cursor.getString(column_index);
            cursor.close();
        }

        return ((path == null || path.isEmpty()) ? (uri.getPath()) : path);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case PICKFILE_RESULT_CODE:
                if (resultCode == -1) {
                    fileUri = data.getData();
                    filePath = getPath(fileUri);

                    //filePath = filePath;//.substring(filePath.lastIndexOf(":")+1);; //fileUri.toString()  fileUri.getPath()
                    tvItemPath.setText(filePath);
                }

                break;
        }
    }


}