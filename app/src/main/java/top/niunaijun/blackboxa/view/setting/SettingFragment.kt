package top.niunaijun.blackboxa.view.setting

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.gms.GmsManagerActivity
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class SettingFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.setting, rootKey)

        initGms()

        invalidHideState {
            val rootHidePreference: Preference = (findPreference("root_hide")!!)
            val hideRoot = AppManager.mBlackBoxLoader.hideRoot()
            rootHidePreference.setDefaultValue(hideRoot)
            rootHidePreference
        }

        invalidHideState {
            val daemonPreference: Preference = (findPreference("daemon_enable")!!)
            val mDaemonEnable = AppManager.mBlackBoxLoader.daemonEnable()
            daemonPreference.setDefaultValue(mDaemonEnable)
            daemonPreference
        }

        invalidHideState {
            val vpnPreference: Preference = (findPreference("use_vpn_network")!!)
            val mUseVpnNetwork = AppManager.mBlackBoxLoader.useVpnNetwork()
            vpnPreference.setDefaultValue(mUseVpnNetwork)
            vpnPreference
        }

        invalidHideState {
            val disableFlagSecurePreference: Preference = (findPreference("disable_flag_secure")!!)
            val mDisableFlagSecure = AppManager.mBlackBoxLoader.disableFlagSecure()
            disableFlagSecurePreference.setDefaultValue(mDisableFlagSecure)
            disableFlagSecurePreference
        }

        initSendLogs()
    }

    private fun initGms() {
        val gmsManagerPreference: Preference = (findPreference("gms_manager")!!)

        if (BlackBoxCore.get().isSupportGms) {

            gmsManagerPreference.setOnPreferenceClickListener {
                GmsManagerActivity.start(requireContext())
                true
            }
        } else {
            gmsManagerPreference.summary = getString(R.string.no_gms)
            gmsManagerPreference.isEnabled = false
        }
    }

    private fun invalidHideState(block: () -> Preference) {
        val pref = block()
        pref.setOnPreferenceChangeListener { preference, newValue ->
            val tmpHide = (newValue == true)
            when (preference.key) {
                "root_hide" -> {

                    AppManager.mBlackBoxLoader.invalidHideRoot(tmpHide)
                }
                "daemon_enable" -> {
                    AppManager.mBlackBoxLoader.invalidDaemonEnable(tmpHide)
                }
                "use_vpn_network" -> {
                    AppManager.mBlackBoxLoader.invalidUseVpnNetwork(tmpHide)
                }
                "disable_flag_secure" -> {
                    AppManager.mBlackBoxLoader.invalidDisableFlagSecure(tmpHide)
                }
            }

            toast(R.string.restart_module)
            return@setOnPreferenceChangeListener true
        }
    }
    // Saves the app's own log on the device instead of uploading it anywhere,
    // and offers to share the file. All cloned apps run under BlackBox's
    // identity, so this log covers them too.
    private fun initSendLogs() {
        val sendLogsPreference: Preference? = findPreference("send_logs")
        sendLogsPreference?.setOnPreferenceClickListener { preference ->
            preference.isEnabled = false
            val context = requireContext().applicationContext
            thread(name = "SaveLogs") {
                var saved: File? = null
                val message = try {
                    val dir = File(context.cacheDir, "logs")
                    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
                    dir.listFiles()?.forEach { it.delete() }
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    val file = File(dir, "blackbox-log-$stamp.txt")
                    file.outputStream().use { out ->
                        out.write(describeState(context).toByteArray())
                        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "-t", "30000"))
                        process.inputStream.use { it.copyTo(out) }
                        process.waitFor()
                    }
                    saved = file
                    // A copy where a file manager can find it. Best effort:
                    // sharing works from the private copy either way.
                    val copy = try {
                        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        downloads.mkdirs()
                        file.copyTo(File(downloads, file.name), overwrite = true).absolutePath
                    } catch (e: Exception) {
                        null
                    }
                    if (copy != null) getString(R.string.logs_saved, copy) else getString(R.string.logs_saved_private)
                } catch (e: Exception) {
                    getString(R.string.logs_save_failed, e.message ?: e.javaClass.simpleName)
                }
                activity?.runOnUiThread {
                    preference.isEnabled = true
                    val host = activity ?: return@runOnUiThread
                    val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(host)
                            .setMessage(message)
                            .setNegativeButton(R.string.done, null)
                    saved?.let { file ->
                        dialog.setPositiveButton(R.string.logs_share) { _, _ -> shareLog(file) }
                    }
                    dialog.show()
                }
            }
            true
        }
    }

    private fun shareLog(file: File) {
        try {
            val context = requireContext()
            val uri = FileProvider.getUriForFile(context, context.packageName + ".logs", file)
            val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, getString(R.string.logs_share)))
        } catch (e: Exception) {
            toast(getString(R.string.logs_save_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    // What the system currently holds for this app: its tasks (one per card
    // in the recents screen) and its processes. Cloned apps show up here as
    // BlackBox's own.
    private fun describeState(context: Context): String {
        val text = StringBuilder()
        text.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            text.append("--- tasks ---\n")
            am.appTasks.forEach { task ->
                val info = task.taskInfo
                text.append("task ").append(if (Build.VERSION.SDK_INT >= 29) info.taskId else info.id)
                        .append(" activities=").append(info.numActivities)
                        .append(" base=").append(info.baseIntent.component?.shortClassName)
                        .append(" top=").append(info.topActivity?.shortClassName)
                        .append(" label=").append(info.taskDescription?.label)
                        .append('\n')
            }
            text.append("--- processes ---\n")
            am.runningAppProcesses?.forEach {
                text.append(it.processName).append(" pid=").append(it.pid)
                        .append(" importance=").append(it.importance).append('\n')
            }
        } catch (e: Exception) {
            text.append("state unavailable: ").append(e).append('\n')
        }
        text.append("--- logcat ---\n")
        return text.toString()
    }
}
