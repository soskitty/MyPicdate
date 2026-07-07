package com.mypicdate.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private Uri imageUri;
    private String originalName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) {
            finish();
            return;
        }

        imageUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (imageUri == null) {
            Toast.makeText(this, "No image received", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        originalName = getFileName(imageUri);
        if (originalName == null) originalName = "photo.jpg";

        showFontSizeDialog();
    }

    private void showFontSizeDialog() {
        final String[] items = {"1 (最小)", "2 (较小)", "3 (适中)", "4 (较大)", "5 (最大)"};
        new AlertDialog.Builder(this)
                .setTitle("选择字号")
                .setItems(items, (dialog, which) -> {
                    try {
                        processImage(which + 1);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        finish();
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void processImage(int fontSizeLevel) throws Exception {
        Bitmap original;
        try (InputStream is = getContentResolver().openInputStream(imageUri)) {
            original = BitmapFactory.decodeStream(is);
        }

        if (original == null) {
            Toast.makeText(this, "Cannot decode image", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Bitmap result = original.copy(Bitmap.Config.ARGB_8888, true);
        original.recycle();

        String dateText = getDateString(imageUri);

        Canvas canvas = new Canvas(result);
        int imgW = result.getWidth();
        int imgH = result.getHeight();
        int imgMin = Math.min(imgW, imgH);

        float[] multipliers = {0.5f, 0.7f, 1.0f, 1.4f, 1.8f};
        float textSize = (imgMin * 0.03f) * multipliers[fontSizeLevel - 1];
        textSize = Math.max(10, textSize);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(textSize);

        float padding = imgMin * 0.03f;
        float textWidth = paint.measureText(dateText);
        float x = result.getWidth() - textWidth - padding;
        float y = result.getHeight() - padding;

        paint.setColor(Color.argb(128, 0, 0, 0));
        canvas.drawText(dateText, x + 1, y + 1, paint);
        paint.setColor(Color.WHITE);
        canvas.drawText(dateText, x, y, paint);

        String baseName = originalName;
        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex > 0) baseName = originalName.substring(0, dotIndex);
        String outputName = baseName + "_d.jpg";

        saveToGallery(result, outputName);
        result.recycle();

        launchGallery();
        Toast.makeText(this, "Saved: " + outputName, Toast.LENGTH_SHORT).show();
        finish();
    }

    private String getDateString(Uri uri) {
        long dateTaken = 0;
        String debugSrc = null;

        // 1: MediaMetadataRetriever (works with any content URI natively)
        MediaMetadataRetriever mmr = null;
        try {
            mmr = new MediaMetadataRetriever();
            mmr.setDataSource(this, uri);
            String date = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE);
            if (date != null && !date.isEmpty()) {
                SimpleDateFormat[] fmts = {
                    new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US),
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
                    new SimpleDateFormat("yyyyMMdd", Locale.US)
                };
                for (SimpleDateFormat f : fmts) {
                    try {
                        Date p = f.parse(date);
                        if (p != null) { dateTaken = p.getTime(); debugSrc = "MMR"; break; }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (mmr != null) mmr.release();
        }

        // 2: Direct MediaStore query on the URI itself (DATE_TAKEN, DATE_ADDED, DATE_MODIFIED)
        if (dateTaken <= 0 && uri != null) {
            try (Cursor c = getContentResolver().query(uri,
                    new String[]{
                            MediaStore.Images.Media.DATE_TAKEN,
                            MediaStore.Images.Media.DATE_ADDED,
                            MediaStore.Images.Media.DATE_MODIFIED
                    }, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    long v;
                    if (!c.isNull(0) && (v = c.getLong(0)) > 0) { dateTaken = v; debugSrc = "DT"; }
                    if (dateTaken <= 0 && !c.isNull(1) && (v = c.getLong(1)) > 0) { dateTaken = v * 1000; debugSrc = "DA"; }
                    if (dateTaken <= 0 && !c.isNull(2) && (v = c.getLong(2)) > 0) { dateTaken = v * 1000; debugSrc = "DM"; }
                }
            } catch (Exception ignored) {}
        }

        // 3: DocumentsContract URI → extract media ID → query MediaStore
        if (dateTaken <= 0 && uri != null) {
            try {
                if (DocumentsContract.isDocumentUri(this, uri)) {
                    String docId = DocumentsContract.getDocumentId(uri);
                    if (docId != null && docId.contains(":")) {
                        String[] parts = docId.split(":");
                        String type = parts[0];
                        String id = parts[1];
                        if (id.matches("\\d+")) {
                            Uri collection = "image".equals(type)
                                    ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                                    : "video".equals(type)
                                            ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                            : null;
                            if (collection != null) {
                                Uri mu = ContentUris.withAppendedId(collection, Long.parseLong(id));
                                try (Cursor c = getContentResolver().query(mu,
                                        new String[]{MediaStore.Images.Media.DATE_TAKEN}, null, null, null)) {
                                    if (c != null && c.moveToFirst() && !c.isNull(0)) {
                                        long v = c.getLong(0);
                                        if (v > 0) { dateTaken = v; debugSrc = "DC"; }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 4: Old-style "media" authority URI → extract path ID → query MediaStore
        if (dateTaken <= 0 && uri != null && "media".equals(uri.getAuthority())) {
            try {
                String mediaId = uri.getLastPathSegment();
                if (mediaId != null && mediaId.matches("\\d+")) {
                    Uri mu = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Long.parseLong(mediaId));
                    try (Cursor c = getContentResolver().query(mu,
                            new String[]{MediaStore.Images.Media.DATE_TAKEN}, null, null, null)) {
                        if (c != null && c.moveToFirst() && !c.isNull(0)) {
                            long v = c.getLong(0);
                            if (v > 0) { dateTaken = v; debugSrc = "MI"; }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 5: Copy to temp file → read EXIF (all date tags)
        if (dateTaken <= 0 && uri != null) {
            File tf = null;
            try {
                tf = File.createTempFile("exif_", ".jpg", getCacheDir());
                try (InputStream is = getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(tf)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                    fos.flush();
                }
                if (tf.length() > 0) {
                    ExifInterface exif = new ExifInterface(tf.getAbsolutePath());
                    String[] tags = {
                        ExifInterface.TAG_DATETIME_ORIGINAL,
                        ExifInterface.TAG_DATETIME,
                        ExifInterface.TAG_DATETIME_DIGITIZED
                    };
                    for (String tag : tags) {
                        String exifDate = exif.getAttribute(tag);
                        if (exifDate != null) {
                            try {
                                Date p = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(exifDate);
                                if (p != null) { dateTaken = p.getTime(); debugSrc = "EXIF"; break; }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (tf != null) tf.delete();
            }
        }

        // 6: Parse date from filename (strip non-digits, take first 14 chars)
        if (dateTaken <= 0 && originalName != null) {
            String digits = originalName.replaceAll("\\D", "");
            if (digits.length() >= 14) {
                try {
                    Date p = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(digits.substring(0, 14));
                    if (p != null && p.getTime() > 0) { dateTaken = p.getTime(); debugSrc = "FN"; }
                } catch (Exception ignored) {}
            }
        }

        // Diagnostic toast (remove after debugging)
        if (uri != null) {
            String msg = (debugSrc != null ? debugSrc : "NOW") + " | " + uri.getAuthority();
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        }

        if (dateTaken > 0) {
            return new SimpleDateFormat("yyyyMMdd HH:mm", Locale.getDefault()).format(new Date(dateTaken));
        }
        return new SimpleDateFormat("yyyyMMdd HH:mm", Locale.getDefault()).format(new Date());
    }

    private String getFileName(Uri uri) {
        String name = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) name = cursor.getString(idx);
                }
            }
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name != null) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".jpg") && !lower.endsWith(".jpeg") && !lower.endsWith(".png")) {
                name = name + ".jpg";
            }
        }
        return name;
    }

    private void saveToGallery(Bitmap bitmap, String fileName) throws Exception {
        final Uri[] savedUriRef = {null};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MyPicdate");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            savedUriRef[0] = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (savedUriRef[0] != null) {
                try (FileOutputStream fos = (FileOutputStream) getContentResolver().openOutputStream(savedUriRef[0])) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                }
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(savedUriRef[0], values, null, null);
                getContentResolver().notifyChange(savedUriRef[0], null);
            }
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "MyPicdate");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, fileName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos);
            }
            Intent scanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            scanIntent.setData(Uri.fromFile(file));
            sendBroadcast(scanIntent);
        }

        String fullPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) + "/MyPicdate/" + fileName;
        MediaScannerConnection.scanFile(this, new String[]{fullPath}, null,
                (path, scanUri) -> {
                    if (savedUriRef[0] != null) {
                        Intent broadcast = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                        broadcast.setData(savedUriRef[0]);
                        sendBroadcast(broadcast);
                    }
                });
    }

    private void launchGallery() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage("com.miui.gallery");
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(launch);
                return;
            }
        } catch (Exception ignored) {}

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                intent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
            } else {
                intent.setDataAndType(Uri.parse(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).getAbsolutePath()), "image/*");
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception ignored) {}
    }
}
