package modmorpher.app.data

import android.content.Context
import android.content.SharedPreferences

/** Server address + token. The token is not a password: it only guards the
 *  local ModMorpher server you are running, and can be regenerated there. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("modmorpher", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString(KEY_URL, "").orEmpty()
        set(value) {
            sp.edit().putString(KEY_URL, normalize(value)).apply()
        }

    var token: String
        get() = sp.getString(KEY_TOKEN, "").orEmpty()
        set(value) {
            sp.edit().putString(KEY_TOKEN, value.trim()).apply()
        }

    /** Where conversion happens. On-device is the default so the app is
     *  usable with nothing but a phone. */
    var mode: String
        get() = sp.getString(KEY_MODE, MODE_DEVICE).orEmpty()
        set(value) {
            sp.edit().putString(KEY_MODE, value).apply()
        }

    val usesServer: Boolean get() = mode == MODE_SERVER

    /** Server mode needs an address; on-device mode needs nothing. */
    val isConfigured: Boolean get() = !usesServer || baseUrl.isNotBlank()

    companion object {
        const val MODE_DEVICE = "device"
        const val MODE_SERVER = "server"

        private const val KEY_URL = "base_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_MODE = "mode"

        /** Accepts "192.168.1.10:8765" and turns it into a usable base URL. */
        fun normalize(raw: String): String {
            var value = raw.trim()
            if (value.isEmpty()) return ""
            if (!value.startsWith("http://") && !value.startsWith("https://")) {
                value = "http://$value"
            }
            return value.trimEnd('/')
        }
    }
}
