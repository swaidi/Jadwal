package io.github.swaidi.jadwal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles the Taken / Skip / Snooze buttons on the notification. */
public class DoseActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !DoseLogic.ACTION_BUTTON.equals(intent.getAction())) return;
        String key = intent.getStringExtra(DoseLogic.EXTRA_KEY);
        String button = intent.getStringExtra(DoseLogic.EXTRA_BUTTON);
        if (key == null || button == null) return;
        if ("note".equals(button)) {
            android.os.Bundle r = androidx.core.app.RemoteInput.getResultsFromIntent(intent);
            CharSequence text = r == null ? null : r.getCharSequence(DoseLogic.NOTE_INPUT);
            if (text != null && text.toString().trim().length() > 0) {
                DoseLogic.addNote(context, "", key, text.toString().trim(), null, 0, "panel");
                DoseLogic.buzz(context);
            }
            DoseLogic.repostQuietly(context, key);
            return;
        }
        DoseLogic.handleButton(context, key, button);
        DoseLogic.buzz(context);
        AlarmActivity.refreshIfOpen();
    }
}
