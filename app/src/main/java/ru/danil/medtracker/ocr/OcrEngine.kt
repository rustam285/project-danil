package ru.danil.medtracker.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

/**
 * Офлайн-распознавание рецептов: Tesseract с rus+eng моделями внутри APK (п. 6.2).
 * Фото не покидает устройство. Результат — черновик, который подтверждает человек (п. 6.4).
 */
object OcrEngine {

    /** Извлечённые черновые поля рецепта (п. 6.3). */
    data class Draft(
        val series: String? = null,
        val number: String? = null,
        val issueDate: String? = null, // ДД.ММ.ГГГГ
        val validDays: Int? = null,    // 30/60/90
        val fio: String? = null,
        val diagnosis: String? = null, // код МКБ
        val drugLines: List<String> = emptyList(),
        val rawText: String = "",
    )

    private var initialized: TessBaseAPI? = null
    private var initializedLang: String? = null

    /** Копирует traineddata из assets в filesDir/tessdata (один раз). */
    private fun ensureTessData(context: Context): String {
        val dir = File(context.filesDir, "tessdata")
        if (!dir.exists()) dir.mkdirs()
        for (lang in listOf("rus", "eng")) {
            val target = File(dir, "$lang.traineddata")
            if (!target.exists() || target.length() == 0L) {
                context.assets.open("$lang.traineddata").use { input ->
                    FileOutputStream(target).use { input.copyTo(it) }
                }
            }
        }
        return context.filesDir.absolutePath
    }

    @Synchronized
    private fun api(context: Context): TessBaseAPI {
        val dataPath = ensureTessData(context)
        val lang = "rus+eng"
        val existing = initialized
        if (existing != null && initializedLang == lang) return existing
        existing?.recycle()
        val tess = TessBaseAPI()
        check(tess.init(dataPath, lang)) { "Не удалось инициализировать распознавание" }
        initialized = tess
        initializedLang = lang
        return tess
    }

    /** Распознавание фото: предобработка → Tesseract → разбор полей. */
    fun recognize(context: Context, photoFile: File): Draft {
        val bmp = BitmapFactory.decodeFile(photoFile.absolutePath)
            ?: error("Не удалось открыть фото")
        return recognizeBitmap(context, bmp)
    }

    fun recognizeBitmap(context: Context, source: Bitmap): Draft {
        val processed = preprocess(source)
        val tess = api(context)
        tess.setImage(processed)
        val text = tess.getUTF8Text() ?: ""
        return parse(text)
    }

    /**
     * Предобработка (п. 6.2): оттенки серого → растяжка контраста → порог Отсу.
     */
    fun preprocess(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // серый
        val gray = IntArray(w * h)
        var min = 255
        var max = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            var v = (r * 30 + g * 59 + b * 11) / 100
            if (v < min) min = v
            if (v > max) max = v
            gray[i] = v
        }
        // растяжка контраста (2–98 перцентили приближённо: обрезаем по 5% диапазона)
        val span = max - min
        if (span > 0) {
            val lo = min + span / 20
            val hi = max - span / 20
            val range = (hi - lo).coerceAtLeast(1)
            for (i in gray.indices) {
                gray[i] = (((gray[i] - lo) * 255) / range).coerceIn(0, 255)
            }
        }
        // порог Отсу
        val threshold = otsu(gray)
        for (i in gray.indices) {
            pixels[i] = if (gray[i] > threshold) Color.WHITE else Color.BLACK
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun otsu(gray: IntArray): Int {
        val hist = IntArray(256)
        gray.forEach { hist[it]++ }
        val total = gray.size
        var sum = 0L
        for (t in 0..255) sum += t.toLong() * hist[t]
        var sumB = 0L
        var wB = 0
        var maxVar = -1.0
        var threshold = 127
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val between = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)
            if (between > maxVar) {
                maxVar = between
                threshold = t
            }
        }
        return threshold
    }

    // ── Разбор полей (п. 6.3): бланки напечатаны, серии/номера/даты извлекаются надёжно ──

    fun parse(text: String): Draft {
        val clean = text.replace('\n', '\n')
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }

        // Серия и номер: «Серия 75464 № 25 222 011 880» / «Серия: 75464 №25222011880»
        var series: String? = null
        var number: String? = null
        val sn = Regex("""Сери[яию][^\d]{0,5}(\d{4,6})\D{0,10}№\s*((?:\d[\s-]?){8,16})""", RegexOption.IGNORE_CASE).find(text)
        if (sn != null) {
            series = sn.groupValues[1]
            number = sn.groupValues[2].replace(Regex("[\\s-]"), "")
        }
        if (series == null) {
            val s2 = Regex("""(?:^|\n)\s*(\d{4,6})\s*№\s*((?:\d[\s-]?){8,16})""").find(text)
            if (s2 != null) {
                series = s2.groupValues[1]
                number = s2.groupValues[2].replace(Regex("[\\s-]"), "")
            }
        }

        // Даты
        val dateRe = Regex("""(\d{1,2})[.\-/](\d{1,2})[.\-/](\d{2,4})""")
        val dates = dateRe.findAll(text).mapNotNull { m ->
            val d = m.groupValues[1].padStart(2, '0')
            val mo = m.groupValues[2].padStart(2, '0')
            var y = m.groupValues[3]
            if (y.length == 2) y = "20$y"
            if (y.length == 4 && mo.toInt() in 1..12 && d.toInt() in 1..31) "$d.$mo.$y" else null
        }.distinct().toList()

        // Дата выдачи: рядом с «РЕЦЕПТ ... от»
        val issueDate = Regex("""(?:РЕЦЕПТ|Рецепт)[^\n]{0,80}?от\s*(\d{1,2}[.\-/]\d{1,2}[.\-/]\d{2,4})""").find(text)
            ?.groupValues?.get(1)
            ?.let { raw ->
                dateRe.find(raw)?.let { m ->
                    val d = m.groupValues[1].padStart(2, '0')
                    val mo = m.groupValues[2].padStart(2, '0')
                    var y = m.groupValues[3]
                    if (y.length == 2) y = "20$y"
                    "$d.$mo.$y"
                }
            }
            ?: dates.firstOrNull()

        // Срок действия: «90 дней», «Продолжительность … 90»
        val validDays = Regex("""(?:Продолжительно\w*|Срок\w*)[^\d\n]{0,40}(\d{2,3})\s*(?:дн|дней|сут)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\b(30|60|90)\s*(?:дн|дней|суток)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()

        // ФИО пациента: строка после «Ф.И.О» / «ФИО»
        val fio = lines.firstOrNull { Regex("""(Ф\.?\s?И\.?\s?О\.?|ФИО)""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            ?.replace(Regex("""(Ф\.?\s?И\.?\s?О\.?|ФИО)\s*[:.]?\s*""", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.takeIf { it.length >= 5 }
            ?.replace(Regex("""[|_«»"]"""), "")
            ?.trim()
            ?.takeIf { Regex("""^[А-ЯЁа-яё\s.-]+$""").matches(it) && it.split(Regex("\\s+")).size >= 2 }

        // Код МКБ
        val diagnosis = Regex("""\b([A-TV-Z]\d{2}(?:\.\d)?)\b""").findAll(text)
            .map { it.groupValues[1] }
            .firstOrNull { it.length in 3..5 }

        // Кандидаты на наименование препарата: строки с кириллицей, не похожие на служебные
        val stopWords = Regex("""РЕЦЕПТ|Серия|Серию|Корешок|Пациент|Адрес|Полис|Врач|Печать|Подпис|Штамп|Отпущ|Дата|Льгот|бесплатн|ФИО|Родил|диагноз|код|Продолж|Инструкц|Талон|категории|обеспечен""", RegexOption.IGNORE_CASE)
        val drugLines = lines.filter { line ->
            val cyr = line.count { it in 'А'..'я' || it == 'ё' || it == 'Ё' }
            cyr >= 6 && !stopWords.containsMatchIn(line) && !dateRe.containsMatchIn(line)
        }.take(6)

        return Draft(
            series = series,
            number = number,
            issueDate = issueDate,
            validDays = validDays,
            fio = fio,
            diagnosis = diagnosis,
            drugLines = drugLines,
            rawText = text.trim(),
        )
    }
}
