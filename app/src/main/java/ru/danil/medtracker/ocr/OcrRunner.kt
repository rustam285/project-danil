package ru.danil.medtracker.ocr

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Запуск OCR для просмотра фото (Ф1.2): перехватывает и нативные сбои (Error,
 * а не только Exception — грабли 16КБ-страниц) и отдаёт Result с friendlyError.
 */
object OcrRunner {

    fun recognize(
        scope: CoroutineScope,
        context: Context,
        file: File,
        onResult: (Result<OcrEngine.Draft>) -> Unit,
    ) {
        scope.launch {
            val res = withContext(Dispatchers.Default) {
                try {
                    Result.success(OcrEngine.recognize(context, file))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    android.util.Log.e("OcrRunner", "OCR failed: ${file.name}", e)
                    Result.failure(IllegalStateException(OcrEngine.friendlyError(e)))
                }
            }
            onResult(res)
        }
    }
}
