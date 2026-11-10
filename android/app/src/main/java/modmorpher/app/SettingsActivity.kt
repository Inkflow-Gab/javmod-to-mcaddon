package modmorpher.app

import android.os.Bundle
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

        binding.saveButton.setOnClickListener { save() }
        binding.testButton.setOnClickListener { testConnection() }
    }

    private fun save() {
        val raw = binding.serverInput.text?.toString().orEmpty()
        if (raw.isBlank()) {
            binding.statusText.setText(R.string.settings_need_url)
            return
        }
        prefs.baseUrl = raw
        prefs.token = binding.tokenInput.text?.toString().orEmpty()
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
