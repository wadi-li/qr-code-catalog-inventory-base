package ru.vdl.catalog

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ru.vdl.catalog.databinding.ActivityMainBinding
import ru.vdl.catalog.databinding.ItemRowBinding
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var db: Db
    private lateinit var prefs: Prefs
    private val adapter = ItemAdapter()
    private var filterType = ""
    private var filterGroup = ""

    private val editLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { reload() }

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val code = res.data?.getStringExtra(ScannerActivity.EXTRA_CODE)?.trim().orEmpty()
            if (code.isNotEmpty()) openEditor(0, code)
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) doImport(uri)
    }

    private val exportCsvLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(Archive.csv(db.all()).toByteArray(Charsets.UTF_8)) }
                toast("CSV сохранён")
            } catch (e: Exception) { toast("Ошибка: ${e.message}") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        prefs.applyTheme()
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        db = Db.get(this)

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        b.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun afterTextChanged(s: Editable?) = reload()
        })

        b.fabAdd.setOnClickListener { openEditor(0, "") }
        b.fabScan.setOnClickListener { scanLauncher.launch(Intent(this, ScannerActivity::class.java)) }
        b.typeFilter.setOnClickListener { pickFilter(true) }
        b.groupFilter.setOnClickListener { pickFilter(false) }
        b.sortBtn.setOnClickListener { pickSort() }

        b.toolbar.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                R.id.mTheme -> { pickTheme(); true }
                R.id.mArchiveNow -> { archiveNow(false); true }
                R.id.mSendNow -> { archiveNow(true); true }
                R.id.mShare -> { shareArchive(); true }
                R.id.mExportCsv -> { exportCsvLauncher.launch("catalog-${Fmt.stamp()}.csv"); true }
                R.id.mImport -> { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")); true }
                R.id.mSettings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                else -> false
            }
        }
        reload()
    }

    override fun onResume() { super.onResume(); reload() }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun openEditor(id: Long, code: String) {
        editLauncher.launch(Intent(this, EditActivity::class.java).apply {
            putExtra(EditActivity.EXTRA_ID, id)
            putExtra(EditActivity.EXTRA_CODE, code)
        })
    }

    private fun reload() {
        val q = b.searchInput.text?.toString().orEmpty()
        val items = db.search(q, filterType, filterGroup, prefs.sortMode)
        adapter.submit(items)
        val total = db.count()
        b.countLine.text = if (items.size == total) "$total записей в каталоге" else "${items.size} из $total записей"
        b.typeFilter.text = if (filterType.isBlank()) "Тип: все" else "Тип: $filterType"
        b.groupFilter.text = if (filterGroup.isBlank()) "Группа: все" else "Группа: $filterGroup"
        val empty = items.isEmpty()
        b.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        b.list.visibility = if (empty) View.GONE else View.VISIBLE
        b.emptyView.text = if (total == 0)
            "Записей нет. Нажмите «Сканировать», чтобы считать код бирки, или «+» для ручного ввода."
        else "По запросу ничего не найдено."
    }

    private fun pickFilter(isType: Boolean) {
        val values = db.distinct(if (isType) "type" else "grp")
        val anchor = if (isType) b.typeFilter else b.groupFilter
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 0, 0, if (isType) "Все типы" else "Все группы")
        values.forEachIndexed { i, v -> menu.menu.add(0, i + 1, i + 1, v) }
        menu.setOnMenuItemClickListener { mi ->
            val v = if (mi.itemId == 0) "" else values[mi.itemId - 1]
            if (isType) { filterType = v; filterGroup = "" } else filterGroup = v
            reload(); true
        }
        menu.show()
    }

    private fun pickSort() {
        val labels = listOf("По дате (новые сверху)" to "date", "По коду" to "code", "По названию" to "name", "По каталогу (Тип→Группа→…)" to "path")
        val menu = PopupMenu(this, b.sortBtn)
        labels.forEachIndexed { i, p -> menu.menu.add(0, i, i, p.first) }
        menu.setOnMenuItemClickListener { mi -> prefs.sortMode = labels[mi.itemId].second; reload(); true }
        menu.show()
    }

    private fun pickTheme() {
        val opts = arrayOf("Как на устройстве", "Светлая", "Тёмная")
        val keys = arrayOf("system", "light", "dark")
        AlertDialog.Builder(this)
            .setTitle("Тема оформления")
            .setSingleChoiceItems(opts, keys.indexOf(prefs.theme).coerceAtLeast(0)) { d, which ->
                prefs.theme = keys[which]; prefs.applyTheme(); d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun archiveNow(withEmail: Boolean) {
        toast(if (withEmail) "Создаю архив и отправляю…" else "Создаю архив…")
        thread {
            try {
                val f = Archive.build(this)
                var msg = "Архив создан: ${f.name} (${f.length() / 1024} КБ)"
                if (withEmail) {
                    Archive.sendByEmail(this, f)
                    msg += "\nОтправлен на ${prefs.mailTo}"
                }
                prefs.lastArchiveAt = System.currentTimeMillis()
                prefs.lastArchiveInfo = msg.replace("\n", ", ")
                runOnUiThread { toast(msg) }
            } catch (e: Exception) {
                runOnUiThread { toast("Ошибка: ${e.message}") }
            }
        }
    }

    /** Резервный путь отправки: системное меню «Поделиться» (почта, мессенджер, диск). */
    private fun shareArchive() {
        thread {
            try {
                val f = Archive.build(this)
                val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Архив каталога предметов ${Fmt.human(System.currentTimeMillis())}")
                    putExtra(Intent.EXTRA_TEXT, "Записей: ${db.count()}")
                    if (prefs.mailTo.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(prefs.mailTo))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runOnUiThread { startActivity(Intent.createChooser(intent, "Отправить архив")) }
            } catch (e: Exception) {
                runOnUiThread { toast("Ошибка: ${e.message}") }
            }
        }
    }

    private fun doImport(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
            val items = Archive.parse(text)
            if (items.isEmpty()) { toast("В файле нет записей"); return }
            AlertDialog.Builder(this)
                .setTitle("Восстановление из файла")
                .setMessage("Найдено записей: ${items.size}. В базе сейчас: ${db.count()}.\n\n" +
                        "«Объединить» — обновит совпадающие по коду и добавит новые.\n" +
                        "«Заменить» — очистит базу и запишет только импортируемое.")
                .setPositiveButton("Объединить") { _, _ ->
                    toast("Импортировано: ${db.import(items, false)}"); reload()
                }
                .setNeutralButton("Заменить") { _, _ ->
                    toast("Импортировано: ${db.import(items, true)}"); reload()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } catch (e: Exception) { toast("Ошибка чтения: ${e.message}") }
    }

    // ---------- список ----------

    inner class ItemAdapter : RecyclerView.Adapter<ItemAdapter.VH>() {
        private var data: List<Item> = emptyList()

        fun submit(items: List<Item>) { data = items; notifyDataSetChanged() }

        inner class VH(val v: ItemRowBinding) : RecyclerView.ViewHolder(v.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = data.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val it = data[pos]
            h.v.rowCode.text = it.code
            h.v.rowName.text = it.name.ifBlank { "(без названия)" }
            h.v.rowPath.text = listOf(it.type, it.group, it.subgroup).filter { s -> s.isNotBlank() }
                .joinToString(" › ").ifBlank { "категории не заданы" }
            h.v.rowComment.visibility = if (it.comment.isBlank()) View.GONE else View.VISIBLE
            h.v.rowComment.text = it.comment
            h.v.rowDate.text = Fmt.human(it.createdAt)
            h.v.root.setOnClickListener { _ -> openEditor(it.id, "") }
            h.v.root.setOnLongClickListener { _ ->
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(it.code)
                    .setItems(arrayOf("Изменить", "Копировать код", "Удалить")) { _, which ->
                        when (which) {
                            0 -> openEditor(it.id, "")
                            1 -> {
                                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("code", it.code))
                                toast("Код скопирован")
                            }
                            2 -> {
                                db.delete(it.id); reload(); toast("Удалено")
                            }
                        }
                    }.show()
                true
            }
        }
    }
}
