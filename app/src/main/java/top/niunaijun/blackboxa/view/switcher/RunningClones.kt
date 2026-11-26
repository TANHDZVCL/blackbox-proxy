package top.niunaijun.blackboxa.view.switcher

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.app.AppManager

/**
 * The cloned apps that currently have a window, one entry per system task.
 *
 * Every clone runs as BlackBox itself, in one of its numbered process slots.
 * Some recents screens show only one card per installed app, so these tasks
 * cannot be told apart, or even reached, from there.
 */
object RunningClones {
    private const val TAG = "RunningClones"
    private val STUB = Regex("""\.proxy\.(?:Transparent)?ProxyActivity\${'$'}P(\d+)$""")

    class Clone(val task: ActivityManager.AppTask, val appLabel: String, val userId: Int) {
        val profileName: String
            get() {
                val remark = AppManager.mRemarkSharedPreferences.getString("Remark$userId", null)
                return if (remark.isNullOrEmpty()) "User $userId" else remark
            }
    }

    fun list(context: Context): List<Clone> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // Process slot -> pid, from the system's view of our own processes.
        val prefix = context.packageName + ":p"
        val pidOfSlot = HashMap<Int, Int>()
        am.runningAppProcesses?.forEach { process ->
            if (process.processName.startsWith(prefix)) {
                process.processName.substring(prefix.length).toIntOrNull()?.let { pidOfSlot[it] = process.pid }
            }
        }
        // pid -> (profile, app), from the engine's process table.
        val ownerOfPid = HashMap<Int, Pair<Int, String>>()
        val core = BlackBoxCore.get()
        try {
            core.users.forEach { user ->
                core.getInstalledApplications(0, user.id)?.forEach { app ->
                    val processes = BlackBoxCore.getBActivityManager()
                            .getRunningAppProcesses(app.packageName, user.id)
                    processes?.mAppProcessInfoList?.forEach { ownerOfPid[it.pid] = Pair(user.id, app.packageName) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot read the engine's process table: ${e.message}")
        }

        val clones = ArrayList<Clone>()
        am.appTasks.forEach { task ->
            try {
                val info = task.taskInfo
                // The root activity names the slot the task was started in.
                val root = info.baseIntent.component?.className ?: return@forEach
                val slot = STUB.find(root)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                val owner = ownerOfPid[pidOfSlot[slot] ?: return@forEach] ?: return@forEach
                clones.add(Clone(task, labelOf(owner.second, info), owner.first))
            } catch (e: Exception) {
                Log.e(TAG, "Skipping a task: ${e.message}")
            }
        }
        return clones.sortedWith(compareBy({ it.userId }, { it.appLabel }))
    }

    /** BlackBox's own window, if it has one. */
    fun homeTask(context: Context): ActivityManager.AppTask? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.appTasks.firstOrNull { task ->
            try {
                val root = task.taskInfo.baseIntent.component?.className ?: ""
                root.startsWith("top.niunaijun.blackboxa.view.main.")
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun labelOf(packageName: String, info: ActivityManager.RecentTaskInfo): String {
        try {
            val pm = BlackBoxCore.getPackageManager()
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            // Not installed outside BlackBox (added from an APK file).
        }
        val label = if (Build.VERSION.SDK_INT >= 21) info.taskDescription?.label else null
        return if (label.isNullOrEmpty()) packageName else label
    }
}
