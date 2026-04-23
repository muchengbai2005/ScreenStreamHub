package com.mcbcc.mcbtm.utils

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import androidx.preference.PreferenceManager
import java.util.*

object LocaleHelper {
    private const val LANGUAGE_KEY = "language_key"

    fun setLocale(context: Context, language: String): Context {
        persistLanguage(context, language)
        return updateResources(context, language)
    }

    private fun persistLanguage(context: Context, language: String) {
        val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().putString(LANGUAGE_KEY, language).apply()
    }

    fun getLanguage(context: Context): String {
        val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        return preferences.getString(LANGUAGE_KEY, "en") ?: "en"
    }

    fun updateResources(context: Context, language: String): Context {
        val locale = when (language) {
            "zh" -> Locale.CHINESE
            else -> Locale.ENGLISH
        }

        Locale.setDefault(locale)

        val resources = context.resources
        val configuration = Configuration(resources.configuration)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            configuration.setLocale(locale)
            return context.createConfigurationContext(configuration)
        } else {
            configuration.locale = locale
            resources.updateConfiguration(configuration, resources.displayMetrics)
            return context
        }
    }

    fun applyLanguage(context: Context): Context {
        val language = getLanguage(context)
        return updateResources(context, language)
    }
}
