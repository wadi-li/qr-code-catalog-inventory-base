package ru.vdl.catalog

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import ru.vdl.catalog.databinding.ActivityEditBinding
import java.util.Calendar

class EditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_CODE = "code"
    }

    private lateinit var b: ActivityEditBinding
    private lateinit var db: Db
    private var item = Item()

    private val scan = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val code = res.data?.getStringExtra(ScannerActivity.EXTRA_CODE)?.trim().orEmpty()
            if (code.isNotEmpty()) {
                b.codeInput.setText(code)
                val fmt = res.data?.getStringExtra(ScannerActivity.EXTRA_FORMAT) ?: ""
                Toast.makeText(this, "Считано ($fmt): $code", Toast.LENGTH_SHORT).show()
                loadExistingByCode(code)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityEditBinding.inflate(layoutInflater)
        setContentView(b.root)
        db = Db.get(this)

        b.toolbar.setNavigationOnClickListener { finish() }

        val id = intent.getLongExtra(EXTRA_ID, 0)
        val presetCode = intent.getStringExtra(EXTRA_CODE)?.trim().orEmpty()

        if (id > 0) db.byId(id)?.let { item = it }
        else if (presetCode.isNotEmpty()) db.byCode(presetCode)?.let { item = it }

        if (item.id == 0L) {
            item.createdAt = System.currentTimeMillis()
            if (presetCode.isNotEmpty()) item.code = presetCode
        }
        bind()

        b.scanBtn.setOnClickListener { scan.launch(Intent(this, ScannerActivity::class.java)) }
        b.saveBtn.setOnClickListener { save() }
        b.deleteBtn.setOnClickListener { confirmDelete() }
        b.dateInput.setOnClickListener { pickDate() }

        // Предиктивный ввод: подсказки пересобираются, когда меняется родительский уровень
        // (по выбору из списка или при уходе фокуса), чтобы не сбивать фильтр при наборе.
        listOf(b.typeInput, b.groupInput, b.subgroupInput).forEach { field ->
            field.setOnItemClickListener { _, _, _, _ -> refreshSuggestions() }
            field.setOnFocusChangeListener { _, focused -> if (!focused) refreshSuggestions() }
        }
        refreshSuggestions()

        b.codeInput.setOnFocusChangeListener { _, focused ->
            if (!focused) loadExistingByCode(b.codeInput.text.toString().trim())
        }
    }

    private fun bind() {
        b.codeInput.setText(item.code)
        b.typeInput.setText(item.type)
        b.groupInput.setText(item.group)
        b.subgroupInput.setText(item.subgroup)
        b.nameInput.setText(item.name)
        b.commentInput.setText(item.comment)
        b.dateInput.setText(Fmt.human(if (item.createdAt > 0) item.createdAt else System.currentTimeMillis()))
        b.deleteBtn.visibility = if (item.id > 0) android.view.View.VISIBLE else android.view.View.GONE
        b.toolbar.title = if (item.id > 0) "Изменение карточки" else "Новая карточка"
        b.saveBtn.text = if (item.id > 0) "Сохранить изменения" else getString(R.string.save)
    }

    /** Если код уже в базе — подтягиваем карточку для правки. */
    private fun loadExistingByCode(code: String) {
        if (code.isBlank() || item.id > 0) return
        val existing = db.byCode(code) ?: return
        item = existing
        bind()
        refreshSuggestions()
        Toast.makeText(this, "Код уже в базе — карточка открыта для правки", Toast.LENGTH_LONG).show()
    }

    private fun fill(field: MaterialAutoCompleteTextView, values: List<String>) {
        field.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, values))
    }

    private fun refreshSuggestions() {
        val t = b.typeInput.text.toString().trim()
        val g = b.groupInput.text.toString().trim()
        val s = b.subgroupInput.text.toString().trim()
        fill(b.typeInput, db.suggest(Db.L_TYPE, ""))
        fill(b.groupInput, db.suggest(Db.L_GROUP, t))
        fill(b.subgroupInput, db.suggest(Db.L_SUBGROUP, "$t\u0001$g"))
        fill(b.nameInput, db.suggest(Db.L_NAME, "$t\u0001$g\u0001$s"))
        fill(b.commentInput, db.suggest(Db.L_COMMENT, ""))
    }

    private fun pickDate() {
        val cal = Calendar.getInstance().apply { timeInMillis = if (item.createdAt > 0) item.createdAt else System.currentTimeMillis() }
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(Calendar.YEAR, y); cal.set(Calendar.MONTH, m); cal.set(Calendar.DAY_OF_MONTH, d)
            TimePickerDialog(this, { _, h, min ->
                cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min); cal.set(Calendar.SECOND, 0)
                item.createdAt = cal.timeInMillis
                b.dateInput.setText(Fmt.human(item.createdAt))
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun save() {
        val code = b.codeInput.text.toString().trim()
        val name = b.nameInput.text.toString().trim()
        if (code.isEmpty()) { Toast.makeText(this, "Введите или отсканируйте код", Toast.LENGTH_SHORT).show(); return }
        if (name.isEmpty()) { Toast.makeText(this, "Укажите название", Toast.LENGTH_SHORT).show(); return }

        val clash = db.byCode(code)
        if (clash != null && clash.id != item.id) {
            AlertDialog.Builder(this)
                .setTitle("Код уже есть в базе")
                .setMessage("Код $code занят карточкой «${clash.name}». Обновить её данными из этой формы?")
                .setPositiveButton("Обновить") { _, _ -> item.id = clash.id; item.createdAt = clash.createdAt; persist(code, name) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        persist(code, name)
    }

    private fun persist(code: String, name: String) {
        item.code = code
        item.name = name
        item.type = b.typeInput.text.toString().trim()
        item.group = b.groupInput.text.toString().trim()
        item.subgroup = b.subgroupInput.text.toString().trim()
        item.comment = b.commentInput.text.toString().trim()
        db.save(item)
        setResult(Activity.RESULT_OK)
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Удалить карточку?")
            .setMessage("«${item.name}» (${item.code}) будет удалена из каталога.")
            .setPositiveButton(R.string.delete) { _, _ ->
                db.delete(item.id)
                setResult(Activity.RESULT_OK)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
