package top.niunaijun.blackboxa.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.App
import top.niunaijun.blackboxa.bean.AppInfo
import top.niunaijun.blackboxa.util.ContextUtil.openAppSystemSettings
import top.niunaijun.blackboxa.view.main.ShortcutActivity


object ShortcutUtil {


    
    fun createShortcut(context: Context,userID: Int, info: AppInfo) {

        if (ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            val labelName = info.name + userID
            val intent = Intent(context, ShortcutActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .putExtra("pkg", info.packageName)
                .putExtra("userId", userID)
            MaterialDialog(context).show {
                title(res = R.string.app_shortcut)
                input(
                    hintRes = R.string.shortcut_name,
                    prefill = labelName
                ) { _, input ->
                    val name = input.toString()
                    val icon = shortcutIcon(info)
                    try {
                        val builder = ShortcutInfoCompat.Builder(context, info.packageName + userID)
                                .setIntent(intent)
                                .setShortLabel(name)
                                .setLongLabel(name)
                        if (icon != null) builder.setIcon(IconCompat.createWithBitmap(icon))
                        ShortcutManagerCompat.requestPinShortcut(context, builder.build(), null)
                    } catch (e: Exception) {
                        Log.e(TAG, "requestPinShortcut failed: ${e.message}")
                    }
                    showAllowPermissionDialog(context, intent, name, icon)
                }
                positiveButton(R.string.done)
                negativeButton(R.string.cancel)
            }

        } else {
            toast(R.string.cannot_create_shortcut)
        }
    }

    // Launchers downscale anyway; a small bitmap also keeps the request well
    // under the size limit for data passed between apps.
    private fun shortcutIcon(info: AppInfo): Bitmap? {
        return try {
            info.icon?.toBitmap(ICON_SIZE, ICON_SIZE)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot render shortcut icon: ${e.message}")
            null
        }
    }

    private fun showAllowPermissionDialog(context: Context, intent: Intent, name: String, icon: Bitmap?) {
        MaterialDialog(context).show {
            title(R.string.try_add_shortcut)
            message(R.string.add_shortcut_fail_msg)
            positiveButton(R.string.done)
            negativeButton(R.string.permission_setting){
                App.getContext().openAppSystemSettings()
            }
            neutralButton(R.string.shortcut_try_other){
                toast(
                        if (installShortcutByBroadcast(context, intent, name, icon))
                                R.string.shortcut_legacy_sent
                        else R.string.shortcut_legacy_none
                )
            }
        }
    }

    // The pre-Android 8 way of adding a shortcut, which some vendor launchers
    // still honour when they turn down a pin request. The broadcast is
    // addressed to each receiver by name because implicit broadcasts no
    // longer reach receivers declared in a manifest. Returns false when no
    // launcher on the device listens for it.
    private fun installShortcutByBroadcast(context: Context, intent: Intent, name: String, icon: Bitmap?): Boolean {
        return try {
            val broadcast = Intent(ACTION_INSTALL_SHORTCUT)
                    .putExtra(Intent.EXTRA_SHORTCUT_INTENT, intent)
                    .putExtra(Intent.EXTRA_SHORTCUT_NAME, name)
                    .putExtra("duplicate", false)
            if (icon != null) broadcast.putExtra(Intent.EXTRA_SHORTCUT_ICON, icon)
            val receivers = context.packageManager.queryBroadcastReceivers(Intent(ACTION_INSTALL_SHORTCUT), 0)
            receivers.forEach {
                context.sendBroadcast(
                        Intent(broadcast).setComponent(
                                ComponentName(it.activityInfo.packageName, it.activityInfo.name)
                        )
                )
            }
            receivers.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Legacy shortcut broadcast failed: ${e.message}")
            false
        }
    }

    private const val TAG = "ShortcutUtil"
    private const val ICON_SIZE = 192
    private const val ACTION_INSTALL_SHORTCUT = "com.android.launcher.action.INSTALL_SHORTCUT"
}
