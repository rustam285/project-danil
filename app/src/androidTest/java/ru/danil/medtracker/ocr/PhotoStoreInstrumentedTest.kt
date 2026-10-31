package ru.danil.medtracker.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Инструментальные тесты импорта фото (PhotoStore): JPEG, HEIC (Xiaomi-камеры
 * умеют сохранять HEIF), слишком большие снимки, битые байты.
 */
@RunWith(AndroidJUnit4::class)
class PhotoStoreInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun tempSource(name: String, build: (File) -> Unit): Uri {
        val f = File(context.cacheDir, name)
        f.parentFile!!.mkdirs()
        build(f)
        return Uri.fromFile(f)
    }

    @Test
    fun importsRegularJpegAndScalesDown() {
        val uri = tempSource("probe_big.jpg") { f ->
            val bmp = Bitmap.createBitmap(3200, 1600, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(Color.WHITE)
            java.io.FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()
        }
        val rel = PhotoStore.saveCompressed(context, uri, "test_import_jpg")
        val out = PhotoStore.file(context, rel)
        assertTrue(out.exists() && out.length() > 0)
        val decoded = BitmapFactory.decodeFile(out.absolutePath)
        assertEquals(1600, maxOf(decoded!!.width, decoded.height))
    }

    /**
     * HEIC — Xiaomi/Google-камеры сохраняют снимки в HEIF: BitmapFactory их часто
     * не декодирует, ImageDecoder обязан. Файл передаётся снаружи (личные фото
     * в репозиторий не идут):
     * adb push test.heic /sdcard/Android/data/<pkg>/files/heic_probe.heic
     */
    @Test
    fun importsHeicIfPushed() {
        val probe = File(context.getExternalFilesDir(null), "heic_probe.heic")
        assumeTrue("heic-файл не передан", probe.exists() && probe.length() > 0)
        val rel = PhotoStore.saveCompressed(context, Uri.fromFile(probe), "test_import_heic")
        val out = PhotoStore.file(context, rel)
        assertTrue(out.exists() && out.length() > 0)
        assertTrue("результат должен быть JPEG", BitmapFactory.decodeFile(out.absolutePath) != null)
    }

    @Test
    fun brokenBytesProduceClearError() {
        val uri = tempSource("probe_bad.jpg") { it.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)) }
        val e = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            PhotoStore.saveCompressed(context, uri, "test_import_bad")
        }
        assertTrue(
            "ошибка должна объяснять причину: ${e.message}",
            e.message!!.contains("декодировать"),
        )
    }
}
