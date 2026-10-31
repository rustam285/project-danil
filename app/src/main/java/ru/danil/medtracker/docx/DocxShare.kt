package ru.danil.medtracker.docx

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Сохранение .docx в «Загрузки» и «Поделиться» (п. 8.1). */
object DocxShare {

    private const val MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Отправить документ"))
    }

    /**
     * Сохраняет файл в «Загрузки/Med Tracker» (API 29+) либо в каталог приложения
     * и показывает путь. Без разрешений на хранилище (п. 11.1).
     */
    fun saveViaSaf(context: Context, file: File) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, MIME)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Med Tracker")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Не удалось создать файл")
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: error("Не удалось записать файл")
                Toast.makeText(context, "Сохранено: Загрузки/Med Tracker/${file.name}", Toast.LENGTH_LONG).show()
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "MedTracker").apply { mkdirs() }
                val target = File(dir, file.name)
                file.copyTo(target, overwrite = true)
                Toast.makeText(context, "Сохранено: ${target.absolutePath}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Не удалось сохранить: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
