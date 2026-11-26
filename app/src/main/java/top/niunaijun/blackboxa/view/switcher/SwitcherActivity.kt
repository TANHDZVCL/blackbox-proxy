package top.niunaijun.blackboxa.view.switcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.view.main.MainActivity

/**
 * A small dialog that lists the running clones and brings the chosen one to
 * the front. Reached from an ongoing notification, so it is available from
 * inside any clone.
 */
class SwitcherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.switcher_title)
        val list = ListView(this)
        setContentView(list)

        val clones = try {
            RunningClones.list(this)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot list running clones: ${e.message}")
            emptyList()
        }
        val labels = ArrayList<String>()
        labels.add(getString(R.string.switcher_home))
        clones.forEach { labels.add(getString(R.string.switcher_entry, it.appLabel, it.profileName)) }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        list.setOnItemClickListener { _, _, position, _ ->
            try {
                if (position == 0) openHome() else clones[position - 1].task.moveToFront()
            } catch (e: Exception) {
                Log.e(TAG, "Cannot switch: ${e.message}")
            }
            finish()
        }
    }

    private fun openHome() {
        val home = RunningClones.homeTask(this)
        if (home != null) {
            home.moveToFront()
        } else {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    companion object {
        private const val TAG = "SwitcherActivity"
        private const val CHANNEL = "profile_switcher"
        private const val NOTIFICATION_ID = 4201

        /** Shows the ongoing notification that opens the switcher. */
        fun showNotification(context: Context) {
            try {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel(CHANNEL, context.getString(R.string.switcher_title),
                            NotificationManager.IMPORTANCE_LOW)
                    channel.setShowBadge(false)
                    manager.createNotificationChannel(channel)
                }
                val open = Intent(context, SwitcherActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
                val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(context, CHANNEL)
                } else {
                    @Suppress("DEPRECATION")
                    Notification.Builder(context)
                }
                manager.notify(NOTIFICATION_ID, builder
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle(context.getString(R.string.switcher_title))
                        .setContentText(context.getString(R.string.switcher_hint))
                        .setContentIntent(PendingIntent.getActivity(context, 0, open, flags))
                        .setOngoing(true)
                        .build())
            } catch (e: Exception) {
                Log.e(TAG, "Cannot show the switcher notification: ${e.message}")
            }
        }
    }
}
