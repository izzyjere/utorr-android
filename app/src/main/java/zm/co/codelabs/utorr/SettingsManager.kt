package zm.co.codelabs.utorr

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import androidx.appcompat.app.AppCompatDelegate
import java.io.File
import androidx.core.content.edit

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("utorr_prefs", Context.MODE_PRIVATE)

    companion object {
        const val KEY_DOWNLOAD_PATH = "download_path"
        const val KEY_THEME = "theme"
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
    }

    var downloadPath: String
        get() = prefs.getString(KEY_DOWNLOAD_PATH, getDefaultDownloadPath()) ?: getDefaultDownloadPath()
        set(value) = prefs.edit { putString(KEY_DOWNLOAD_PATH, value) }

    var theme: Int
        get() = prefs.getInt(KEY_THEME, THEME_SYSTEM)
        set(value) = prefs.edit { putInt(KEY_THEME, value) }

    var maxConns: Int
        get() = prefs.getInt("max_conns", 80)
        set(value) = prefs.edit { putInt("max_conns", value) }

    private fun getDefaultDownloadPath(): String {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
    }

    fun applyTheme() {
        when (theme) {
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }
}
