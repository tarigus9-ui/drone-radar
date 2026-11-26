package com.droneradar

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** In-app language (independent of the phone language). Saved by AppCompat; layouts flip RTL/LTR. */
object Lang {
    /** "No ID" in the app's current language, for places without a Context (DroneInfo.label). */
    val noId: String get() {
        val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags().ifEmpty { java.util.Locale.getDefault().language }
        return if (tag.startsWith("en")) "No ID" else "بلا هوية"
    }
    private val choices = listOf("ar" to "العربية", "en" to "English")

    fun pick(a: Activity) {
        val cur = AppCompatDelegate.getApplicationLocales().toLanguageTags().take(2)
            .ifBlank { a.resources.configuration.locales[0].language }
        val idx = choices.indexOfFirst { it.first == cur }.coerceAtLeast(0)
        AlertDialog.Builder(a)
            .setTitle(R.string.language)
            .setSingleChoiceItems(choices.map { it.second }.toTypedArray(), idx) { d, which ->
                d.dismiss()
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(choices[which].first))
            }
            .show()
    }
}
