package com.example.wallettransactions.DataItems;

import android.graphics.Bitmap;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;

public class Icon {
    private Bitmap getResizedBitmap(Bitmap bm, int newWidth, int newHeight) {
        int width = bm.getWidth();
        int height = bm.getHeight();
        float scaleWidth = ((float) newWidth) / width;
        float scaleHeight = ((float) newHeight) / height;
        // CREATE A MATRIX FOR THE MANIPULATION
        Matrix matrix = new Matrix();
        // RESIZE THE BIT MAP
        matrix.postScale(scaleWidth, scaleHeight);

        // "RECREATE" THE NEW BITMAP
        Bitmap resizedBitmap = Bitmap.createBitmap(
                bm, 0, 0, width, height, matrix, false);
        return resizedBitmap;
    }

    public enum IconType {
        CATEGORY(0, "Категория"),
        FLAG(1, "Флаг");

        private final int value;
        private final String label;

        IconType(int value, String label) {
            this.value = value;
            this.label = label;
        }

        public int getValue() {
            return value;
        }

        public String getLabel() {
            return label;
        }

        public static IconType fromValue(int value) {
            if (value == FLAG.value) return FLAG;
            return CATEGORY;
        }
    }

    private int fId;
    private String fName;
    private Bitmap fBitmap;
    private Bitmap bitmap32;
    private Bitmap bitmap64;
    private IconType fType = IconType.CATEGORY;
    /** {@code icon.origin = 0} (openspec server-managed-icons): только просмотр, правит администратор на сайте. */
    private boolean fFromServer;

    public Bitmap getBitmap32(){
       if (bitmap32 == null)
           bitmap32 =getResizedBitmap( getBitmap(),42,42);
       return bitmap32;
    }


    public Bitmap getBitmap64(){
        if (bitmap64 == null)
            bitmap64 =getResizedBitmap( getBitmap(),164,164);
        return bitmap64;
    }

    public Bitmap getBitmap() {
        return fBitmap;
    }

    public String getName(){
        return fName;}

    public int getId(){
        return fId;
    }

    public IconType getType() {
        return fType;
    }

    public void setType(IconType type) {
        fType = type;
    }

    public boolean isFromServer() {
        return fFromServer;
    }

    public Icon(int id, String name, Bitmap bitmap) {
        fId = id;
        fName = name;
        fBitmap = bitmap;
    }

    public Icon(int id, String name, Bitmap bitmap, IconType type) {
        this(id, name, bitmap, type, false);
    }

    public Icon(int id, String name, Bitmap bitmap, IconType type, boolean fromServer) {
        fId = id;
        fName = name;
        fBitmap = bitmap;
        fType = type;
        fFromServer = fromServer;
    }
}
