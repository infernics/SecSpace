package infernics.notes

import android.content.Context

object AppPreferences {
    private const val FILE_NAME = "secnote_preferences"
    private const val KEY_DARK_THEME = "dark_theme"

    fun isDarkTheme(context: Context): Boolean =
        preferences(context).getBoolean(KEY_DARK_THEME, true)

    fun setDarkTheme(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_DARK_THEME, enabled).apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
