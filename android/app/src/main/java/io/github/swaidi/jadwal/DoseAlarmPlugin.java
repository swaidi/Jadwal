package io.github.swaidi.jadwal;

import android.app.Activity;
import android.app.AlarmManager;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.annotation.ActivityCallback;
import java.io.InputStream;
import java.io.OutputStream;
import android.content.ClipData;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;

@CapacitorPlugin(name = "DoseAlarm")
public class DoseAlarmPlugin extends Plugin {
    private static WeakReference<DoseAlarmPlugin> instance = new WeakReference<>(null);

    @Override
    public void load() {
        instance = new WeakReference<>(this);
        DoseLogic.ensureChannel(getContext());
        clearReports(getContext());
    }

    /** Report files are temporary: removed on the next app start and before each new report. */
    static void clearReports(Context c) {
        File dir = new File(c.getCacheDir(), "reports");
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
    }

    @PluginMethod
    public void vibrate(PluginCall call) {
        DoseLogic.buzz(getContext());
        call.resolve();
    }

    /**
     * Builds the physician report as a PDF (and optionally a ZIP with the voice notes)
     * and opens the Android share sheet.
     */
    @PluginMethod
    public void shareReport(PluginCall call) {
        final Context c = getContext();
        final JSArray blocks = call.getArray("blocks", new JSArray());
        final boolean rtl = Boolean.TRUE.equals(call.getBoolean("rtl", false));
        final boolean zip = Boolean.TRUE.equals(call.getBoolean("zip", false));
        final String base = call.getString("fileBase", "Jadwal-Report").replaceAll("[^A-Za-z0-9_\\-]", "_");
        final String pageLabel = call.getString("pageLabel", "Page {n}");
        final String chooserTitle = call.getString("chooserTitle", "Share report");
        final JSArray audio = call.getArray("audio", new JSArray());
        new Thread(() -> {
            try {
                clearReports(c);
                File dir = new File(c.getCacheDir(), "reports");
                if (!dir.exists()) dir.mkdirs();
                File pdf = new File(dir, base + ".pdf");
                ReportPdf.write(blocks, rtl, pageLabel, pdf);
                File out = pdf;
                String mime = "application/pdf";
                int added = 0;
                if (zip) {
                    File z = new File(dir, base + ".zip");
                    try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(z))) {
                        addToZip(zos, pdf, pdf.getName());
                        File notesDir = new File(c.getFilesDir(), "notes");
                        for (int i = 0; i < audio.length(); i++) {
                            JSONObject a = audio.optJSONObject(i);
                            if (a == null) continue;
                            File f = new File(a.optString("path"));
                            // Only voice notes this app recorded can be attached.
                            if (!f.exists() || !f.getCanonicalPath().startsWith(notesDir.getCanonicalPath())) continue;
                            String name = a.optString("name", f.getName()).replaceAll("[^A-Za-z0-9_\\-.]", "_");
                            addToZip(zos, f, "voice-notes/" + name);
                            added++;
                        }
                    }
                    out = z;
                    mime = "application/zip";
                }
                Uri uri = FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", out);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(mime);
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.setClipData(ClipData.newRawUri(out.getName(), uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(send, chooserTitle);
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                final int count = added;
                getActivity().runOnUiThread(() -> {
                    try {
                        getActivity().startActivity(chooser);
                        JSObject ret = new JSObject();
                        ret.put("ok", true);
                        ret.put("audioAdded", count);
                        call.resolve(ret);
                    } catch (Exception e) { call.reject("Could not open the share sheet: " + e.getMessage()); }
                });
            } catch (Exception e) {
                call.reject("Could not create the report: " + e.getMessage());
            }
        }).start();
    }


    /* ---------------- Backup and restore ---------------- */

    private File pendingBackup;

    private File restoreDir() { File d = new File(getContext().getCacheDir(), "restore"); d.mkdirs(); return d; }
    private File safetyFile() { File d = new File(getContext().getFilesDir(), "safety"); d.mkdirs(); return new File(d, "before-restore.jadwal"); }

    @PluginMethod
    public void getNotesDir(PluginCall call) {
        JSObject r = new JSObject();
        r.put("path", BackupFile.notesDir(getContext()).getAbsolutePath());
        call.resolve(r);
    }

    /** Creates the backup file, then saves it through the file picker or opens the share sheet. */
    @PluginMethod
    public void createBackup(PluginCall call) {
        final Context c = getContext();
        final String data = call.getString("data", "{}");
        final JSArray audio = call.getArray("audio", new JSArray());
        final String password = call.getString("password", "");
        final String dest = call.getString("dest", "share");
        final String fileName = call.getString("fileName", "Jadwal-backup.jadwal").replaceAll("[^A-Za-z0-9_\\-.]", "_");
        final String chooserTitle = call.getString("chooserTitle", "Share backup");
        new Thread(() -> {
            try {
                File dir = new File(c.getCacheDir(), "backups");
                BackupFile.deleteTree(dir);
                dir.mkdirs();
                File out = new File(dir, fileName);
                BackupFile.write(c, data, audio, password, out);
                getActivity().runOnUiThread(() -> {
                    try {
                        if ("save".equals(dest)) {
                            pendingBackup = out;
                            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            i.setType("application/octet-stream");
                            i.putExtra(Intent.EXTRA_TITLE, fileName);
                            startActivityForResult(call, i, "backupSaved");
                        } else {
                            Uri uri = FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", out);
                            Intent send = new Intent(Intent.ACTION_SEND);
                            send.setType("application/octet-stream");
                            send.putExtra(Intent.EXTRA_STREAM, uri);
                            send.setClipData(ClipData.newRawUri(out.getName(), uri));
                            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            Intent chooser = Intent.createChooser(send, chooserTitle);
                            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            getActivity().startActivity(chooser);
                            JSObject r = new JSObject();
                            r.put("ok", true);
                            call.resolve(r);
                        }
                    } catch (Exception e) { call.reject("Could not open: " + e.getMessage()); }
                });
            } catch (Exception e) {
                call.reject("Could not create the backup: " + e.getMessage());
            }
        }).start();
    }

    @ActivityCallback
    private void backupSaved(PluginCall call, ActivityResult result) {
        if (call == null) return;
        JSObject r = new JSObject();
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null || pendingBackup == null) {
            r.put("ok", false);
            r.put("cancelled", true);
            call.resolve(r);
            return;
        }
        Uri uri = result.getData().getData();
        try (OutputStream os = getContext().getContentResolver().openOutputStream(uri, "w");
             FileInputStream in = new FileInputStream(pendingBackup)) {
            BackupFile.copy(in, os);
            r.put("ok", true);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("Could not save the backup: " + e.getMessage());
        } finally {
            BackupFile.deleteTree(pendingBackup.getParentFile());
            pendingBackup = null;
        }
    }

    /** Opens the file picker to choose a backup; reports whether it needs a password. */
    @PluginMethod
    public void pickBackup(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(call, i, "backupPicked");
    }

    @ActivityCallback
    private void backupPicked(PluginCall call, ActivityResult result) {
        if (call == null) return;
        JSObject r = new JSObject();
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) {
            r.put("picked", false);
            call.resolve(r);
            return;
        }
        Uri uri = result.getData().getData();
        File in = new File(restoreDir(), "in.bin");
        try (InputStream is = getContext().getContentResolver().openInputStream(uri);
             FileOutputStream fo = new FileOutputStream(in)) {
            BackupFile.copy(is, fo);
        } catch (Exception e) {
            call.reject("Could not read the file: " + e.getMessage());
            return;
        }
        try {
            r.put("picked", true);
            r.put("encrypted", BackupFile.isEncrypted(in));
            call.resolve(r);
        } catch (Exception e) {
            in.delete();
            call.reject("This file is not a Jadwal backup.", "not_backup");
        }
    }

    /** Reads the picked backup (with its password if it has one) and returns its data. */
    @PluginMethod
    public void openBackup(PluginCall call) {
        final String password = call.getString("password", "");
        new Thread(() -> {
            try {
                String json = BackupFile.open(new File(restoreDir(), "in.bin"), password, new File(restoreDir(), "stage"));
                JSObject r = new JSObject();
                r.put("data", json);
                call.resolve(r);
            } catch (BackupFile.WrongPassword e) {
                call.reject("Wrong password.", "wrong_password");
            } catch (BackupFile.NotABackup e) {
                call.reject("This file is not a Jadwal backup.", "not_backup");
            } catch (Exception e) {
                call.reject("Could not open the backup: " + e.getMessage());
            }
        }).start();
    }

    /** Moves the opened backup's voice notes into place, after the app has replaced its data. */
    @PluginMethod
    public void finishRestore(PluginCall call) {
        int n = BackupFile.installAudio(getContext(), new File(restoreDir(), "stage"));
        BackupFile.deleteTree(restoreDir());
        JSObject r = new JSObject();
        r.put("audio", n);
        r.put("notesDir", BackupFile.notesDir(getContext()).getAbsolutePath());
        call.resolve(r);
    }

    /** Keeps a copy of the current data before a restore, so the restore can be undone. */
    @PluginMethod
    public void makeSafetyCopy(PluginCall call) {
        final String data = call.getString("data", "{}");
        final JSArray audio = call.getArray("audio", new JSArray());
        new Thread(() -> {
            try {
                BackupFile.write(getContext(), data, audio, "", safetyFile());
                call.resolve();
            } catch (Exception e) {
                call.reject("Could not make a safety copy: " + e.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void safetyCopyInfo(PluginCall call) {
        File f = safetyFile();
        JSObject r = new JSObject();
        r.put("exists", f.exists());
        r.put("time", f.exists() ? f.lastModified() : 0);
        call.resolve(r);
    }

    /** Prepares the safety copy to be restored, as if it had been picked. */
    @PluginMethod
    public void useSafetyCopy(PluginCall call) {
        try (FileInputStream in = new FileInputStream(safetyFile());
             FileOutputStream fo = new FileOutputStream(new File(restoreDir(), "in.bin"))) {
            BackupFile.copy(in, fo);
            call.resolve();
        } catch (Exception e) {
            call.reject("No safety copy found.");
        }
    }

    private static void addToZip(ZipOutputStream zos, File f, String name) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) zos.write(buf, 0, r);
        }
        zos.closeEntry();
    }

    static void emit(JSONObject act) {
        DoseAlarmPlugin p = instance.get();
        if (p == null) return;
        try { p.notifyListeners("doseAction", JSObject.fromJSONObject(act)); } catch (Exception ignored) {}
    }

    /** Replaces the list of pending doses and re-arms the alarms. */
    @PluginMethod
    public void setAlarms(PluginCall call) {
        Context c = getContext();
        JSArray alarms = call.getArray("alarms", new JSArray());
        int repeatMin = call.getInt("repeatMin", 10);
        DoseLogic.prefs(c).edit().putInt("repeatMin", Math.max(1, repeatMin)).apply();
        DoseLogic.put(c, "doses", alarms);
        JSObject labels = call.getObject("labels", new JSObject());
        DoseLogic.put(c, "labels", labels);
        DoseLogic.prefs(c).edit()
                .putString("lang", call.getString("lang", "en"))
                .putBoolean("elderly", Boolean.TRUE.equals(call.getBoolean("elderly", false)))
                .putString("theme", DoseLogic.pick(call.getString("theme", "auto"), new String[]{"auto", "light", "dark"}, "auto"))
                .putString("sound", DoseLogic.pick(call.getString("sound", "normal"), DoseLogic.SOUNDS, "normal"))
                .putString("display", DoseLogic.pick(call.getString("display", "full"), DoseLogic.DISPLAYS, "full"))
                .apply();
        DoseLogic.put(c, "done", new JSONObject());
        DoseLogic.scheduleAll(c);
        JSObject ret = new JSObject();
        ret.put("count", alarms.length());
        call.resolve(ret);
    }

    /** Shows a sample alert with the given sound and display. */
    @PluginMethod
    public void testAlert(PluginCall call) {
        Context c = getContext();
        JSONObject d = new JSONObject();
        try {
            d.put("title", call.getString("title", "Test alert"));
            d.put("name", call.getString("name", "Test"));
            d.put("dose", call.getString("dose", ""));
            d.put("body", call.getString("body", ""));
            d.put("due", call.getString("due", ""));
            d.put("sound", call.getString("sound", ""));
            d.put("display", call.getString("display", ""));
        } catch (Exception ignored) {}
        DoseLogic.testAlert(c, d);
        call.resolve();
    }

    /** Returns and clears Taken/Skip presses made outside the app. */
    @PluginMethod
    public void takeActions(PluginCall call) {
        Context c = getContext();
        JSONArray q = DoseLogic.getArr(c, "actions");
        DoseLogic.put(c, "actions", new JSONArray());
        JSObject ret = new JSObject();
        try { ret.put("actions", new JSArray(q.toString())); } catch (Exception e) { ret.put("actions", new JSArray()); }
        call.resolve(ret);
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        Context c = getContext();
        JSObject ret = new JSObject();
        ret.put("overlay", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c));
        boolean fs = true;
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            fs = nm.canUseFullScreenIntent();
        }
        ret.put("fullScreen", fs);
        boolean exact = true;
        if (Build.VERSION.SDK_INT >= 31) {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            exact = am.canScheduleExactAlarms();
        }
        ret.put("exact", exact);
        ret.put("notifications", androidx.core.app.NotificationManagerCompat.from(c).areNotificationsEnabled());
        try {
            ret.put("version", c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName);
        } catch (Exception e) { ret.put("version", ""); }
        call.resolve(ret);
    }

    @PluginMethod
    public void openOverlaySettings(PluginCall call) {
        open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getContext().getPackageName())));
        call.resolve();
    }

    @PluginMethod
    public void openFullScreenSettings(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 34) {
            open(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + getContext().getPackageName())));
        } else {
            open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName())));
        }
        call.resolve();
    }

    /** Opens Android's "Alarms & reminders" setting so dose alerts can fire at the exact minute. */
    @PluginMethod
    public void openExactAlarmSettings(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 31) {
            open(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getContext().getPackageName())));
        }
        call.resolve();
    }

    @PluginMethod
    public void openUrl(PluginCall call) {
        String url = call.getString("url", "");
        if (url.startsWith("https://")) open(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        call.resolve();
    }

    private void open(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (Exception e) {
            try {
                Intent f = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName()));
                f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(f);
            } catch (Exception ignored) {}
        }
    }
}
