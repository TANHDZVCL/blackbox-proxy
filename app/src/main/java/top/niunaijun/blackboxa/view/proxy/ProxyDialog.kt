package top.niunaijun.blackboxa.view.proxy

import android.view.View
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.proxy.ProfileProxy
import top.niunaijun.blackboxa.R
import kotlin.concurrent.thread

/** Editor for the outbound proxy of one profile. */
object ProxyDialog {

    fun show(activity: AppCompatActivity, userId: Int, profileName: String, onSaved: () -> Unit) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_proxy, null)
        val type = view.findViewById<RadioGroup>(R.id.proxy_type)
        val fields = view.findViewById<View>(R.id.proxy_fields)
        val host = view.findViewById<EditText>(R.id.proxy_host)
        val port = view.findViewById<EditText>(R.id.proxy_port)
        val user = view.findViewById<EditText>(R.id.proxy_user)
        val pass = view.findViewById<EditText>(R.id.proxy_pass)
        val status = view.findViewById<TextView>(R.id.proxy_status)

        status.setText(R.string.proxy_note)
        val current = try {
            ProfileProxy.load(userId)
        } catch (e: Exception) {
            status.text = activity.getString(R.string.proxy_test_failed, e.message)
            null
        }
        if (current != null) {
            type.check(if (current.type == ProfileProxy.TYPE_HTTP) R.id.proxy_type_http else R.id.proxy_type_socks5)
            host.setText(current.host)
            port.setText(current.port.toString())
            user.setText(current.user)
            pass.setText(current.pass)
        } else {
            type.check(R.id.proxy_type_none)
        }

        // Null when the profile should connect directly.
        fun readForm(): ProfileProxy? {
            val kind = when (type.checkedRadioButtonId) {
                R.id.proxy_type_socks5 -> ProfileProxy.TYPE_SOCKS5
                R.id.proxy_type_http -> ProfileProxy.TYPE_HTTP
                else -> return null
            }
            return ProfileProxy(kind, host.text.toString(), port.text.toString().toIntOrNull() ?: 0,
                    user.text.toString(), pass.text.toString())
        }

        val dialog = MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.proxy_title, profileName))
                .setView(view)
                .setPositiveButton(R.string.proxy_save, null)
                .setNeutralButton(R.string.proxy_test, null)
                .setNegativeButton(R.string.cancel, null)
                .create()

        dialog.setOnShowListener {
            val testButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            fun refresh() {
                val direct = type.checkedRadioButtonId == R.id.proxy_type_none
                fields.visibility = if (direct) View.GONE else View.VISIBLE
                testButton.isEnabled = !direct
            }
            refresh()
            type.setOnCheckedChangeListener { _, _ -> refresh() }

            // Set here rather than on the builder so a click does not dismiss
            // the dialog on invalid input or while testing.
            testButton.setOnClickListener {
                val proxy = readForm() ?: return@setOnClickListener
                if (!proxy.isValid) {
                    status.setText(R.string.proxy_invalid)
                    return@setOnClickListener
                }
                status.setText(R.string.proxy_testing)
                testButton.isEnabled = false
                thread(name = "ProxyTest") {
                    val message = try {
                        activity.getString(R.string.proxy_test_ok, proxy.probeExitIp())
                    } catch (e: Exception) {
                        activity.getString(R.string.proxy_test_failed, e.message ?: e.javaClass.simpleName)
                    }
                    activity.runOnUiThread {
                        if (dialog.isShowing) {
                            status.text = message
                            refresh()
                        }
                    }
                }
            }

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val proxy = readForm()
                if (proxy != null && !proxy.isValid) {
                    status.setText(R.string.proxy_invalid)
                    return@setOnClickListener
                }
                try {
                    ProfileProxy.save(userId, proxy)
                } catch (e: Exception) {
                    status.text = activity.getString(R.string.proxy_save_failed, e.message)
                    return@setOnClickListener
                }
                stopProfileApps(userId)
                status.setText(if (proxy == null) R.string.proxy_removed else R.string.proxy_saved)
                onSaved()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText(R.string.done)
            }
        }
        dialog.show()
    }

    // A running process keeps the network setup it started with, so the new
    // setting only becomes true for the profile once its apps restart.
    private fun stopProfileApps(userId: Int) {
        thread(name = "ProxyStopApps") {
            try {
                BlackBoxCore.get().getInstalledApplications(0, userId)?.forEach {
                    BlackBoxCore.get().stopPackage(it.packageName, userId)
                }
            } catch (e: Exception) {
                android.util.Log.e("ProxyDialog", "Error stopping apps of profile $userId: ${e.message}")
            }
        }
    }
}
