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

    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    companion object {
        private const val KEY_URL = "base_url"
        private const val KEY_TOKEN = "token"

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
