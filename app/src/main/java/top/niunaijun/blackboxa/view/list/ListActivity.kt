package top.niunaijun.blackboxa.view.list

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import cbfg.rvadapter.RVAdapter
import com.ferfalk.simplesearchview.SimpleSearchView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.InstalledAppBean
import top.niunaijun.blackboxa.databinding.ActivityListBinding
import top.niunaijun.blackboxa.util.InjectionUtil
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.view.base.BaseActivity

class ListActivity : BaseActivity() {

    private val viewBinding: ActivityListBinding by inflate()

    private lateinit var mAdapter: RVAdapter<InstalledAppBean>

    private lateinit var viewModel: ListViewModel

    private var appList: List<InstalledAppBean> = ArrayList()

    private var askedAppListPermission = false

    private var shownEmptyHint = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)

        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.installed_app, true)

        mAdapter =
                RVAdapter<InstalledAppBean>(this, ListAdapter())
                        .bind(viewBinding.recyclerView)
                        .setItemClickListener { _, item, _ -> finishWithResult(item.packageName) }

        viewBinding.recyclerView.layoutManager = LinearLayoutManager(this)

        initSearchView()
        initViewModel()
    }

    private fun initSearchView() {
        viewBinding.searchView.setOnQueryTextListener(
                object : SimpleSearchView.OnQueryTextListener {
                    override fun onQueryTextChange(newText: String): Boolean {
                        filterApp(newText)
                        return true
                    }

                    override fun onQueryTextCleared(): Boolean {
                        return true
                    }

                    override fun onQueryTextSubmit(query: String): Boolean {
                        return true
                    }
                }
        )
    }

    private fun initViewModel() {
        viewModel =
                ViewModelProvider(this, InjectionUtil.getListFactory())
                        .get(ListViewModel::class.java)
        viewBinding.toolbarLayout.toolbar.setTitle(R.string.installed_app)
    }

    // Observers are dropped in onStop, so (re)attach and reload on every start.
    // That also picks up a permission granted in system settings meanwhile.
    override fun onStart() {
        super.onStart()
        viewModel.loadingLiveData.observe(this) {
            if (it) {
                viewBinding.stateView.showLoading()
            } else {
                viewBinding.stateView.showContent()
            }
        }

        viewModel.appsLiveData.observe(this) {
            if (it != null) {
                this.appList = it
                viewBinding.searchView.setQuery("", false)
                filterApp("")
                if (it.isNotEmpty()) {
                    viewBinding.stateView.showContent()
                    viewModel.previewInstalledList()
                } else {
                    viewBinding.stateView.showEmpty()
                    onEmptyList()
                }
            }
        }
        loadApps()
    }

    private fun loadApps() {
        viewModel.getInstallAppList(intent.getIntExtra("userID", 0))
    }

    // Every phone has user apps, so an empty list means we are not allowed to
    // see them. Ask for the vendor permission where it exists, otherwise send
    // the user to the app's settings. Each is offered once per screen.
    private fun onEmptyList() {
        if (!askedAppListPermission && appListPermissionExists() &&
                        ContextCompat.checkSelfPermission(this, PERMISSION_APP_LIST) !=
                                PackageManager.PERMISSION_GRANTED
        ) {
            askedAppListPermission = true
            ActivityCompat.requestPermissions(
                    this,
                    arrayOf(PERMISSION_APP_LIST),
                    REQUEST_APP_LIST
            )
            return
        }
        if (shownEmptyHint) return
        shownEmptyHint = true
        MaterialAlertDialogBuilder(this)
                .setTitle(R.string.app_list_empty_title)
                .setMessage(R.string.app_list_empty_message)
                .setPositiveButton(R.string.open_app_settings) { _, _ ->
                    try {
                        startActivity(
                                Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:$packageName")
                                )
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Cannot open app settings: ${e.message}")
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
    }

    private fun appListPermissionExists(): Boolean {
        return try {
            packageManager.getPermissionInfo(PERMISSION_APP_LIST, 0) != null
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_APP_LIST) loadApps()
    }

    private fun filterApp(newText: String) {
        val newList =
                this.appList.filter {
                    it.name.contains(newText, true) or it.packageName.contains(newText, true)
                }
        mAdapter.setItems(newList)
    }

    private val openDocumentedResult =
            registerForActivityResult(ActivityResultContracts.GetContent()) {
                it?.run { finishWithResult(it.toString()) }
            }

    private fun finishWithResult(source: String) {
        intent.putExtra("source", source)
        setResult(Activity.RESULT_OK, intent)
        val imm: InputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        window.peekDecorView()?.run { imm.hideSoftInputFromWindow(windowToken, 0) }
        finish()
    }

    override fun onBackPressed() {
        if (viewBinding.searchView.isSearchOpen) {
            viewBinding.searchView.closeSearch()
        } else {
            super.onBackPressed()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.list_choose) {
            openDocumentedResult.launch("application/vnd.android.package-archive")
        }
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_list, menu)
        val item = menu!!.findItem(R.id.list_search)
        viewBinding.searchView.setMenuItem(item)

        return true
    }

    override fun onStop() {
        super.onStop()
        viewModel.loadingLiveData.postValue(true)
        viewModel.loadingLiveData.removeObservers(this)
        viewModel.appsLiveData.postValue(null)
        viewModel.appsLiveData.removeObservers(this)
    }

    companion object {
        private const val TAG = "ListActivity"
        private const val PERMISSION_APP_LIST = "com.android.permission.GET_INSTALLED_APPS"
        private const val REQUEST_APP_LIST = 2001

        fun start(context: Context) {
            val intent = Intent(context, ListActivity::class.java)
            context.startActivity(intent)
        }
    }
}
