package ru.danil.medtracker.docx

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Минимальный генератор .docx: прямая сборка OOXML (ZIP) без Apache POI/docx4j (п. 8.1).
 * Times New Roman, кириллица UTF-8, редактируемые абзацы и таблицы.
 */
class DocxBuilder {

    class Run(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
    )

    class Para(
        val runs: List<Run>,
        val align: String? = null, // center / right
        val spacingBefore: Int = 0,
        val spacingAfter: Int = 0, // twentieths of a point
        val fontSize: Int? = null, // half-points
    )

    class Table(val rows: List<List<List<Run>>>, val widths: List<Int>? = null, val header: Boolean = true)

    private val body = mutableListOf<String>()

    fun para(text: String, bold: Boolean = false, align: String? = null, size: Int? = null, after: Int = 80): DocxBuilder {
        body.add(p(runs(text, bold), align = align, after = after, size = size))
        return this
    }

    fun para(vararg runs: Run, align: String? = null, after: Int = 80, size: Int? = null): DocxBuilder {
        body.add(p(runs.toList(), align = align, after = after, size = size))
        return this
    }

    fun runs(text: String, bold: Boolean = false) = listOf(Run(text, bold))

    fun empty() { body.add(p(emptyList(), after = 40)) }

    fun table(t: Table): DocxBuilder {
        val sb = StringBuilder()
        sb.append("<w:tbl>")
        sb.append("""<w:tblPr><w:tblStyle w:val="TBL"/><w:tblW w:w="5000" w:type="pct"/><w:tblBorders>""")
        for (edge in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
            sb.append("""<w:$edge w:val="single" w:sz="4" w:space="0" w:color="000000"/>""")
        }
        sb.append("</w:tblBorders></w:tblPr>")
        // сетка колонок
        val cols = t.rows.maxOf { it.size }
        val widths = t.widths ?: List(cols) { 9000 / cols }
        sb.append("<w:tblGrid>")
        widths.forEach { sb.append("""<w:gridCol w:w="$it"/>""") }
        sb.append("</w:tblGrid>")
        t.rows.forEachIndexed { rowIdx, row ->
            sb.append("<w:tr>")
            row.forEachIndexed { colIdx, cell ->
                val headerCell = t.header && rowIdx == 0
                sb.append("""<w:tc><w:tcPr>""")
                if (headerCell) sb.append("""<w:shd w:val="clear" w:color="auto" w:fill="E8E8E8"/>""")
                sb.append("</w:tcPr>")
                val align = if (headerCell || colIdx > 0) "center" else null
                sb.append(p(cell, align = align, after = 20, size = 20))
                sb.append("</w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        body.add(sb.toString())
        // после таблицы Word требует абзац
        body.add(p(emptyList(), after = 40))
        return this
    }

    private fun p(runs: List<Run>, align: String? = null, before: Int = 0, after: Int = 80, size: Int? = null): String {
        val sb = StringBuilder()
        sb.append("""<w:p><w:pPr>""")
        if (align != null) sb.append("""<w:jc w:val="$align"/>""")
        sb.append("""<w:spacing w:before="$before" w:after="$after" w:line="240" w:lineRule="auto"/>""")
        sb.append("""</w:pPr>""")
        for (r in runs) {
            sb.append("""<w:r><w:rPr>""")
            if (r.bold) sb.append("""<w:b/>""")
            if (r.italic) sb.append("""<w:i/>""")
            sb.append("""<w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:cs="Times New Roman"/>""")
            (size ?: 24).let { sb.append("""<w:sz w:val="$it"/><w:szCs w:val="$it"/>""") }
            sb.append("""</w:rPr><w:t xml:space="preserve">${esc(r.text)}</w:t></w:r>""")
        }
        sb.append("</w:p>")
        return sb.toString()
    }

    private fun esc(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    fun build(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, StandardCharsets.UTF_8).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", CONTENT_TYPES)
            entry("_rels/.rels", RELS)
            entry("word/_rels/document.xml.rels", DOC_RELS)
            entry("word/styles.xml", STYLES)
            val doc = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>${body.joinToString("")}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="850" w:bottom="1134" w:left="1134" w:header="0" w:footer="0" w:gutter="0"/></w:sectPr></w:body></w:document>"""
            entry("word/document.xml", doc)
        }
        return out.toByteArray()
    }

    companion object {
        private val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>"""

        private val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""

        private val DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""

        private val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:cs="Times New Roman" w:eastAsia="Times New Roman"/><w:sz w:val="24"/><w:szCs w:val="24"/><w:lang w:val="ru-RU"/></w:rPr></w:rPrDefault><w:pPrDefault/></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style><w:style w:type="table" w:styleId="TBL"><w:name w:val="Table Grid"/><w:tblPr><w:tblBorders><w:top w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:left w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:bottom w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:right w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:insideH w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:insideV w:val="single" w:sz="4" w:space="0" w:color="auto"/></w:tblBorders></w:tblPr></w:style></w:styles>"""
    }
}
