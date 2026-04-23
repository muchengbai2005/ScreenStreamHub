package com.mcbcc.mcbtm.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.mcbcc.mcbtm.R
import com.mcbcc.mcbtm.utils.LocaleHelper

class SettingsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.applyLanguage(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
    }
}