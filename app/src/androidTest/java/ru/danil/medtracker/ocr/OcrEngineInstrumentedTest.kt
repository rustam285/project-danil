package ru.danil.medtracker.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Инструментальные тесты OCR-движка на реальном Android-рантайме:
 * инициализация Tesseract (нативные .so), предобработка, распознавание.
 *
 * Юнит-тесты (JVM) не могут проверить нативную библиотеку — только эти.
 */
@RunWith(AndroidJUnit4::class)
class OcrEngineInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Печатный бланк-заглушка: крупный чёрный текст на белом — движок обязан его прочитать. */
    private fun syntheticForm(): Bitmap {
        val bmp = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 56f
            isFakeBoldText = true
        }
        val lines = listOf(
            "РЕЦЕПТ",
            "Серия 75464 № 25 222 011 880",
            "от 01.09.2026",
            "Ф.И.О. пациента: Иванов Иван Иванович",
            "Дата рождения: 05.03.2015",
            "Инсулин деглудек 100 ЕД/мл",
            "Срок действия 60 дней",
        )
        lines.forEachIndexed { i, line -> canvas.drawText(line, 80f, 160f + i * 100f, paint) }
        return bmp
    }

    /** Движок инициализируется и читает крупный печатный текст (нативные библиотеки живы). */
    @Test
    fun recognizesPrintedText() {
        val draft = OcrEngine.recognizeBitmap(context, syntheticForm())
        val score = OcrEngine.scoreText(draft.rawText)
        assertTrue("Текст не распознан, score=$score, текст=\"${draft.rawText.take(120)}\"", score >= 40)
    }

    /**
     * Реальное фото бланка (личные данные в репозиторий не попадают): передайте
     * adb push photo.jpg /sdcard/Android/data/<pkg>/files/ocr_probe.jpg — тест
     * прогонит полный путь recognize() и проверит качество на реальном снимке.
     * Без файла тест пропускается.
     */
    @Test
    fun recognizesRealPhotoIfPushed() {
        val probe = File(context.getExternalFilesDir(null), "ocr_probe.jpg")
        assumeTrue("probe-фото не передано", probe.exists() && probe.length() > 0)
        val draft = OcrEngine.recognize(context, probe)
        val score = OcrEngine.scoreText(draft.rawText)
        android.util.Log.i("OcrInstrumented", "real photo score=$score chars=${draft.rawText.length} series=${draft.series} number=${draft.number} date=${draft.issueDate}")
        assertNotNull(draft.rawText)
        assertTrue("Слишком слабое распознавание реального фото: score=$score", score >= 300)
    }
}
