package edu.swjtu.campus.data

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 极简 CSV 表：用一个 .csv 文件替代真实数据库。
 * 每个操作都在文件锁内「读文件 -> 修改 -> 写回文件」，保证并发的正确性。
 */
class CsvTable(val file: File, val header: List<String>) {

    private val lock = ReentrantLock()

    init {
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            file.writeText(renderHeader() + "\n", StandardCharsets.UTF_8)
        }
    }

    /** 只读访问 */
    fun <R> read(block: (List<Map<String, String>>) -> R): R = lock.withLock {
        block(parse(file.readText(StandardCharsets.UTF_8)))
    }

    /** 读写访问，block 返回后自动落盘 */
    fun <R> tx(block: (MutableList<Map<String, String>>) -> R): R = lock.withLock {
        val rows = parse(file.readText(StandardCharsets.UTF_8)).toMutableList()
        val result = block(rows)
        save(rows)
        result
    }

    private fun save(rows: List<Map<String, String>>) {
        val sb = StringBuilder(renderHeader()).append('\n')
        for (row in rows) {
            sb.append(header.joinToString(",") { escape(row[it] ?: "") }).append('\n')
        }
        file.writeText(sb.toString(), StandardCharsets.UTF_8)
    }

    private fun renderHeader(): String = header.joinToString(",") { escape(it) }

    companion object {

        fun escape(value: String): String =
            if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                "\"" + value.replace("\"", "\"\"") + "\""
            } else {
                value
            }

        /** 解析 CSV 文本为「表头 -> 值」的映射列表 */
        fun parse(text: String): List<Map<String, String>> {
            val records = parseRecords(text)
            if (records.isEmpty()) return emptyList()
            val head = records.first()
            return records.drop(1)
                .filter { row -> row.any { it.isNotEmpty() } }
                .map { row -> head.indices.associate { i -> head[i] to (row.getOrNull(i) ?: "") } }
        }

        private fun parseRecords(text: String): List<List<String>> {
            val rows = mutableListOf<List<String>>()
            var row = mutableListOf<String>()
            val field = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    inQuotes -> {
                        if (c == '"') {
                            if (i + 1 < text.length && text[i + 1] == '"') {
                                field.append('"'); i++
                            } else {
                                inQuotes = false
                            }
                        } else {
                            field.append(c)
                        }
                    }
                    c == '"' -> inQuotes = true
                    c == ',' -> {
                        row.add(field.toString()); field.setLength(0)
                    }
                    c == '\n' -> {
                        row.add(field.toString()); field.setLength(0)
                        rows.add(row); row = mutableListOf()
                    }
                    c == '\r' -> Unit
                    else -> field.append(c)
                }
                i++
            }
            if (field.isNotEmpty() || row.isNotEmpty()) {
                row.add(field.toString())
                rows.add(row)
            }
            return rows
        }
    }
}
