package app.lawnchair.silencer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fired by the AlarmManager alarm a timed Silencer session schedules, to turn DND back off. */
class SilencerOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SilencerController.getInstance(context).cancelSession()
    }
}
