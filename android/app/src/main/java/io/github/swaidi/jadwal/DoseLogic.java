package io.github.swaidi.jadwal;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.RemoteInput;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * Dose alerts: one exact alarm per pending dose. When it fires, a full-screen alert and an ongoing
 * notification appear; the next alarm is set for the repeat interval until Taken or Skip is pressed.
 */
public final class DoseLogic {
    static final String PREFS = "dose_alarm";
    static final String CHANNEL = "dose_alerts_v1";
    static final String ACTION_FIRE = "io.github.swaidi.jadwal.DOSE_FIRE";
    static final String ACTION_BUTTON = "io.github.swaidi.jadwal.DOSE_BUTTON";
    static final String EXTRA_KEY = "key";
    static final String EXTRA_ATTEMPT = "attempt";
    static final String EXTRA_BUTTON = "button";

    private DoseLogic() {}

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static JSONArray getArr(Context c, String name) {
        try { return new JSONArray(prefs(c).getString(name, "[]")); } catch (Exception e) { return new JSONArray(); }
    }
    static JSONObject getObj(Context c, String name) {
        try { return new JSONObject(prefs(c).getString(name, "{}")); } catch (Exception e) { return new JSONObject(); }
    }
    static void put(Context c, String name, Object v) { prefs(c).edit().putString(name, v.toString()).apply(); }

    /** Interface text sent by the app (English or Arabic); falls back to English. */
    static String label(Context c, String key, String def) {
        String v = getObj(c, "labels").optString(key, "");
        return v.isEmpty() ? def : v;
    }
    static boolean rtl(Context c) { return "ar".equals(prefs(c).getString("lang", "en")); }
    static boolean elderly(Context c) { return prefs(c).getBoolean("elderly", false); }
    /** Theme chosen in the app: auto follows the phone. */
    static boolean darkTheme(Context c) {
        String th = prefs(c).getString("theme", "auto");
        if ("dark".equals(th)) return true;
        if ("light".equals(th)) return false;
        return (c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    static long repeatMs(Context c) { return Math.max(1, prefs(c).getInt("repeatMin", 10)) * 60000L; }

    static JSONObject findDose(Context c, String key) {
        JSONArray doses = getArr(c, "doses");
        for (int i = 0; i < doses.length(); i++) {
            JSONObject d = doses.optJSONObject(i);
            if (d != null && key.equals(d.optString("key"))) return d;
        }
        return null;
    }

    static int reqCode(String key) { return key.hashCode(); }

    static PendingIntent firePI(Context c, String key, int attempt) {
        Intent i = new Intent(c, DoseAlarmReceiver.class);
        i.setAction(ACTION_FIRE);
        i.setData(Uri.parse("dose://" + Uri.encode(key)));
        i.putExtra(EXTRA_KEY, key);
        i.putExtra(EXTRA_ATTEMPT, attempt);
        return PendingIntent.getBroadcast(c, reqCode(key), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent buttonPI(Context c, String key, String button) {
        Intent i = new Intent(c, DoseActionReceiver.class);
        i.setAction(ACTION_BUTTON);
        i.setData(Uri.parse("dosebtn://" + Uri.encode(key) + "/" + button));
        i.putExtra(EXTRA_KEY, key);
        i.putExtra(EXTRA_BUTTON, button);
        return PendingIntent.getBroadcast(c, (key + button).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent alertActivityPI(Context c) {
        Intent i = new Intent(c, AlarmActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        return PendingIntent.getActivity(c, 7001, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent openAppPI(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 7002, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void setAlarm(Context c, String key, long at, int attempt) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = firePI(c, key, attempt);
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, openAppPI(c)), pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    static void cancelAlarm(Context c, String key) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.cancel(firePI(c, key, 0));
    }

    /** Re-arms every pending dose from the stored list. Safe to call any time (app start, boot, update). */
    static void scheduleAll(Context c) {
        JSONArray prev = getArr(c, "scheduledKeys");
        for (int i = 0; i < prev.length(); i++) cancelAlarm(c, prev.optString(i));
        JSONArray doses = getArr(c, "doses");
        JSONObject done = getObj(c, "done");
        JSONObject active = getObj(c, "active");
        long now = System.currentTimeMillis(), rep = repeatMs(c);
        JSONArray keys = new JSONArray();
        for (int i = 0; i < doses.length(); i++) {
            JSONObject d = doses.optJSONObject(i);
            if (d == null) continue;
            String key = d.optString("key");
            if (done.has(key)) continue;
            long base = d.optLong("base");
            long stop = d.optLong("stopMin") * 60000L;
            int k;
            long at;
            if (now < base) { k = 0; at = base; }
            else {
                k = (int) Math.ceil((now - base) / (double) rep);
                if (k == 0) k = 1;
                at = base + (long) k * rep;
                if ((long) k * rep > stop) continue;
            }
            setAlarm(c, key, at, k);
            keys.put(key);
        }
        put(c, "scheduledKeys", keys);
        // Clear alerts for doses that are no longer pending (logged in the app).
        Iterator<String> it = active.keys();
        JSONObject keep = new JSONObject();
        while (it.hasNext()) {
            String key = it.next();
            if (TEST_KEY.equals(key) || (findDose(c, key) != null && !done.has(key))) { try { keep.put(key, active.get(key)); } catch (Exception ignored) {} }
            else NotificationManagerCompat.from(c).cancel(reqCode(key));
        }
        put(c, "active", keep);
    }

    /* Alert levels. Sound: alarm | normal | vibrate | silent. Display: full | banner | quiet.
       Android fixes a channel's sound when it is created, so each sound/pop-up pair has its own channel.
       "normal" with a pop-up keeps the original channel id, so earlier settings on that channel stay. */
    static final String[] SOUNDS = {"alarm", "normal", "vibrate", "silent"};
    static final String[] DISPLAYS = {"full", "banner", "quiet"};

    static String pick(String v, String[] allowed, String def) {
        for (String a : allowed) if (a.equals(v)) return v;
        return def;
    }
    static String soundOf(Context c, JSONObject d) {
        String g = pick(prefs(c).getString("sound", "normal"), SOUNDS, "normal");
        return pick(d == null ? "" : d.optString("sound", ""), SOUNDS, g);
    }
    static String displayOf(Context c, JSONObject d) {
        String g = pick(prefs(c).getString("display", "full"), DISPLAYS, "full");
        return pick(d == null ? "" : d.optString("display", ""), DISPLAYS, g);
    }

    static void ensureChannel(Context c) { channelFor(c, "normal", "full"); }

    static String channelFor(Context c, String sound, String display) {
        boolean pop = !"quiet".equals(display);
        String id = ("normal".equals(sound) && pop) ? CHANNEL : "dose_" + sound + (pop ? "_pop" : "_quiet");
        if (Build.VERSION.SDK_INT < 26) return id;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(id) != null) return id;
        int imp = pop ? NotificationManager.IMPORTANCE_HIGH
                : ("silent".equals(sound) ? NotificationManager.IMPORTANCE_LOW : NotificationManager.IMPORTANCE_DEFAULT);
        String name = label(c, "ch_" + sound, defaultChannelName(sound)) + (pop ? "" : " · " + label(c, "ch_quiet", "quiet"));
        NotificationChannel ch = new NotificationChannel(id, name, imp);
        ch.setDescription(label(c, "ch_desc", "Medicine alerts that come back until you answer them"));
        ch.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        long[] pattern = new long[]{0, 600, 300, 600, 300, 600};
        switch (sound) {
            case "alarm": {
                Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                ch.setSound(u, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                ch.enableVibration(true);
                ch.setVibrationPattern(pattern);
                break;
            }
            case "vibrate":
                ch.setSound(null, null);
                ch.enableVibration(true);
                ch.setVibrationPattern(pattern);
                break;
            case "silent":
                ch.setSound(null, null);
                ch.enableVibration(false);
                break;
            default:
                ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                ch.enableVibration(true);
                ch.setVibrationPattern(pattern);
        }
        nm.createNotificationChannel(ch);
        return id;
    }

    static String defaultChannelName(String sound) {
        switch (sound) {
            case "alarm": return "Dose alerts – alarm sound";
            case "vibrate": return "Dose alerts – vibrate only";
            case "silent": return "Dose alerts – silent";
            default: return "Dose alerts";
        }
    }

    /** Called when an alarm fires. */
    static void fire(Context c, String key, int attempt) {
        JSONObject d = findDose(c, key);
        if (d == null || getObj(c, "done").has(key)) return;
        JSONObject active = getObj(c, "active");
        try {
            JSONObject a = new JSONObject();
            a.put("title", d.optString("title"));
            a.put("name", d.optString("name"));
            a.put("dose", d.optString("dose"));
            a.put("body", d.optString("body"));
            a.put("due", d.optString("due"));
            a.put("attempt", attempt);
            a.put("canSkip", d.optBoolean("canSkip", true));
            active.put(key, a);
        } catch (Exception ignored) {}
        put(c, "active", active);
        postNotification(c, key, d, attempt);

        // Next alarm, unless the repeat window is over.
        long rep = repeatMs(c), stop = d.optLong("stopMin") * 60000L, base = d.optLong("base");
        int next = attempt + 1;
        if ((long) next * rep <= stop) {
            long at = Math.max(base + (long) next * rep, System.currentTimeMillis() + 60000L);
            setAlarm(c, key, at, next);
        }

        if ("full".equals(displayOf(c, d)) && (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c))) launchAlert(c);
    }

    static void postNotification(Context c, String key, JSONObject d, int attempt) {
        postNotification(c, key, d, attempt, false);
    }

    /**
     * Opens the full-screen alert over whatever is on screen. On Android 15 and later the system lets an app
     * with "Display over other apps" start a screen from the background only while one of its overlay windows
     * is showing, so a tiny invisible overlay is added for a moment around the launch.
     */
    static void launchAlert(Context c) {
        View tmp = null;
        WindowManager wm = null;
        try {
            if (Build.VERSION.SDK_INT >= 26 && Settings.canDrawOverlays(c)) {
                wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
                tmp = new View(c.getApplicationContext());
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.START;
                wm.addView(tmp, lp);
            }
        } catch (Exception e) { tmp = null; }
        try {
            Intent i = new Intent(c, AlarmActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            c.startActivity(i);
        } catch (Exception ignored) {}
        if (tmp != null) {
            final View v = tmp;
            final WindowManager w = wm;
            new Handler(Looper.getMainLooper()).postDelayed(() -> { try { w.removeView(v); } catch (Exception ignored) {} }, 2000);
        }
    }

    static void postNotification(Context c, String key, JSONObject d, int attempt, boolean silent) {
        String sound = soundOf(c, d), display = displayOf(c, d);
        boolean pop = !"quiet".equals(display);
        String channel = channelFor(c, sound, display);
        String title = (attempt > 0 ? label(c, "prefix", "Reminder: ") : "") + d.optString("title");
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_stat_pill)
                .setContentTitle(title)
                .setContentText(d.optString("body"))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(d.optString("body")))
                .setPriority(pop ? NotificationCompat.PRIORITY_MAX : NotificationCompat.PRIORITY_DEFAULT)
                .setCategory("full".equals(display) || "alarm".equals(sound) ? NotificationCompat.CATEGORY_ALARM : NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setSilent(silent)
                .setContentIntent(alertActivityPI(c));
        if (Build.VERSION.SDK_INT < 26 && !silent) {
            long[] pattern = new long[]{0, 600, 300, 600, 300, 600};
            if ("alarm".equals(sound)) { b.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), android.media.AudioManager.STREAM_ALARM); b.setVibrate(pattern); }
            else if ("normal".equals(sound)) { b.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)); b.setVibrate(pattern); }
            else if ("vibrate".equals(sound)) b.setVibrate(pattern);
        }
        if (!silent && "full".equals(display)) b.setFullScreenIntent(alertActivityPI(c), true);
        if (d.optBoolean("canSkip", true)) {
            b.addAction(0, label(c, "taken", "Taken"), buttonPI(c, key, "taken"));
            b.addAction(0, label(c, "skip", "Skip"), buttonPI(c, key, "skip"));
            b.addAction(0, label(c, "snooze_short", "Snooze"), buttonPI(c, key, "snooze"));
        } else {
            b.addAction(0, label(c, "ok", "OK"), buttonPI(c, key, "dismiss"));
        }
        if (!TEST_KEY.equals(key)) {
            // "Add note" typed straight from the notification panel.
            RemoteInput ri = new RemoteInput.Builder(NOTE_INPUT).setLabel(label(c, "addnote", "Add note")).build();
            b.addAction(new NotificationCompat.Action.Builder(0, label(c, "addnote", "Add note"), notePI(c, key))
                    .addRemoteInput(ri).setAllowGeneratedReplies(false).build());
        }
        try { NotificationManagerCompat.from(c).notify(reqCode(key), b.build()); } catch (SecurityException ignored) {}
    }

    static final String TEST_KEY = "test_alert";

    /** Shows a sample alert with the chosen sound and display, without logging anything. */
    static void testAlert(Context c, JSONObject d) {
        try {
            d.put("key", TEST_KEY);
            d.put("canSkip", false);
            d.put("attempt", 0);
            JSONObject active = getObj(c, "active");
            active.put(TEST_KEY, d);
            put(c, "active", active);
        } catch (Exception ignored) {}
        postNotification(c, TEST_KEY, d, 0, false);
        if ("full".equals(displayOf(c, d)) && (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c))) launchAlert(c);
    }

    static final String NOTE_INPUT = "note_text";

    static PendingIntent notePI(Context c, String key) {
        Intent i = new Intent(c, DoseActionReceiver.class);
        i.setAction(ACTION_BUTTON);
        i.setData(Uri.parse("dosebtn://" + Uri.encode(key) + "/note"));
        i.putExtra(EXTRA_KEY, key);
        i.putExtra(EXTRA_BUTTON, "note");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        return PendingIntent.getBroadcast(c, (key + "note").hashCode(), i, flags);
    }

    /** Re-shows an active alert's notification quietly (after a note was typed in the panel). */
    static void repostQuietly(Context c, String key) {
        JSONObject a = getObj(c, "active").optJSONObject(key);
        JSONObject d = findDose(c, key);
        if (a != null && d != null) postNotification(c, key, d, a.optInt("attempt", 0), true);
        else NotificationManagerCompat.from(c).cancel(reqCode(key));
    }

    static void queue(Context c, JSONObject entry) {
        JSONArray q = getArr(c, "actions");
        q.put(entry);
        put(c, "actions", q);
        DoseAlarmPlugin.emit(entry);
    }

    /**
     * Taken, Skip, Snooze or OK, from the full-screen alert or the notification.
     * Returns the logged event (null for OK), with "firstSnooze" set on the first snooze of a dose.
     */
    static JSONObject handleButton(Context c, String key, String button) {
        JSONObject active = getObj(c, "active");
        int attempt = 0;
        JSONObject a = active.optJSONObject(key);
        if (a != null) attempt = a.optInt("attempt", 0);
        active.remove(key);
        put(c, "active", active);
        NotificationManagerCompat.from(c).cancel(reqCode(key));
        if (TEST_KEY.equals(key)) return null;

        if ("snooze".equals(button)) {
            setAlarm(c, key, System.currentTimeMillis() + repeatMs(c), attempt + 1);
        } else {
            cancelAlarm(c, key);
            markDone(c, key);
        }
        if ("dismiss".equals(button)) return null;

        JSONObject ev = new JSONObject();
        try {
            JSONObject d = findDose(c, key);
            long now = System.currentTimeMillis();
            ev.put("kind", "action");
            ev.put("id", "e" + now + Integer.toHexString(key.hashCode() & 0xfff));
            ev.put("key", key);
            ev.put("action", button);
            ev.put("at", now);
            ev.put("time", new SimpleDateFormat("HH:mm", Locale.US).format(new Date(now)));
            ev.put("dose", d != null ? d.optString("dose") : "");
            ev.put("name", d != null ? d.optString("name") : "");
            ev.put("source", "alert");
            boolean first = false;
            if ("snooze".equals(button)) {
                JSONObject sc = getObj(c, "snoozeCount");
                int n = sc.optInt(key, 0) + 1;
                sc.put(key, n);
                put(c, "snoozeCount", sc);
                first = n == 1;
            }
            queue(c, ev);
            ev.put("firstSnooze", first);
        } catch (Exception ignored) {}
        return ev;
    }

    /** Saves a typed or spoken note. ref is the event it belongs to ("" for a note on its own). */
    static void addNote(Context c, String ref, String key, String text, String audioPath, long audioMs, String source) {
        try {
            JSONObject n = new JSONObject();
            long now = System.currentTimeMillis();
            n.put("kind", "note");
            n.put("id", "n" + now);
            n.put("ref", ref == null ? "" : ref);
            n.put("key", key);
            n.put("text", text == null ? "" : text);
            n.put("audio", audioPath == null ? "" : audioPath);
            n.put("audioMs", audioMs);
            n.put("at", now);
            n.put("time", new SimpleDateFormat("HH:mm", Locale.US).format(new Date(now)));
            JSONObject d = findDose(c, key);
            n.put("dose", d != null ? d.optString("dose") : "");
            n.put("source", source);
            queue(c, n);
        } catch (Exception ignored) {}
    }

    static void buzz(Context c) {
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(70);
        } catch (Exception ignored) {}
    }

    static void markDone(Context c, String key) {
        JSONObject done = getObj(c, "done");
        try { done.put(key, true); } catch (Exception ignored) {}
        put(c, "done", done);
    }
}
