package net.kimptoc.introspect.export

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kimptoc.introspect.db.AppDatabase
import net.kimptoc.introspect.db.SampleEntity
import java.io.OutputStreamWriter

/** Dumps the `samples` table to CSV at a SAF-provided [Uri] (spec §5 export). */
object CsvExporter {

    suspend fun export(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val samples = AppDatabase.get(context).sampleDao().getAll()
        context.contentResolver.openOutputStream(uri)?.use { out ->
            OutputStreamWriter(out).use { writer ->
                writer.appendLine("timestamp,collector_id,key,value_num,value_text")
                samples.forEach { writer.appendLine(it.toCsvRow()) }
            }
        }
    }

    private fun SampleEntity.toCsvRow(): String {
        val num = valueNum?.toString().orEmpty()
        val text = valueText?.let { "\"${it.replace("\"", "\"\"")}\"" }.orEmpty()
        return "$timestamp,$collectorId,$key,$num,$text"
    }
}
