package ru.danil.medtracker.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

/**
 * Офлайн-распознавание рецептов: Tesseract с rus+eng моделями внутри APK (п. 6.2).
 * Фото не покидает устройство. Результат — черновик, который подтверждает человек (п. 6.4).
 */
object OcrEngine {

    private const val TAG = "OcrEngine"

    /** Извлечённые черновые поля рецепта (п. 6.3). */
    data class Draft(
        val series: String? = null,
        val number: String? = null,
        val issueDate: String? = null, // ДД.ММ.ГГГГ
        val validDays: Int? = null,    // 30/60/90
        val fio: String? = null,
        val birthDate: String? = null, // ДД.ММ.ГГГГ
        val diagnosis: String? = null, // код МКБ
        val drugLines: List<String> = emptyList(),
        val rawText: String = "",
    )

    /** Целевая длинная сторона для OCR: Tesseract'у нужно ~300 DPI, а фото хранится ≤1600 px. */
    private const val TARGET_OCR_SIDE = 2600
    private const val MAX_UPSCALE = 2f

    /** Ниже этого балла текст считается нечитаемым — пробуем повернуть фото. */
    const val MIN_TEXT_SCORE = 40

    private var initialized: TessBaseAPI? = null
    private var initializedLang: String? = null

    /** Копирует traineddata из assets в filesDir/tessdata (один раз, атомарно). */
    private fun ensureTessData(context: Context): String {
        val dir = File(context.filesDir, "tessdata")
        if (!dir.exists()) dir.mkdirs()
        for (lang in listOf("rus", "eng")) {
            val target = File(dir, "$lang.traineddata")
            if (!target.exists() || target.length() == 0L) {
                // копируем во временный файл и переименовываем: обрыв процесса
                // не оставит битый traineddata, который потом не заменится
                val tmp = File(dir, "$lang.traineddata.tmp")
                context.assets.open("$lang.traineddata").use { input ->
                    FileOutputStream(tmp).use { input.copyTo(it) }
                }
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    target.delete()
                    FileOutputStream(target).use { out ->
                        context.assets.open("$lang.traineddata").use { it.copyTo(out) }
                    }
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
        val tess: TessBaseAPI
        try {
            // System.loadLibrary в статической инициализации TessBaseAPI может бросить
            // ExceptionInInitializerError (например, несовместимые 16КБ-страницы прошивки)
            tess = TessBaseAPI()
            check(tess.init(dataPath, lang)) { "Не удалось инициализировать распознавание" }
        } catch (e: Throwable) {
            Log.e(TAG, "Tesseract init failed: dataPath=$dataPath lang=$lang", e)
            throw IllegalStateException(friendlyError(e))
        }
        tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        tess.setVariable("user_defined_dpi", "300")
        initialized = tess
        initializedLang = lang
        return tess
    }

    /**
     * Человекочитаемое описание сбоя распознавания: любая ошибка нативного движка
     * (в т.ч. Error-типы, которые catch(Exception) не ловит) превращается в текст
     * для диалога вместо краша приложения.
     */
    fun friendlyError(e: Throwable): String = when (e) {
        is UnsatisfiedLinkError, is ExceptionInInitializerError ->
            "Модуль распознавания не загрузился на этом устройстве (${e.javaClass.simpleName}: ${e.message})"
        is OutOfMemoryError ->
            "Не хватило памяти для распознавания. Закройте другие приложения и попробуйте снова"
        is StackOverflowError -> "Внутренняя ошибка распознавания (StackOverflow)"
        else -> e.message ?: "Не удалось инициализировать распознавание"
    }

    /** Распознавание фото: предобработка → Tesseract → разбор полей. */
    fun recognize(context: Context, photoFile: File): Draft {
        val bmp = BitmapFactory.decodeFile(photoFile.absolutePath)
            ?: error("Не удалось открыть фото")
        val draft = recognizeBitmap(context, bmp)
        bmp.recycle()
        return draft
    }

    /**
     * Если текст не читается (балл ниже [MIN_TEXT_SCORE]) — фото может быть повёрнуто:
     * перебираем повороты 90°/270°/180° и берём лучший результат. Лечит и старые
     * снимки, сохранённые до исправления EXIF-поворота.
     */
    @Synchronized
    fun recognizeBitmap(context: Context, source: Bitmap): Draft {
        val tess = api(context)
        var bestText = runTess(tess, source)
        Log.i(TAG, "ocr ${source.width}x${source.height}: score=${scoreText(bestText)} chars=${bestText.length}")
        if (scoreText(bestText) < MIN_TEXT_SCORE) {
            for (angle in intArrayOf(90, 270, 180)) {
                val rotated = rotate(source, angle) ?: continue
                val text = runTess(tess, rotated)
                rotated.recycle()
                Log.i(TAG, "ocr rotated $angle: score=${scoreText(text)}")
                if (scoreText(text) > scoreText(bestText)) bestText = text
                if (scoreText(bestText) >= MIN_TEXT_SCORE) break
            }
        }
        return parse(bestText)
    }

    private fun runTess(tess: TessBaseAPI, bitmap: Bitmap): String {
        val processed = preprocess(bitmap)
        tess.setImage(processed)
        val text = tess.getUTF8Text() ?: ""
        processed.recycle()
        return text
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap? = try {
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    } catch (e: OutOfMemoryError) {
        null
    }

    /**
     * Грубая оценка читаемости текста: кириллица весит вдвое больше цифр.
     * Чистая функция — покрыта тестами.
     */
    fun scoreText(text: String): Int {
        var score = 0
        for (c in text) {
            when {
                c in 'А'..'я' || c == 'ё' || c == 'Ё' -> score += 2
                c in '0'..'9' -> score += 1
            }
        }
        return score
    }

    /**
     * Предобработка (п. 6.2): апскейл до ~300 DPI, оттенки серого, растяжка контраста.
     * Жёсткую бинаризацию не делаем: у Tesseract свой внутренний порог,
     * а Отсу по всему кадру на фото с тенями уничтожал текст (замечание №3).
     */
    fun preprocess(src: Bitmap): Bitmap {
        // апскейл: хранимое фото ≤1600 px — это ~140 DPI на бланке А4
        val work: Bitmap
        val longSide = maxOf(src.width, src.height).toFloat()
        if (longSide < TARGET_OCR_SIDE) {
            val scale = (TARGET_OCR_SIDE / longSide).coerceAtMost(MAX_UPSCALE).coerceAtLeast(1f)
            work = Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            work = src
        }
        val w = work.width
        val h = work.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        work.getPixels(pixels, 0, w, 0, 0, w, h)

        // серый
        val gray = IntArray(w * h)
        var min = 255
        var max = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val v = (r * 30 + g * 59 + b * 11) / 100
            gray[i] = v
            if (v < min) min = v
            if (v > max) max = v
        }
        // растяжка контраста: срезаем по 2% диапазона с каждой стороны
        val span = max - min
        if (span > 10) {
            val lo = min + span / 50
            val hi = max - span / 50
            val range = (hi - lo).coerceAtLeast(1)
            for (i in gray.indices) {
                val v = ((gray[i] - lo) * 255) / range
                gray[i] = v.coerceIn(0, 255)
            }
        }
        for (i in gray.indices) {
            val v = gray[i]
            pixels[i] = Color.rgb(v, v, v)
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        if (work !== src) work.recycle()
        return bmp
    }

    // ── Разбор полей (п. 6.3): бланки напечатаны, серии/номера/даты извлекаются надёжно ──

    /** Кириллические двойники латинских букв — частый случай в кодах МКБ (Е10 → E10). */
    private val cyrLookalikes = mapOf(
        'А' to 'A', 'В' to 'B', 'Е' to 'E', 'К' to 'K', 'М' to 'M', 'Н' to 'H',
        'О' to 'O', 'Р' to 'P', 'С' to 'C', 'Т' to 'T', 'У' to 'Y', 'Х' to 'X',
    )

    private fun toLatin(s: String): String = buildString(s.length) {
        for (c in s) append(cyrLookalikes[c] ?: c)
    }

    /** Нормализация даты из регулярки: группы 1-2-3 → «ДД.ММ.ГГГГ» или null. */
    private fun MatchResult.normalizedDate(): String? {
        val d = groupValues[1].padStart(2, '0')
        val mo = groupValues[2].padStart(2, '0')
        var y = groupValues[3]
        if (y.length == 2) y = "20$y"
        return if (y.length == 4 && mo.toInt() in 1..12 && d.toInt() in 1..31) "$d.$mo.$y" else null
    }

    fun parse(text: String): Draft {
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
            val s2 = Regex("""(?:^|\n|\r)\s*(\d{4,6})\s*№\s*((?:\d[\s-]?){8,16})""").find(text)
            if (s2 != null) {
                series = s2.groupValues[1]
                number = s2.groupValues[2].replace(Regex("[\\s-]"), "")
            }
        }

        // Даты
        val dateRe = Regex("""(\d{1,2})[.\-/](\d{1,2})[.\-/](\d{2,4})""")
        val dates = dateRe.findAll(text).mapNotNull { it.normalizedDate() }.distinct().toList()

        // Дата рождения: «Дата рождения: 01.03.2015» / «родившегося 01.03.2015».
        // Внимание: квантификатор сразу после кириллической буквы Java-regex разбирает
        // некорректно — используем символьные классы; регистр указываем явно
        // (CASE_INSENSITIVE без UNICODE_CASE кириллицу не сворачивает).
        val birthDate = Regex(
            """(?:[Рр]одив[а-яё]*|[Дд]ат[аы]\s+рожд[а-яё]*)[^\d\n]{0,25}(\d{1,2}[.\-/]\d{1,2}[.\-/]\d{2,4})""",
        ).find(text)?.groupValues?.get(1)?.let { raw ->
            dateRe.find(raw)?.normalizedDate()
        }

        // Дата выдачи: рядом с «РЕЦЕПТ ... от», иначе первая дата, не совпадающая с датой рождения
        val issueDate = Regex("""(?:РЕЦЕПТ|Рецепт)[^\n]{0,80}?от\s*(\d{1,2}[.\-/]\d{1,2}[.\-/]\d{2,4})""").find(text)
            ?.groupValues?.get(1)
            ?.let { raw -> dateRe.find(raw)?.normalizedDate() }
            ?: dates.firstOrNull { it != birthDate }
            ?: dates.firstOrNull()

        // Срок действия: «90 дней», «Продолжительность (дни) 90», «… на 60 дней»
        val validDays = Regex("""(?:Продолж|Срок|действ\w*)[^\d\n]{0,40}(\d{2,3})""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 30..90 && it % 30 == 0 }
            ?: Regex("""\b(30|60|90)\s*(?:дн|дней|суток)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\((?:дни|дн)\)\s*[:.]?\s*(30|60|90)\b""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()

        // ФИО пациента: строка с «Ф.И.О» / «ФИО» / «Пациент»
        val fio = lines.firstOrNull {
            Regex("""(Ф\.?\s?И\.?\s?О\.?|ФИО|[Пп]ациент[а-я]*)""").containsMatchIn(it)
        }
            ?.replace(Regex("""(Ф\.?\s?И\.?\s?О\.?|ФИО)\s*[:.]?\s*""", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("""^[Пп]ациент[а-я]*\s*[:.]?\s*"""), "")
            ?.trim()
            ?.takeIf { it.length >= 5 }
            ?.replace(Regex("""[|_«»":]"""), "")
            ?.trim()
            ?.takeIf { Regex("""^[А-ЯЁа-яё\s.-]+$""").matches(it) && it.split(Regex("\\s+")).size >= 2 }

        // Код МКБ: сначала в строке с «диагноз», затем по всему тексту; кириллические
        // двойники переводим в латиницу (Е10 → E10)
        val icdRe = Regex("""\b([A-TV-Z]\d{2}(?:\.\d)?)\b""")
        val diagnosis = lines.firstOrNull {
            Regex("""[Дд]иагноз|МКБ""").containsMatchIn(it)
        }?.let { icdRe.find(toLatin(it))?.groupValues?.get(1) }
            ?: icdRe.findAll(toLatin(text)).map { it.groupValues[1] }.firstOrNull { it.length in 3..5 }

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
            birthDate = birthDate,
            diagnosis = diagnosis,
            drugLines = drugLines,
            rawText = text.trim(),
        )
    }
}
