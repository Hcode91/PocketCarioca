package com.hcode91.pocketcarioca

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import java.io.File

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(android.R.id.content, Fragment()).commit()
        }
    }

    override fun onPause() {
        super.onPause()
        Core.applySettings(this)
    }

    class Fragment : PreferenceFragmentCompat() {
        private val pickSoundfont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            val ctx = requireContext()
            // The core opens the bank by path, so keep a private copy.
            val out = File(ctx.filesDir, "soundfont.sf2")
            ctx.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } }
            val name = DocumentFile.fromSingleUri(ctx, uri)?.name ?: "soundfont.sf2"
            PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString("soundfont", out.absolutePath).putString("soundfont_name", name).apply()
            updateSoundfontSummary()
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            findPreference<Preference>("soundfont_pick")?.setOnPreferenceClickListener {
                pickSoundfont.launch(arrayOf("*/*")); true
            }
            findPreference<Preference>("about")?.setOnPreferenceClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Hcode91/PocketCarioca")))
                true
            }
            updateSoundfontSummary()
        }

        private fun updateSoundfontSummary() {
            val p = PreferenceManager.getDefaultSharedPreferences(requireContext())
            p.getString("soundfont_name", null)?.let { findPreference<Preference>("soundfont_pick")?.summary = it }
        }
    }
}
