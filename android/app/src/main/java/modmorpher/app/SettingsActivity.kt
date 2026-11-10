package modmorpher.app

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import modmorpher.app.data.Prefs
import modmorpher.app.databinding.ActivitySettingsBinding
import modmorpher.app.net.ModMorpherClient

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.serverInput.setText(prefs.baseUrl)
        binding.tokenInput.setText(prefs.token)

        if (prefs.usesServer) binding.modeServer.isChecked = true else binding.modeDevice.isChecked = true
        binding.modeGroup.setOnCheckedChangeListener { _, _ -> applyModeUi() }
        applyModeUi()

        binding.saveButton.setOnClickListener { save() }
        binding.testButton.setOnClickListener { testConnection() }
    }

    /** Server fields are irrelevant — and disabled — in on-device mode. */
    private fun applyModeUi() {
        val serverMode = binding.modeServer.isChecked
        val enabled = if (serverMode) View.VISIBLE else View.GONE
        binding.serverLayout.visibility = enabled
        binding.tokenLayout.visibility = enabled
        binding.testButton.visibility = enabled
        binding.modeHelp.setText(
            if (serverMode) R.string.settings_mode_server_help
            else R.string.settings_mode_device_help
        )
    }

    private fun save() {
        val serverMode = binding.modeServer.isChecked
        if (serverMode) {
            val raw = binding.serverInput.text?.toString().orEmpty()
            if (raw.isBlank()) {
                binding.statusText.setText(R.string.settings_need_url)
                return
            }
            prefs.baseUrl = raw
            prefs.token = binding.tokenInput.text?.toString().orEmpty()
            prefs.mode = Prefs.MODE_SERVER
        } else {
            // Keep any server details around for when they switch back.
            val raw = binding.serverInput.text?.toString().orEmpty()
            if (raw.isNotBlank()) {
                prefs.baseUrl = raw
                prefs.token = binding.tokenInput.text?.toString().orEmpty()
            }
            prefs.mode = Prefs.MODE_DEVICE
        }
        binding.serverInput.setText(prefs.baseUrl)
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun testConnection() {
        val url = Prefs.normalize(binding.serverInput.text?.toString().orEmpty())
        val token = binding.tokenInput.text?.toString().orEmpty().trim()
        if (url.isBlank()) {
            binding.statusText.setText(R.string.settings_need_url)
            return
        }
        binding.statusText.setText(R.string.settings_testing)
        lifecycleScope.launch {
            try {
                val health = ModMorpherClient(url, token).health()
                val java = health.optString("java").ifBlank { getString(R.string.settings_java_missing) }
                binding.statusText.text = getString(R.string.settings_ok, java)
                // A successful test is a good moment to remember the details.
                prefs.baseUrl = url
                prefs.token = token
            } catch (e: Exception) {
                binding.statusText.text = e.message ?: getString(R.string.settings_failed)
            }
        }
    }
}
