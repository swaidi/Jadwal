package io.github.swaidi.jadwal;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONObject;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.Locale;

/**
 * Full-screen dose alert. It stays until each dose is answered with Taken, Skip or Snooze.
 * After an answer it offers a note (spoken or typed) before returning to any other open alerts.
 */
public class AlarmActivity extends Activity {
    private static WeakReference<AlarmActivity> current = new WeakReference<>(null);
    private static final int REQ_MIC = 42;
    private static final long MAX_REC_MS = 120000;
    private static final long AUTO_CLOSE_MS = 30000;

    private LinearLayout list, noteBox;
    private TextView head, sub;
    private ScrollView scroll;
    private float scale = 1f;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Note flow state
    private JSONObject noteEvent;           // the action the note belongs to
    private boolean noteMode = false;
    private boolean touched = false;

    // Recording state
    private MediaRecorder recorder;
    private MediaPlayer player;
    private File recFile;
    private long recStart, recMs;
    private boolean recording = false, tapMode = false, ignoreUp = false;
    private long downAt;
    private TextView recStatus;
    private Button recButton;

    private final Runnable autoClose = () -> { if (noteMode && !touched) exitNote(); };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!recording || recStatus == null) return;
            long ms = SystemClock.elapsedRealtime() - recStart;
            recStatus.setText("● " + fmt(ms) + " / " + fmt(MAX_REC_MS));
            handler.postDelayed(this, 250);
        }
    };

    private String L(String key, String def) { return DoseLogic.label(this, key, def); }

    static void refreshIfOpen() {
        AlarmActivity a = current.get();
        if (a != null) a.runOnUiThread(() -> { if (!a.noteMode) a.render(); });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = new WeakReference<>(this);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.parseColor("#0B3B3A"));
        getWindow().setNavigationBarColor(Color.parseColor("#0B3B3A"));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        scale = DoseLogic.elderly(this) ? 1.3f : 1f;
        dark = DoseLogic.darkTheme(this);
        scroll = new ScrollView(this);
        if (DoseLogic.rtl(this)) scroll.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.setBackgroundColor(Color.parseColor("#0B3B3A"));
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(20);
        root.setPadding(pad, dp(48), pad, dp(32));

        head = text(L("header", "MEDICATION REMINDER"), 14, "#9FE3DD", true);
        if (!DoseLogic.rtl(this)) head.setLetterSpacing(0.12f);
        head.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        root.addView(head);
        sub = text(L("check", "Check the label before you take it."), 15, "#D7EFEC", false);
        sub.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        sub.setPadding(0, dp(6), 0, dp(20));
        root.addView(sub);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list, matchWrap());

        noteBox = new LinearLayout(this);
        noteBox.setOrientation(LinearLayout.VERTICAL);
        noteBox.setVisibility(View.GONE);
        root.addView(noteBox, matchWrap());

        Button open = flatButton(L("open", "Open Jadwal"), "#9FE3DD");
        open.setOnClickListener(v -> {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && km != null && km.isKeyguardLocked()) {
                km.requestDismissKeyguard(this, null);
            }
            startActivity(i);
        });
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        olp.topMargin = dp(12);
        root.addView(open, olp);

        scroll.addView(root);
        setContentView(scroll);
        // Android 15+ draws edge to edge: pad for the status bar, navigation bar and keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(false);
        new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(false);
        render();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!noteMode) render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = new WeakReference<>(this);
        if (!noteMode) render();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopRecording(true);
        stopPlaying();
        super.onDestroy();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (noteMode && !touched) { touched = true; handler.removeCallbacks(autoClose); }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onBackPressed() {
        // Intentionally ignored: the alert closes only through its buttons.
    }

    /* ---------------- Alert cards ---------------- */

    void render() {
        if (list == null) return;
        list.removeAllViews();
        JSONObject active = DoseLogic.getObj(this, "active");
        Iterator<String> it = active.keys();
        int n = 0;
        while (it.hasNext()) {
            String key = it.next();
            JSONObject a = active.optJSONObject(key);
            if (a == null) continue;
            list.addView(card(key, a));
            n++;
        }
        if (n == 0) finish();
    }

    private View card(String key, JSONObject a) {
        LinearLayout card = panel();
        String due = a.optString("due");
        TextView t = text((due.isEmpty() ? "" : due + "  ·  ") + (a.optInt("attempt") > 0 ? L("notlogged", "not logged yet") : L("due", "due now")), 14, "#4A5D63", false);
        card.addView(t);
        card.addView(text(a.optString("name"), 26, "#17242A", true));
        TextView dose = text(a.optString("dose"), 40, "#0F7F7A", true);
        dose.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        card.addView(dose);
        String body = a.optString("body");
        if (!body.isEmpty()) {
            TextView b = text(body, 15, "#4A5D63", false);
            b.setPadding(0, dp(4), 0, dp(12));
            card.addView(b);
        }

        if (a.optBoolean("canSkip", true)) {
            Button taken = solidButton("✓  " + L("taken", "Taken"), "#1D7A3E");
            taken.setOnClickListener(v -> press(key, "taken"));
            card.addView(taken, fullWidth(60));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button snooze = solidButton("⏰  " + L("snooze", "Snooze {n} min").replace("{n}", String.valueOf(Math.max(1, DoseLogic.prefs(this).getInt("repeatMin", 10)))), "#9A5A00");
            snooze.setOnClickListener(v -> press(key, "snooze"));
            Button skip = solidButton("✕  " + L("skip", "Skip"), "#A3302A");
            skip.setOnClickListener(v -> press(key, "skip"));
            LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(Math.round(56 * scale)), 1f);
            half.topMargin = dp(10);
            LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(Math.round(56 * scale)), 1f);
            half2.topMargin = dp(10);
            half2.setMarginStart(dp(10));
            row.addView(snooze, half);
            row.addView(skip, half2);
            card.addView(row);
        } else {
            Button ok = solidButton("✓  " + L("ok", "OK"), "#4A5D78");
            ok.setOnClickListener(v -> press(key, "dismiss"));
            card.addView(ok, fullWidth(56));
        }
        return card;
    }

    private void press(String key, String button) {
        JSONObject ev = DoseLogic.handleButton(this, key, button);
        DoseLogic.buzz(this);
        boolean ask = ev != null && ("taken".equals(button) || "skip".equals(button)
                || ("snooze".equals(button) && ev.optBoolean("firstSnooze", false)));
        if (ask) showNotePrompt(ev);
        else render();
    }

    /* ---------------- Note flow ---------------- */

    private String actionWord(String action) {
        if ("taken".equals(action)) return L("taken", "Taken");
        if ("skip".equals(action)) return L("skipped", "Skipped");
        return L("snoozed", "Snoozed");
    }

    private void enterNoteMode() {
        noteMode = true;
        list.setVisibility(View.GONE);
        head.setVisibility(View.GONE);
        sub.setVisibility(View.GONE);
        noteBox.setVisibility(View.VISIBLE);
        noteBox.removeAllViews();
        scroll.scrollTo(0, 0);
    }

    private void showNotePrompt(JSONObject ev) {
        noteEvent = ev;
        touched = false;
        enterNoteMode();
        LinearLayout p = panel();
        p.addView(text("✓  " + L("saved_action", "{action} saved").replace("{action}", actionWord(ev.optString("action"))), 24, "#1D7A3E", true));
        p.addView(text(ev.optString("name") + "  " + ev.optString("dose") + "  ·  " + ev.optString("time"), 16, "#4A5D63", false));
        TextView q = text(L("addnote_q", "Add a note?"), 28, "#17242A", true);
        q.setPadding(0, dp(18), 0, dp(8));
        p.addView(q);
        Button speak = solidButton("🎤  " + L("speak", "Speak"), "#2A62B8");
        speak.setOnClickListener(v -> startSpeak());
        p.addView(speak, fullWidth(68));
        Button type = solidButton("⌨  " + L("type", "Type"), "#4A5D78");
        type.setOnClickListener(v -> showType());
        p.addView(type, fullWidth(68));
        Button none = solidButton("✕  " + L("nonote", "No note"), "#5A6B72");
        none.setOnClickListener(v -> exitNote());
        p.addView(none, fullWidth(68));
        TextView hint = text(L("autoclose", "Closes by itself in 30 seconds. Your answer is already saved."), 14, "#5A6B72", false);
        hint.setPadding(0, dp(12), 0, 0);
        p.addView(hint);
        noteBox.addView(p);
        handler.removeCallbacks(autoClose);
        handler.postDelayed(autoClose, AUTO_CLOSE_MS);
    }

    private void showType() {
        enterNoteMode();
        LinearLayout p = panel();
        p.addView(text("⌨  " + L("type_title", "Type your note"), 24, "#17242A", true));
        EditText box = new EditText(this);
        box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.setMinLines(4);
        box.setGravity(Gravity.TOP | Gravity.START);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20 * scale);
        box.setTextColor(Color.parseColor(C("#17242A")));
        box.setBackground(rounded("#FFFFFF", "#4A5D63"));
        box.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams blp = matchWrap();
        blp.topMargin = dp(12);
        p.addView(box, blp);
        Button save = solidButton("✓  " + L("save", "Save"), "#1D7A3E");
        save.setOnClickListener(v -> {
            String tx = box.getText().toString().trim();
            hideKeyboard(box);
            if (tx.isEmpty()) { exitNote(); return; }
            DoseLogic.addNote(this, noteEvent.optString("id"), noteEvent.optString("key"), tx, null, 0, "alert");
            showSaved();
        });
        p.addView(save, fullWidth(68));
        Button back = solidButton("←  " + L("back", "Back"), "#5A6B72");
        back.setOnClickListener(v -> { hideKeyboard(box); showNotePrompt(noteEvent); touched = true; handler.removeCallbacks(autoClose); });
        p.addView(back, fullWidth(60));
        noteBox.addView(p);
        box.requestFocus();
        handler.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(box, InputMethodManager.SHOW_IMPLICIT);
        }, 200);
    }

    private void startSpeak() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        showSpeak();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MIC) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) showSpeak();
        else {
            showNotePrompt(noteEvent);
            touched = true;
            handler.removeCallbacks(autoClose);
            TextView msg = text(L("mic_denied", "Microphone not allowed. You can type a note instead."), 16, "#F2C065", true);
            msg.setPadding(0, dp(12), 0, 0);
            noteBox.addView(msg);
        }
    }

    private void showSpeak() {
        enterNoteMode();
        LinearLayout p = panel();
        p.addView(text("🎤  " + L("speak_title", "Record a voice note"), 24, "#17242A", true));
        p.addView(text(L("speak_hint", "Hold the button while you speak, or tap once to start and again to stop. Up to 2 minutes."), 16, "#4A5D63", false));
        recStatus = text("0:00 / " + fmt(MAX_REC_MS), 22, "#17242A", true);
        recStatus.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        recStatus.setPadding(0, dp(16), 0, dp(8));
        p.addView(recStatus);
        recButton = solidButton("🎤  " + L("rec_start", "Hold or tap to record"), "#A3302A");
        recButton.setOnTouchListener((v, e) -> onRecTouch(e));
        p.addView(recButton, fullWidth(96));
        Button back = solidButton("←  " + L("back", "Back"), "#5A6B72");
        back.setOnClickListener(v -> { stopRecording(true); showNotePrompt(noteEvent); touched = true; handler.removeCallbacks(autoClose); });
        p.addView(back, fullWidth(60));
        noteBox.addView(p);
    }

    private boolean onRecTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (recording && tapMode) { ignoreUp = true; stopRecording(false); return true; }
                if (!recording) { downAt = SystemClock.elapsedRealtime(); startRecording(); }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (ignoreUp) { ignoreUp = false; return true; }
                if (recording) {
                    if (SystemClock.elapsedRealtime() - downAt >= 700) stopRecording(false);
                    else {
                        tapMode = true;
                        recButton.setText("■  " + L("rec_tapstop", "Recording… tap to stop"));
                    }
                }
                return true;
        }
        return true;
    }

    private void startRecording() {
        try {
            File dir = new File(getFilesDir(), "notes");
            if (!dir.exists()) dir.mkdirs();
            recFile = new File(dir, noteEvent.optString("id", "note") + "_" + System.currentTimeMillis() + ".m4a");
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setAudioSamplingRate(44100);
            recorder.setMaxDuration((int) MAX_REC_MS);
            recorder.setOutputFile(recFile.getAbsolutePath());
            recorder.setOnInfoListener((mr, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) runOnUiThread(() -> stopRecording(false));
            });
            recorder.prepare();
            recorder.start();
            recording = true;
            tapMode = false;
            recStart = SystemClock.elapsedRealtime();
            recButton.setText("●  " + L("rec_holding", "Recording… release to stop"));
            handler.post(tick);
        } catch (Exception ex) {
            releaseRecorder();
            recording = false;
            if (recFile != null) recFile.delete();
            if (recStatus != null) recStatus.setText(L("rec_failed", "Could not start recording."));
        }
    }

    /** Stops recording. discard=true deletes the file (Back or leaving the screen). */
    private void stopRecording(boolean discard) {
        if (!recording) return;
        recording = false;
        handler.removeCallbacks(tick);
        recMs = SystemClock.elapsedRealtime() - recStart;
        boolean ok = true;
        try { recorder.stop(); } catch (RuntimeException ex) { ok = false; }
        releaseRecorder();
        if (discard || !ok || recMs < 600) {
            if (recFile != null) recFile.delete();
            recFile = null;
            if (!discard && recStatus != null) {
                recStatus.setText(L("rec_short", "Too short. Try again."));
                recButton.setText("🎤  " + L("rec_start", "Hold or tap to record"));
            }
            return;
        }
        showReview();
    }

    private void releaseRecorder() {
        if (recorder != null) { try { recorder.release(); } catch (Exception ignored) {} recorder = null; }
    }

    private void showReview() {
        enterNoteMode();
        LinearLayout p = panel();
        p.addView(text("🎤  " + L("rec_done", "Voice note recorded") + "  (" + fmt(Math.min(recMs, MAX_REC_MS)) + ")", 22, "#17242A", true));
        Button play = solidButton("▶  " + L("play", "Play"), "#2A62B8");
        play.setOnClickListener(v -> togglePlay(play));
        p.addView(play, fullWidth(64));
        Button again = solidButton("↻  " + L("rerecord", "Re-record"), "#9A5A00");
        again.setOnClickListener(v -> { stopPlaying(); if (recFile != null) recFile.delete(); recFile = null; showSpeak(); });
        p.addView(again, fullWidth(64));
        Button del = solidButton("🗑  " + L("delete", "Delete"), "#A3302A");
        del.setOnClickListener(v -> { stopPlaying(); if (recFile != null) recFile.delete(); recFile = null; showNotePrompt(noteEvent); touched = true; handler.removeCallbacks(autoClose); });
        p.addView(del, fullWidth(64));
        Button save = solidButton("✓  " + L("save", "Save"), "#1D7A3E");
        save.setOnClickListener(v -> {
            stopPlaying();
            if (recFile != null && recFile.exists()) {
                DoseLogic.addNote(this, noteEvent.optString("id"), noteEvent.optString("key"), "", recFile.getAbsolutePath(), Math.min(recMs, MAX_REC_MS), "alert");
                recFile = null;
                showSaved();
            } else exitNote();
        });
        p.addView(save, fullWidth(72));
        noteBox.addView(p);
    }

    private void togglePlay(Button b) {
        if (player != null) { stopPlaying(); b.setText("▶  " + L("play", "Play")); return; }
        if (recFile == null) return;
        try {
            player = new MediaPlayer();
            player.setDataSource(recFile.getAbsolutePath());
            player.setOnCompletionListener(mp -> { stopPlaying(); b.setText("▶  " + L("play", "Play")); });
            player.prepare();
            player.start();
            b.setText("■  " + L("stop", "Stop"));
        } catch (Exception ex) { stopPlaying(); }
    }

    private void stopPlaying() {
        if (player != null) { try { player.stop(); } catch (Exception ignored) {} try { player.release(); } catch (Exception ignored) {} player = null; }
    }

    private void showSaved() {
        DoseLogic.buzz(this);
        enterNoteMode();
        LinearLayout p = panel();
        TextView t = text("✓  " + L("saved", "Saved"), 40, "#1D7A3E", true);
        t.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        t.setPadding(0, dp(30), 0, dp(30));
        p.addView(t);
        noteBox.addView(p);
        handler.postDelayed(this::exitNote, 1300);
    }

    private void exitNote() {
        handler.removeCallbacks(autoClose);
        stopRecording(true);
        stopPlaying();
        if (recFile != null) { recFile.delete(); recFile = null; }
        noteMode = false;
        noteEvent = null;
        noteBox.removeAllViews();
        noteBox.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        head.setVisibility(View.VISIBLE);
        sub.setVisibility(View.VISIBLE);
        render();
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    /* ---------------- Helpers ---------------- */

    private static String fmt(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /** Card colors follow the app theme: soft cream when light, dark slate when dark. */
    private boolean dark;
    private String C(String hex) {
        switch (hex.toUpperCase(java.util.Locale.ROOT)) {
            case "#F4FBFA": return dark ? "#172225" : "#FBF8F2";
            case "#FFFFFF": return dark ? "#0F1719" : "#FFFDF8";
            case "#17242A": return dark ? "#E6EEF0" : hex;
            case "#4A5D63": return dark ? "#9DB0B6" : hex;
            case "#5A6B72": return dark ? "#9DB0B6" : hex;
            case "#0F7F7A": return dark ? "#4CC7BF" : hex;
            case "#1D7A3E": return dark ? "#6FD393" : hex;
            default: return hex;
        }
    }

    private LinearLayout panel() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(rounded("#F4FBFA", null));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(14);
        card.setLayoutParams(lp);
        return card;
    }

    private GradientDrawable rounded(String fill, String stroke) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(C(fill)));
        bg.setCornerRadius(dp(16));
        if (stroke != null) bg.setStroke(dp(2), Color.parseColor(C(stroke)));
        return bg;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams fullWidth(int hDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(Math.round(hDp * scale)));
        p.topMargin = dp(10);
        return p;
    }

    private TextView text(String s, int sp, String color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * scale);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        t.setTextColor(Color.parseColor(C(color)));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button solidButton(String label, String color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20 * scale);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(color));
        bg.setCornerRadius(dp(14));
        b.setBackground(bg);
        return b;
    }

    private Button flatButton(String label, String color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15 * scale);
        b.setTextColor(Color.parseColor(color));
        b.setBackgroundColor(Color.TRANSPARENT);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
