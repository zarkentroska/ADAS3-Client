package com.github.digitallyrefined.androidipcamera

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.github.digitallyrefined.androidipcamera.helpers.CameraResolutionHelper

class SettingsDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.9).toInt() // 90% de la altura
        )
        // Fondo blanco semitransparente para mejor visibilidad
        dialog.window?.setBackgroundDrawableResource(android.R.color.white)
        dialog.window?.decorView?.setBackgroundColor(0xE6FFFFFF.toInt()) // Blanco con 90% opacidad (0xE6 = 230/255)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Crear un FrameLayout como contenedor para el PreferenceFragment con fondo blanco semitransparente
        val frameLayout = android.widget.FrameLayout(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xE6FFFFFF.toInt()) // Blanco con 90% opacidad
        }
        return frameLayout
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentByTag("settings_fragment") == null) {
            childFragmentManager
                .beginTransaction()
                .replace(view.id, SettingsFragment(), "settings_fragment")
                .commit()
        }
    }

    companion object {
        const val TAG = "SettingsDialogFragment"
        
        fun show(fragmentManager: FragmentManager) {
            SettingsDialogFragment().show(fragmentManager, TAG)
        }
    }
}

// SettingsFragment movido fuera para que sea accesible
class SettingsFragment : PreferenceFragmentCompat() {
    companion object {
        private const val PICK_CERTIFICATE_FILE = 1
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        // Set up camera resolution list with actual resolutions
        setupCameraResolutionPreference()

        // Set up certificate selection preference
        findPreference<Preference>("certificate_path")?.apply {
            setOnPreferenceClickListener {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivityForResult(
                    Intent.createChooser(intent, getString(R.string.settings_select_certificate)),
                    PICK_CERTIFICATE_FILE
                )
                true
            }
        }

        // Add listener for camera resolution changes
        findPreference<ListPreference>("camera_resolution")?.apply {
            setOnPreferenceChangeListener { preference, newValue ->
                val listPref = preference as ListPreference
                listPref.value = newValue.toString()
                listPref.summary = listPref.entry
                
                // Delay the restart to ensure preference is saved
                Handler(Looper.getMainLooper()).postDelayed({
                    // Close dialog first
                    (requireActivity() as? MainActivity)?.let {
                        val intent = Intent(it, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        it.startActivity(intent)
                        Runtime.getRuntime().exit(0)
                    } ?: run {
                        // Fallback if not MainActivity
                        val intent = Intent(requireActivity(), MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        startActivity(intent)
                        Runtime.getRuntime().exit(0)
                    }
                }, 500) // 500ms delay

                true
            }
        }
        
        // Helper function to restart app (like resolution change does)
        fun restartAppForCameraReload() {
            Handler(Looper.getMainLooper()).postDelayed({
                // Close dialog first
                (requireActivity() as? MainActivity)?.let {
                    val intent = Intent(it, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    it.startActivity(intent)
                    Runtime.getRuntime().exit(0)
                } ?: run {
                    // Fallback if not MainActivity
                    val intent = Intent(requireActivity(), MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    Runtime.getRuntime().exit(0)
                }
            }, 500) // 500ms delay to ensure preference is saved
        }
        
        // Add listener for image quality changes
        findPreference<androidx.preference.SeekBarPreference>("image_quality")?.apply {
            setOnPreferenceChangeListener { preference, newValue ->
                val seekBarPref = preference as androidx.preference.SeekBarPreference
                seekBarPref.summary = "${newValue}%"
                // Restart app to apply changes (like resolution change)
                restartAppForCameraReload()
                true
            }
            // Set initial summary
            summary = "${value}%"
        }
        
        // Add listener for camera FPS changes
        findPreference<ListPreference>("camera_fps")?.apply {
            setOnPreferenceChangeListener { preference, newValue ->
                val listPref = preference as ListPreference
                // Find the entry text for the selected value
                val index = listPref.findIndexOfValue(newValue.toString())
                listPref.summary = if (index >= 0) listPref.entries[index] else newValue.toString()
                // Restart app to apply changes
                restartAppForCameraReload()
                true
            }
            // Set initial summary from entry text
            summary = entry ?: "30 FPS Modo 1"
        }
        
        // Add listener for stream delay changes
        findPreference<Preference>("stream_delay")?.apply {
            setOnPreferenceChangeListener { preference, newValue ->
                // Restart app to apply changes (like resolution change)
                restartAppForCameraReload()
                true
            }
        }
        
        // Add listener for language changes
        findPreference<ListPreference>("app_language")?.apply {
            setOnPreferenceChangeListener { preference, newValue ->
                val listPref = preference as ListPreference
                listPref.value = newValue.toString()
                listPref.summary = listPref.entry
                
                // Apply language change
                val languageCode = newValue.toString()
                val locale = java.util.Locale(languageCode)
                java.util.Locale.setDefault(locale)
                val config = android.content.res.Configuration()
                config.setLocale(locale)
                resources.updateConfiguration(config, resources.displayMetrics)
                
                // Restart app to apply language changes
                Handler(Looper.getMainLooper()).postDelayed({
                    val activity = requireActivity()
                    val intent = Intent(activity, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    activity.startActivity(intent)
                    Runtime.getRuntime().exit(0)
                }, 500)
                
                true
            }
            // Set initial summary
            summary = entry ?: "Español"
        }
        
        // Add listener for stream port changes
        findPreference<androidx.preference.EditTextPreference>("stream_port")?.apply {
            setOnPreferenceChangeListener { preference, newValue ->
                val portValue = newValue.toString().toIntOrNull()
                if (portValue != null && portValue in 1..65535) {
                    val editTextPref = preference as androidx.preference.EditTextPreference
                    editTextPref.summary = newValue.toString()
                    // Restart app to apply port changes
                    restartAppForCameraReload()
                    true
                } else {
                    android.widget.Toast.makeText(requireContext(), getString(R.string.toast_invalid_port), android.widget.Toast.LENGTH_SHORT).show()
                    false
                }
            }
            // Set initial summary
            summary = text ?: "8080"
        }
        
        // Set up app version preference (non-clickable, just display)
        findPreference<Preference>("app_version")?.apply {
            summary = getString(R.string.settings_app_version_summary)
            isSelectable = false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == PICK_CERTIFICATE_FILE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                // Store the certificate path
                val certificatePath = uri.toString()
                preferenceManager.sharedPreferences?.edit()?.apply {
                    putString("certificate_path", certificatePath)
                    apply()
                }
                // Update the preference summary
                findPreference<Preference>("certificate_path")?.summary = certificatePath
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun setupCameraResolutionPreference() {
        val resolutionPreference = findPreference<ListPreference>("camera_resolution")
        resolutionPreference?.let { pref ->
            try {
                val cameraManager = requireContext().getSystemService(CameraManager::class.java)
                val cameraIds = cameraManager.cameraIdList
                
                // Get resolutions from back camera (or first available)
                val backCameraId = cameraIds.find { id ->
                    try {
                        val characteristics = cameraManager.getCameraCharacteristics(id)
                        characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                    } catch (e: Exception) {
                        false
                    }
                } ?: cameraIds.firstOrNull()
                
                backCameraId?.let { cameraId ->
                    val resolutionHelper = CameraResolutionHelper(requireContext())
                    val resolutions = resolutionHelper.getAllResolutions(cameraId)
                    
                    if (resolutions.isNotEmpty()) {
                        val entries = resolutions.map { "${it.width}x${it.height}" }.toTypedArray()
                        val entryValues = resolutions.map { "${it.width}x${it.height}" }.toTypedArray()
                        
                        pref.entries = entries
                        pref.entryValues = entryValues
                        
                        // Set default resolution priority: 720x480 > 1280x720 > 1920x1080 > first available
                        val currentValue = preferenceManager.sharedPreferences?.getString("camera_resolution", null)
                        val defaultResolution = when {
                            entryValues.contains("720x480") -> "720x480"
                            entryValues.contains("1280x720") -> "1280x720"
                            entryValues.contains("1920x1080") -> "1920x1080"
                            else -> entryValues[0] // Fallback to first (highest) available
                        }
                        
                        if (currentValue.isNullOrEmpty() || !entryValues.contains(currentValue)) {
                            pref.value = defaultResolution
                            preferenceManager.sharedPreferences?.edit()?.putString("camera_resolution", defaultResolution)?.apply()
                        }
                        
                        // Update summary
                        pref.summary = pref.entry
                    }
                }
            } catch (e: Exception) {
                // If we can't get resolutions, keep default behavior
                android.util.Log.e("SettingsActivity", "Error setting up camera resolutions: ${e.message}")
            }
            Unit // Explicit return to avoid expression issues
        }
    }
}

// Mantener SettingsActivity para compatibilidad, pero ahora solo muestra el dialog
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsDialogFragment.show(supportFragmentManager)
        // Cerrar esta activity inmediatamente, el dialog se mostrará sobre MainActivity
        finish()
    }
}
