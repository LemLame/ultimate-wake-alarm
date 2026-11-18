package jp.waketerminal;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Import chosen audio privately; the device-default option stays dynamic. */
final class SoundSettings {
    private final Context context;
    private final SharedPreferences prefs;
    SoundSettings(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getSharedPreferences("wake_terminal", Context.MODE_PRIVATE);
    }
    Uri selected() {
        String saved = prefs.getString("alarm_sound_uri", "");
        return saved.isEmpty() ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) : Uri.parse(saved);
    }
    String label() { return prefs.getString("alarm_sound_label", "端末の標準アラーム音"); }
    Intent ringtonePicker() {
        return new Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "起床アラーム音")
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, selected());
    }
    static Intent documentPicker() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    void useDefault() throws IOException { save(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), "端末の標準アラーム音"); }
    void selectRingtone(Uri uri) throws IOException {
        if (uri == null) throw new IOException("無音は選択できません");
        if (RingtoneManager.isDefault(uri)) { useDefault(); return; }
        // Ringtone providers normally offer system-readable URIs; copy while the picker grant is valid.
        String label = "選択したアラーム音";
        try {
            android.media.Ringtone tone = RingtoneManager.getRingtone(context, uri);
            if (tone != null) label = tone.getTitle(context);
        } catch (RuntimeException ignored) { }
        importAudio(uri, label);
    }
    void importAudio(Uri uri) throws IOException { importAudio(uri, documentName(uri)); }
    private void importAudio(Uri uri, String label) throws IOException {
        File copy = File.createTempFile("alarm-sound-", ".audio", context.getFilesDir());
        boolean selected = false;
        try {
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(copy)) {
                if (in == null) throw new IOException("音声ファイルを開けません");
                byte[] buffer = new byte[32 * 1024]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                out.getFD().sync();
            } catch (IOException e) { throw new IOException("音声ファイルを読み込めません。ファイルと端末の空き容量を確認してください", e); }
            // Validate before committing. A failed import leaves the previous selection intact.
            MediaPlayer probe = new MediaPlayer();
            try { probe.setDataSource(copy.getAbsolutePath()); probe.prepare(); }
            catch (IOException e) { throw new IOException("再生できる音声ファイルを選んでください", e); }
            finally { probe.release(); }
            save(Uri.fromFile(copy), label); selected = true;
        } catch (RuntimeException e) { throw new IOException("再生できる音声ファイルを選んでください", e); }
        finally { if (!selected) copy.delete(); }
    }
    private String documentName(Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException ignored) { }
        return "選択した音声ファイル";
    }
    private void save(Uri uri, String label) throws IOException {
        Uri previous = selected();
        if (!prefs.edit().putString("alarm_sound_uri", uri.toString()).putString("alarm_sound_label", label).commit())
            throw new IOException("アラーム音の設定を保存できません");
        // Only remove imports this class created inside our own private files directory.
        if ("file".equals(previous.getScheme()) && !previous.equals(uri)) {
            File old = new File(previous.getPath());
            if (context.getFilesDir().equals(old.getParentFile()) && old.getName().startsWith("alarm-sound-")) old.delete();
        }
    }
}
