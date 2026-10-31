package ru.danil.medtracker.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * Фото рецептов: приватный каталог + сжатие до 1600 px / JPEG 80 (п. 6.1).
 *
 * Импорт устойчив к особенностям провайдеров Xiaomi/HyperOS и «облачных» пикеров:
 * поток копируется в кэш-файл один раз (повторное открытие URI бывает недоступно),
 * декодирование — сначала ImageDecoder (HEIC/HEIF + автоматический EXIF-поворот),
 * затем BitmapFactory с ручным поворотом. Сбой декодирования даёт понятную ошибку
 * и подробный лог, а не безликое «не удалось».
 */
object PhotoStore {

    const val MAX_SIDE = 1600
    const val JPEG_QUALITY = 80
    private const val TAG = "PhotoStore"

    fun dir(context: Context): File = File(context.filesDir, "photos").apply { mkdirs() }

    /**
     * Сохраняет изображение из URI (камера/галерея) со сжатием до [MAX_SIDE]/JPEG80.
     * Возвращает относительный путь (rx_<id>_<n>.jpg) или бросает понятную ошибку.
     */
    fun saveCompressed(context: Context, source: Uri, nameHint: String): String {
        // Один раз читаем поток в кэш-файл: URI галереи/пикера не всегда можно
        // открыть повторно, а «облачный» элемент может отдавать поток порциями.
        val importDir = File(context.cacheDir, "import").apply { mkdirs() }
        val tmp = File(importDir, "$nameHint.tmp")
        try {
            val copied = runCatching {
                context.contentResolver.openInputStream(source)?.use { input ->
                    FileOutputStream(tmp).use { input.copyTo(it) }
                } != null
            }.onFailure { Log.e(TAG, "openInputStream failed: uri=$source", it) }.getOrDefault(false)
            if (!copied || tmp.length() == 0L) {
                throw IllegalStateException("Не удалось прочитать изображение: источник недоступен или пуст (${source.scheme})")
            }
            val magic = tmp.inputStream().use { s ->
                val b = ByteArray(12)
                s.read(b)
                b.joinToString("") { "%02x".format(it) }
            }
            Log.i(TAG, "import uri=$source bytes=${tmp.length()} magic=$magic")

            val orientation = runCatching { ExifInterface(tmp).rotationDegrees }.getOrDefault(0)
            val bmp = decodeScaled(tmp, orientation)
                ?: throw IllegalStateException(
                    "Не удалось декодировать изображение (${tmp.length() / 1024} КБ, заголовок $magic). " +
                        "Формат не поддерживается — переснимите и пришлите скриншот этой ошибки.",
                )
            val target = File(dir(context), "$nameHint.jpg")
            try {
                FileOutputStream(target).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
            } catch (e: Exception) {
                target.delete()
                throw IllegalStateException("Не хватает места для сохранения фото")
            } finally {
                runCatching { bmp.recycle() }
            }
            return target.name
        } finally {
            tmp.delete()
        }
    }

    /** Декодирование с уменьшением до [MAX_SIDE] по длинной стороне; EXIF-поворот учитывается. */
    private fun decodeScaled(file: File, exifDegrees: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder: понимает HEIC/HEIF и сам применяет EXIF-поворот
            try {
                val src = ImageDecoder.createSource(file)
                val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longSide = maxOf(info.size.width, info.size.height)
                    if (longSide > MAX_SIDE) {
                        decoder.setTargetSampleSize(sampleFor(longSide))
                    }
                }
                return downscale(bmp)
            } catch (e: Throwable) {
                // в т.ч. OutOfMemoryError — BitmapFactory ниже либо справится, либо даст понятную ошибку
                Log.w(TAG, "ImageDecoder failed (${file.name}), fallback to BitmapFactory", e)
            }
        }
        // Старый путь (API 26–27 и отказ ImageDecoder): BitmapFactory игнорирует EXIF —
        // поворот применяем вручную.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(maxOf(bounds.outWidth, bounds.outHeight)) }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        val upright = if (exifDegrees != 0) applyRotation(decoded, exifDegrees) else decoded
        return downscale(upright)
    }

    /** Степень подвыборки (степень двойки), чтобы длинная сторона стала ≤ [MAX_SIDE]. */
    private fun sampleFor(longSide: Int): Int {
        var sample = 1
        while (longSide / (sample * 2) >= MAX_SIDE) sample *= 2
        return sample
    }

    private fun downscale(src: Bitmap): Bitmap {
        val longNow = maxOf(src.width, src.height)
        if (longNow <= MAX_SIDE) return src
        val scale = MAX_SIDE.toFloat() / longNow
        val scaled = Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== src) src.recycle()
        return scaled
    }

    /** Поворот по градусам из EXIF (0/90/180/270). */
    private fun applyRotation(src: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix()
        when (degrees) {
            90 -> matrix.postRotate(90f)
            180 -> matrix.postRotate(180f)
            270 -> matrix.postRotate(270f)
            else -> return src
        }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (rotated !== src) src.recycle()
        return rotated
    }

    fun file(context: Context, relative: String): File = File(dir(context), relative)

    fun delete(context: Context, relative: String) {
        file(context, relative).delete()
    }
}
