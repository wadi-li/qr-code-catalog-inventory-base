package ru.vdl.catalog

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Одна карточка каталога: Тип - Группа - Подгруппа - Название - Комментарий. */
data class Item(
    var id: Long = 0,
    var code: String = "",
    var type: String = "",
    var group: String = "",
    var subgroup: String = "",
    var name: String = "",
    var comment: String = "",
    var createdAt: Long = 0,
    var updatedAt: Long = 0
) {
    val path: String
        get() = listOf(type, group, subgroup, name).filter { it.isNotBlank() }.joinToString(" · ")
}

object Fmt {
    private val human = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru"))
    private val stampFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    fun human(ms: Long): String = if (ms <= 0) "—" else human.format(Date(ms))
    fun stamp(ms: Long = System.currentTimeMillis()): String = stampFmt.format(Date(ms))
    fun iso(ms: Long): String = if (ms <= 0) "" else isoFmt.format(Date(ms))
}

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "catalog.db", null, 1) {

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(ctx.applicationContext).also { inst = it }
        }
        const val L_TYPE = "type"
        const val L_GROUP = "grp"
        const val L_SUBGROUP = "subgroup"
        const val L_NAME = "name"
        const val L_COMMENT = "comment"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE items(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 code TEXT NOT NULL UNIQUE,
                 type TEXT NOT NULL DEFAULT '',
                 grp TEXT NOT NULL DEFAULT '',
                 subgroup TEXT NOT NULL DEFAULT '',
                 name TEXT NOT NULL DEFAULT '',
                 comment TEXT NOT NULL DEFAULT '',
                 created_at INTEGER NOT NULL,
                 updated_at INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX idx_items_name ON items(name)")
        db.execSQL("CREATE INDEX idx_items_path ON items(type, grp, subgroup)")
        // Словарь введённых значений для предиктивного ввода (живёт независимо от записей)
        db.execSQL(
            """CREATE TABLE dict(
                 level TEXT NOT NULL,
                 parent TEXT NOT NULL DEFAULT '',
                 value TEXT NOT NULL,
                 uses INTEGER NOT NULL DEFAULT 1,
                 last_used INTEGER NOT NULL,
                 PRIMARY KEY(level, parent, value))"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) { /* пока миграций нет */ }

    // ---------- записи ----------

    private fun read(c: Cursor) = Item(
        id = c.getLong(0), code = c.getString(1), type = c.getString(2), group = c.getString(3),
        subgroup = c.getString(4), name = c.getString(5), comment = c.getString(6),
        createdAt = c.getLong(7), updatedAt = c.getLong(8)
    )

    private val cols = "id,code,type,grp,subgroup,name,comment,created_at,updated_at"

    /** Поиск по коду, названию, комментарию и категориям — по любой части строки. */
    fun search(query: String, type: String = "", group: String = "", sort: String = "date"): List<Item> {
        val where = StringBuilder("1=1")
        val args = ArrayList<String>()
        query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { token ->
            where.append(" AND (code LIKE ? OR name LIKE ? OR comment LIKE ? OR type LIKE ? OR grp LIKE ? OR subgroup LIKE ?)")
            repeat(6) { args.add("%$token%") }
        }
        if (type.isNotBlank()) { where.append(" AND type = ?"); args.add(type) }
        if (group.isNotBlank()) { where.append(" AND grp = ?"); args.add(group) }
        val order = when (sort) {
            "code" -> "code COLLATE NOCASE ASC"
            "name" -> "name COLLATE NOCASE ASC"
            "path" -> "type COLLATE NOCASE, grp COLLATE NOCASE, subgroup COLLATE NOCASE, name COLLATE NOCASE"
            else -> "created_at DESC"
        }
        val out = ArrayList<Item>()
        readableDatabase.rawQuery("SELECT $cols FROM items WHERE $where ORDER BY $order", args.toTypedArray()).use {
            while (it.moveToNext()) out.add(read(it))
        }
        return out
    }

    fun all(): List<Item> = search("")

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM items", null).use {
        if (it.moveToNext()) it.getInt(0) else 0
    }

    fun byCode(code: String): Item? =
        readableDatabase.rawQuery("SELECT $cols FROM items WHERE code = ? LIMIT 1", arrayOf(code)).use {
            if (it.moveToNext()) read(it) else null
        }

    fun byId(id: Long): Item? =
        readableDatabase.rawQuery("SELECT $cols FROM items WHERE id = ? LIMIT 1", arrayOf(id.toString())).use {
            if (it.moveToNext()) read(it) else null
        }

    private fun values(i: Item) = ContentValues().apply {
        put("code", i.code); put("type", i.type); put("grp", i.group); put("subgroup", i.subgroup)
        put("name", i.name); put("comment", i.comment)
        put("created_at", i.createdAt); put("updated_at", i.updatedAt)
    }

    /** Вставка или обновление по id; возвращает id. Запоминает значения в словарь. */
    fun save(item: Item): Long {
        val now = System.currentTimeMillis()
        if (item.createdAt <= 0) item.createdAt = now
        item.updatedAt = now
        val db = writableDatabase
        val id = if (item.id > 0) {
            db.update("items", values(item), "id = ?", arrayOf(item.id.toString()))
            item.id
        } else {
            db.insertWithOnConflict("items", null, values(item), SQLiteDatabase.CONFLICT_REPLACE)
        }
        item.id = id
        remember(item)
        return id
    }

    fun delete(id: Long) { writableDatabase.delete("items", "id = ?", arrayOf(id.toString())) }

    fun deleteAll() { writableDatabase.delete("items", null, null) }

    /** Импорт списка: merge — обновить/добавить по коду, replace — очистить базу. */
    fun import(items: List<Item>, replace: Boolean): Int {
        val db = writableDatabase
        db.beginTransaction()
        var n = 0
        try {
            if (replace) db.delete("items", null, null)
            for (raw in items) {
                val it = raw.copy()
                if (it.code.isBlank()) continue
                val existing = if (replace) null else byCode(it.code)
                it.id = existing?.id ?: 0
                if (it.createdAt <= 0) it.createdAt = System.currentTimeMillis()
                val now = System.currentTimeMillis()
                it.updatedAt = if (it.updatedAt > 0) it.updatedAt else now
                if (it.id > 0) db.update("items", values(it), "id = ?", arrayOf(it.id.toString()))
                else db.insertWithOnConflict("items", null, values(it), SQLiteDatabase.CONFLICT_REPLACE)
                remember(it)
                n++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return n
    }

    // ---------- словарь предиктивного ввода ----------

    private fun bump(level: String, parent: String, value: String) {
        if (value.isBlank()) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val upd = db.compileStatement("UPDATE dict SET uses = uses + 1, last_used = ? WHERE level = ? AND parent = ? AND value = ?")
        upd.bindLong(1, now); upd.bindString(2, level); upd.bindString(3, parent); upd.bindString(4, value)
        if (upd.executeUpdateDelete() == 0) {
            db.insertWithOnConflict("dict", null, ContentValues().apply {
                put("level", level); put("parent", parent); put("value", value); put("uses", 1); put("last_used", now)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    fun remember(i: Item) {
        bump(L_TYPE, "", i.type)
        bump(L_GROUP, i.type, i.group)
        bump(L_SUBGROUP, i.type + "\u0001" + i.group, i.subgroup)
        bump(L_NAME, i.type + "\u0001" + i.group + "\u0001" + i.subgroup, i.name)
        bump(L_COMMENT, "", i.comment)
    }

    /** Подсказки уровня. Сначала значения для текущего родителя, затем все остальные того же уровня. */
    fun suggest(level: String, parent: String): List<String> {
        val out = LinkedHashSet<String>()
        readableDatabase.rawQuery(
            "SELECT value FROM dict WHERE level = ? AND parent = ? ORDER BY uses DESC, last_used DESC, value COLLATE NOCASE",
            arrayOf(level, parent)
        ).use { while (it.moveToNext()) out.add(it.getString(0)) }
        readableDatabase.rawQuery(
            "SELECT value FROM dict WHERE level = ? ORDER BY uses DESC, last_used DESC, value COLLATE NOCASE",
            arrayOf(level)
        ).use { while (it.moveToNext()) out.add(it.getString(0)) }
        return out.filter { it.isNotBlank() }
    }

    fun distinct(column: String): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT $column FROM items WHERE $column <> '' ORDER BY $column COLLATE NOCASE", null
        ).use { while (it.moveToNext()) out.add(it.getString(0)) }
        return out
    }
}
