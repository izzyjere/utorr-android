package zm.co.codelabs.utorr

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import zm.co.codelabs.utorr.databinding.FragmentSettingsBinding

/**
 * A simple [Fragment] subclass as the second destination in the navigation.
 */
class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var settingsManager: SettingsManager

    private val selectDirLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val path = it.path ?: return@let
            // Note: In a real app, you'd need to convert the content URI to a file path
            // or use document-based file access. For simplicity here, we use the URI string
            // or a placeholder if path is not simple. 
            // Better to keep it as URI if possible but TorrentManager uses File.
            // For now, let's just use the URI as a representation.
            settingsManager.downloadPath = it.toString()
            updateUI()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        settingsManager = SettingsManager(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateUI()

        binding.btnDownloadDirectory.setOnClickListener {
            selectDirLauncher.launch(null)
        }

        binding.btnTheme.setOnClickListener {
            showThemeSelectionDialog()
        }
    }

    private fun updateUI() {
        binding.tvDownloadPath.text = settingsManager.downloadPath
        binding.tvCurrentTheme.text = when (settingsManager.theme) {
            SettingsManager.THEME_LIGHT -> getString(R.string.theme_light)
            SettingsManager.THEME_DARK -> getString(R.string.theme_dark)
            else -> getString(R.string.theme_system)
        }
    }

    private fun showThemeSelectionDialog() {
        val themes = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_theme)
            .setSingleChoiceItems(themes, settingsManager.theme) { dialog, which ->
                settingsManager.theme = which
                settingsManager.applyTheme()
                updateUI()
                dialog.dismiss()
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}