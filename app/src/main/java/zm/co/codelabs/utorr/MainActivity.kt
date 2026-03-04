package zm.co.codelabs.utorr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.*
import android.view.Menu
import android.view.MenuItem
import zm.co.codelabs.utorr.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private var isAnyTorrentRunning = false
    private var isAnyTorrentPresent = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Snackbar.make(binding.root, "Notifications are required for foreground service.", Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        SettingsManager(this).applyTheme()
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val navController = findNavController(R.id.nav_host_fragment_content_main)
        appBarConfiguration = AppBarConfiguration(setOf(R.id.HomeFragment, R.id.SettingsFragment, R.id.AboutFragment))
        setupActionBarWithNavController(navController, appBarConfiguration)
        binding.bottomNav.setupWithNavController(navController)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val topLevelDestinations = setOf(R.id.HomeFragment, R.id.SettingsFragment, R.id.AboutFragment)
            if (destination.id == R.id.HomeFragment) {
                binding.fab.show()
            } else {
                binding.fab.hide()
            }
            binding.bottomNav.visibility = if (destination.id in topLevelDestinations) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        }

        binding.fab.setOnClickListener {
            val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)
            val firstFragment = navHostFragment?.childFragmentManager?.fragments?.find { it is HomeFragment } as? HomeFragment
            firstFragment?.showAddTorrentDialog()
        }

        requestNotificationPermission()
    }

    fun updatePauseResumeAllMenu(torrents: List<TorrentItem>) {
        isAnyTorrentPresent = torrents.isNotEmpty()
        isAnyTorrentRunning = torrents.any { it.status != TorrentItem.Status.PAUSED }
        invalidateOptionsMenu()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        
        val pauseResumeItem = menu.findItem(R.id.action_pause_all)
        pauseResumeItem.isVisible = isAnyTorrentPresent
        if (isAnyTorrentRunning) {
            pauseResumeItem.title = getString(R.string.action_pause_all)
        } else {
            pauseResumeItem.title = getString(R.string.action_resume_all)
        }
        
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)
        val homeFragment = navHostFragment?.childFragmentManager?.fragments?.find { it is HomeFragment } as? HomeFragment

        return when (item.itemId) {
            R.id.action_pause_all -> {
                if (isAnyTorrentRunning) {
                    homeFragment?.pauseAll()
                } else {
                    homeFragment?.resumeAll()
                }
                true
            }
            else -> item.onNavDestinationSelected(navController) || super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }
}