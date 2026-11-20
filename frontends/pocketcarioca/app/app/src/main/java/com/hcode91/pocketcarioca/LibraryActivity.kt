package com.hcode91.pocketcarioca

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings as SysSettings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.concurrent.Executors

/** The home screen: a grid of games with their covers. */
class LibraryActivity : AppCompatActivity() {

    private data class Game(val title: String, val path: String)

    private lateinit var grid: GridView
    private lateinit var empty: TextView
    private lateinit var folderLabel: TextView
    private var games: List<Game> = emptyList()
    private val covers = HashMap<String, Bitmap?>()
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var settings: Settings

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        val path = Settings.treeToPath(DocumentsContract.getTreeDocumentId(uri))
        if (path == null) {
            toast(getString(R.string.folder_unsupported))
        } else {
            settings.romsDir = path
            if (!canReadAllFiles()) askAllFilesAccess()
            refresh()
        }
    }

    private val importGame = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        toast(getString(R.string.importing))
        io.execute {
            uris.forEach { copyIntoPrivate(it) }
            runOnUiThread {
                settings.romsDir = settings.privateRomsDir.absolutePath
                refresh()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.ensureInit(this)
        settings = Settings(this)
        setContentView(R.layout.activity_library)
        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        folderLabel = findViewById(R.id.folder)
        findViewById<View>(R.id.btn_folder).setOnClickListener { pickFolder.launch(null) }
        findViewById<View>(R.id.btn_import).setOnClickListener { importGame.launch(arrayOf("*/*")) }
        findViewById<View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ -> launch(games[pos]) }
        // A game asked for by another app is looked up once the library scan (onResume) is done.
        if (savedInstanceState == null) pendingLaunch = intent
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingLaunch = intent
    }

    /** A launch request from another app, waiting for the library scan. */
    private var pendingLaunch: Intent? = null

    /**
     * Another app (BacklogQuest's home-screen tiles) asks for a game with ACTION_LAUNCH_GAME and the
     * extra "game_id": its title as the library shows it, or its file name without the extension.
     * The core opens games by path, so a content URI from another app can't be used here.
     */
    private fun launchPending() {
        val i = pendingLaunch ?: return
        pendingLaunch = null
        if (i.action != ACTION_LAUNCH_GAME) return
        val wanted = i.getStringExtra("game_id")?.trim().orEmpty()
        if (wanted.isEmpty()) return
        val game = games.firstOrNull { it.title.equals(wanted, ignoreCase = true) }
            ?: games.firstOrNull { File(it.path).nameWithoutExtension.equals(wanted, ignoreCase = true) }
        if (game == null) {
            toast(getString(R.string.launch_not_found, wanted))
            return
        }
        launch(game)
        finish()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val dir = settings.romsDir
        folderLabel.text = getString(R.string.folder_label, dir)
        io.execute {
            val raw = Core.scan(dir) ?: emptyArray()
            val list = (raw.indices step 2).mapNotNull { i ->
                val t = raw[i]; val p = raw.getOrNull(i + 1)
                if (t != null && p != null) Game(t, p) else null
            }.sortedBy { it.title.lowercase() }
            runOnUiThread {
                games = list
                adapter.notifyDataSetChanged()
                empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                empty.text = if (!File(dir).canRead() || (dir != settings.privateRomsDir.absolutePath && !canReadAllFiles()))
                    getString(R.string.empty_permission) else getString(R.string.empty_library)
                if (list.isNotEmpty()) grid.requestFocus()
                launchPending()
            }
        }
    }

    private fun launch(game: Game) {
        startActivity(Intent(this, GameActivity::class.java).putExtra("path", game.path).putExtra("title", game.title))
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = games.size
        override fun getItem(position: Int) = games[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_game, parent, false)
            val g = games[position]
            v.findViewById<TextView>(R.id.title).text = g.title
            val img = v.findViewById<ImageView>(R.id.cover)
            img.tag = g.path
            if (covers.containsKey(g.path)) {
                setCover(img, covers[g.path])
            } else {
                setCover(img, null)
                io.execute {
                    val bmp = Core.toBitmap(Core.cover(g.path))
                    runOnUiThread {
                        covers[g.path] = bmp
                        if (img.tag == g.path) setCover(img, bmp)
                    }
                }
            }
            return v
        }
    }

    private fun setCover(img: ImageView, bmp: Bitmap?) {
        if (bmp != null) {
            img.setImageBitmap(bmp)
            img.setBackgroundColor(Color.TRANSPARENT)
        } else {
            img.setImageResource(R.drawable.ic_cartridge)
            img.setBackgroundColor(0xFF1C2333.toInt())
        }
    }

    private fun canReadAllFiles(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun askAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_title)
            .setMessage(R.string.permission_body)
            .setPositiveButton(R.string.permission_grant) { _, _ ->
                try {
                    startActivity(Intent(SysSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(SysSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Copies a picked file (a .mod, .zip or a game folder's file) into the private roms dir. */
    private fun copyIntoPrivate(uri: Uri) {
        val name = DocumentFile.fromSingleUri(this, uri)?.name ?: "game_${System.currentTimeMillis()}.zip"
        val out = File(settings.privateRomsDir, name.replace('/', '_'))
        contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Controller "Y"/"menu" opens settings from the library.
        if (keyCode == KeyEvent.KEYCODE_BUTTON_Y || keyCode == KeyEvent.KEYCODE_MENU) {
            startActivity(Intent(this, SettingsActivity::class.java)); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        const val ACTION_LAUNCH_GAME = "com.hcode91.pocket.LAUNCH_GAME"
    }
}
