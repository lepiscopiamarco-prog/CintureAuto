package it.marco.cintureauto

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Crea un vero file Excel (.xlsx) con il registro dei viaggi.
 *
 * Un .xlsx è un archivio ZIP con qualche file XML dentro: lo scriviamo a mano, così l'app non
 * ha bisogno di librerie esterne. Nessuna parte di Android: si prova anche sul computer.
 *
 * Il foglio "Viaggi" contiene una riga per viaggio, i totali (con formule), l'intestazione
 * bloccata in alto, i filtri e un collegamento alla mappa del punto di arrivo.
 */
object TripExport {

    const val SHEET_NAME = "Viaggi"
    const val MIME_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    private const val DAY_MS = 86_400_000L

    /** Numero di giorni tra il 30/12/1899 (origine delle date di Excel) e il 01/01/1970. */
    private const val EXCEL_EPOCH_OFFSET_DAYS = 25569L

    private val HEADERS = listOf(
        "Data",
        "Inizio",
        "Fine",
        "Durata (min)",
        "In movimento (min)",
        "Distanza (km)",
        "Velocità max (km/h)",
        "Velocità media (km/h)",
        "Superamenti del limite",
        "Arrivo"
    )

    private val COL_WIDTHS = listOf(12, 8, 8, 12, 14, 12, 13, 14, 14, 14)

    // Indici degli stili definiti in styles()
    private const val S_DEFAULT = 0
    private const val S_HEADER = 1
    private const val S_DATE = 2
    private const val S_TIME = 3
    private const val S_INT = 4
    private const val S_DEC1 = 5
    private const val S_LINK = 6
    private const val S_TOTAL_LABEL = 7
    private const val S_TOTAL_INT = 8
    private const val S_TOTAL_DEC1 = 9
    private const val S_INT_CENTER = 10

    /** Nome proposto per il file: viaggi_cinture_auto_2026-10-06.xlsx */
    fun suggestedFileName(nowMs: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        f.timeZone = tz
        return "viaggi_cinture_auto_${f.format(java.util.Date(nowMs))}.xlsx"
    }

    /** Il contenuto del file .xlsx, pronto da scrivere su disco. */
    fun toXlsx(trips: List<Trip>, tz: TimeZone = TimeZone.getDefault()): ByteArray {
        val sorted = trips.sortedBy { it.startMs }
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            fun add(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            add("[Content_Types].xml", contentTypes())
            add("_rels/.rels", rootRels())
            add("xl/workbook.xml", workbook(sorted.size))
            add("xl/_rels/workbook.xml.rels", workbookRels())
            add("xl/styles.xml", styles())
            add("xl/worksheets/sheet1.xml", sheet(sorted, tz))
        }
        return buffer.toByteArray()
    }

    // -----------------------------------------------------------------------------------------
    // Foglio "Viaggi"
    // -----------------------------------------------------------------------------------------

    private fun sheet(trips: List<Trip>, tz: TimeZone): String {
        val n = trips.size
        val lastDataRow = n + 1
        val sb = StringBuilder(4096)
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""").append('\n')
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        sb.append("""<sheetPr><pageSetUpPr fitToPage="1"/></sheetPr>""")
        sb.append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/>""")
        sb.append("""<selection pane="bottomLeft" activeCell="A2" sqref="A2"/></sheetView></sheetViews>""")
        sb.append("""<sheetFormatPr defaultRowHeight="15"/>""")
        sb.append("<cols>")
        COL_WIDTHS.forEachIndexed { i, w ->
            sb.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""")
        }
        sb.append("</cols>")
        sb.append("<sheetData>")

        // Intestazione
        sb.append("""<row r="1" ht="32" customHeight="1">""")
        HEADERS.forEachIndexed { i, h -> sb.append(textCell(ref(i, 1), h, S_HEADER)) }
        sb.append("</row>")

        // Viaggi
        var sumDur = 0.0
        var sumMov = 0.0
        var sumKm = 0.0
        var maxSpeed = 0.0
        var sumOver = 0L

        trips.forEachIndexed { index, t ->
            val r = index + 2
            val durMin = round(t.durationSec / 60.0, 1)
            val movMin = round(t.movingSec / 60.0, 1)
            val km = round(t.distanceKm, 2)
            val vmax = round(t.maxSpeedKmh, 1)
            val vavg = round(t.avgMovingKmh, 1)
            sumDur += durMin
            sumMov += movMin
            sumKm += km
            if (vmax > maxSpeed) maxSpeed = vmax
            if (t.speedMonitored) sumOver += t.overspeedCount

            sb.append("""<row r="$r">""")
            sb.append(numCell(ref(0, r), localDay(t.startMs, tz), S_DATE, 0))
            sb.append(numCell(ref(1, r), localTimeOfDay(t.startMs, tz), S_TIME, 12))
            sb.append(numCell(ref(2, r), localTimeOfDay(t.endMs, tz), S_TIME, 12))
            sb.append(numCell(ref(3, r), durMin, S_INT, 1))
            sb.append(numCell(ref(4, r), movMin, S_INT, 1))
            sb.append(numCell(ref(5, r), km, S_DEC1, 2))
            sb.append(numCell(ref(6, r), vmax, S_INT, 1))
            sb.append(numCell(ref(7, r), vavg, S_INT, 1))
            if (t.speedMonitored) {
                sb.append(numCell(ref(8, r), t.overspeedCount.toDouble(), S_INT_CENTER, 0))
            } else {
                sb.append(textCell(ref(8, r), "n.d.", S_INT_CENTER))
            }
            if (t.hasEnd) {
                val url = MapLinks.webPoint(t.endLat, t.endLon)
                sb.append(formulaStrCell(ref(9, r), "HYPERLINK(\"$url\",\"Apri mappa\")", "Apri mappa", S_LINK))
            } else {
                sb.append(textCell(ref(9, r), "n.d.", S_DEFAULT))
            }
            sb.append("</row>")
        }

        // Totali (dopo una riga vuota, così i filtri non li includono)
        var lastRow = lastDataRow
        if (n > 0) {
            val t = n + 3
            lastRow = t + 2
            val avg = if (sumMov > 0.0) round(sumKm / (sumMov / 60.0), 1) else 0.0
            val first = 2
            sb.append("""<row r="$t">""")
            sb.append(textCell(ref(0, t), "Totale", S_TOTAL_LABEL))
            sb.append(emptyStyledCell(ref(1, t), S_TOTAL_LABEL))
            sb.append(emptyStyledCell(ref(2, t), S_TOTAL_LABEL))
            sb.append(formulaNumCell(ref(3, t), "SUM(D$first:D$lastDataRow)", sumDur, S_TOTAL_INT, 1))
            sb.append(formulaNumCell(ref(4, t), "SUM(E$first:E$lastDataRow)", sumMov, S_TOTAL_INT, 1))
            sb.append(formulaNumCell(ref(5, t), "SUM(F$first:F$lastDataRow)", sumKm, S_TOTAL_DEC1, 2))
            sb.append(formulaNumCell(ref(6, t), "MAX(G$first:G$lastDataRow)", maxSpeed, S_TOTAL_INT, 1))
            sb.append(formulaNumCell(ref(7, t), "IF(E$t>0,F$t/(E$t/60),0)", avg, S_TOTAL_INT, 1))
            sb.append(formulaNumCell(ref(8, t), "SUM(I$first:I$lastDataRow)", sumOver.toDouble(), S_TOTAL_INT, 0))
            sb.append(emptyStyledCell(ref(9, t), S_TOTAL_LABEL))
            sb.append("</row>")

            sb.append("""<row r="${t + 2}">""")
            sb.append(
                textCell(
                    ref(0, t + 2),
                    "La velocità media è calcolata sul tempo in movimento. «n.d.» = controllo velocità spento durante il viaggio.",
                    S_DEFAULT
                )
            )
            sb.append("</row>")
        }

        sb.append("</sheetData>")
        if (n > 0) {
            sb.append("""<autoFilter ref="A1:J$lastDataRow"/>""")
        }
        sb.append("""<pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/>""")
        sb.append("""<pageSetup paperSize="9" orientation="landscape" fitToWidth="1" fitToHeight="0"/>""")
        sb.append("</worksheet>")
        return sb.toString()
    }

    // -----------------------------------------------------------------------------------------
    // Celle
    // -----------------------------------------------------------------------------------------

    private fun ref(col: Int, row: Int): String = "${'A' + col}$row"

    private fun textCell(ref: String, text: String, style: Int): String =
        """<c r="$ref" s="$style" t="inlineStr"><is><t>${esc(text)}</t></is></c>"""

    private fun emptyStyledCell(ref: String, style: Int): String = """<c r="$ref" s="$style"/>"""

    private fun numCell(ref: String, value: Double, style: Int, decimals: Int): String =
        """<c r="$ref" s="$style"><v>${plain(value, decimals)}</v></c>"""

    private fun formulaNumCell(ref: String, formula: String, cached: Double, style: Int, decimals: Int): String =
        """<c r="$ref" s="$style"><f>${esc(formula)}</f><v>${plain(cached, decimals)}</v></c>"""

    private fun formulaStrCell(ref: String, formula: String, cached: String, style: Int): String =
        """<c r="$ref" s="$style" t="str"><f>${esc(formula)}</f><v>${esc(cached)}</v></c>"""

    /** Numero scritto sempre con il punto, senza notazione scientifica e senza zeri inutili. */
    private fun plain(v: Double, decimals: Int): String {
        if (!v.isFinite()) return "0"
        var s = String.format(Locale.US, "%.${decimals}f", v)
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        if (s == "-0" || s.isEmpty()) s = "0"
        return s
    }

    private fun round(v: Double, decimals: Int): Double {
        var f = 1.0
        repeat(decimals) { f *= 10.0 }
        return Math.round(v * f) / f
    }

    /** Giorno (ora locale) come numero di serie di Excel. */
    private fun localDay(ms: Long, tz: TimeZone): Double {
        val local = ms + tz.getOffset(ms)
        return (Math.floorDiv(local, DAY_MS) + EXCEL_EPOCH_OFFSET_DAYS).toDouble()
    }

    /**
     * Ora del giorno (ora locale) come frazione di giorno: 0,5 = mezzogiorno.
     * Si aggiunge mezzo millisecondo per evitare che l'arrotondamento dei decimali faccia
     * comparire 13:41 al posto di 13:42.
     */
    private fun localTimeOfDay(ms: Long, tz: TimeZone): Double {
        val local = ms + tz.getOffset(ms)
        val wholeSeconds = Math.floorMod(local, DAY_MS) / 1000L
        return (wholeSeconds.toDouble() + 0.0005) / 86_400.0
    }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' -> { /* carattere non valido in XML */ }
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    // -----------------------------------------------------------------------------------------
    // Parti fisse del file .xlsx
    // -----------------------------------------------------------------------------------------

    private const val XML_HEAD = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""

    private fun contentTypes(): String = XML_HEAD + "\n" +
        """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
        """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
        """<Default Extension="xml" ContentType="application/xml"/>""" +
        """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
        """<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" +
        """<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
        "</Types>"

    private fun rootRels(): String = XML_HEAD + "\n" +
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
        """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
        "</Relationships>"

    private fun workbookRels(): String = XML_HEAD + "\n" +
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
        """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>""" +
        """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" +
        "</Relationships>"

    private fun workbook(tripCount: Int): String {
        val names = if (tripCount > 0) {
            """<definedNames><definedName name="_xlnm._FilterDatabase" localSheetId="0" hidden="1">$SHEET_NAME!${'$'}A${'$'}1:${'$'}J${'$'}${tripCount + 1}</definedName></definedNames>"""
        } else {
            ""
        }
        return XML_HEAD + "\n" +
            """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
            """<bookViews><workbookView xWindow="0" yWindow="0" windowWidth="24000" windowHeight="12000"/></bookViews>""" +
            """<sheets><sheet name="$SHEET_NAME" sheetId="1" r:id="rId1"/></sheets>""" +
            names +
            """<calcPr calcId="191029" fullCalcOnLoad="1"/>""" +
            "</workbook>"
    }

    private fun styles(): String = XML_HEAD + "\n" +
        """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
        """<numFmts count="3">""" +
        """<numFmt numFmtId="164" formatCode="0.0"/>""" +
        """<numFmt numFmtId="165" formatCode="dd/mm/yyyy"/>""" +
        """<numFmt numFmtId="166" formatCode="hh:mm"/>""" +
        """</numFmts>""" +
        """<fonts count="3">""" +
        """<font><sz val="11"/><name val="Calibri"/><family val="2"/></font>""" +
        """<font><b/><sz val="11"/><name val="Calibri"/><family val="2"/></font>""" +
        """<font><u/><sz val="11"/><color rgb="FF0563C1"/><name val="Calibri"/><family val="2"/></font>""" +
        """</fonts>""" +
        """<fills count="4">""" +
        """<fill><patternFill patternType="none"/></fill>""" +
        """<fill><patternFill patternType="gray125"/></fill>""" +
        """<fill><patternFill patternType="solid"><fgColor rgb="FFD9E1F2"/><bgColor indexed="64"/></patternFill></fill>""" +
        """<fill><patternFill patternType="solid"><fgColor rgb="FFF2F2F2"/><bgColor indexed="64"/></patternFill></fill>""" +
        """</fills>""" +
        """<borders count="2">""" +
        """<border><left/><right/><top/><bottom/><diagonal/></border>""" +
        """<border><left/><right/><top/><bottom style="thin"><color auto="1"/></bottom><diagonal/></border>""" +
        """</borders>""" +
        """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
        """<cellXfs count="11">""" +
        """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
        """<xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>""" +
        """<xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
        """<xf numFmtId="166" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
        """<xf numFmtId="1" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
        """<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
        """<xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/>""" +
        """<xf numFmtId="0" fontId="1" fillId="3" borderId="0" xfId="0" applyFont="1" applyFill="1"/>""" +
        """<xf numFmtId="1" fontId="1" fillId="3" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1"/>""" +
        """<xf numFmtId="164" fontId="1" fillId="3" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1"/>""" +
        """<xf numFmtId="1" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment horizontal="center"/></xf>""" +
        """</cellXfs>""" +
        """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
        """</styleSheet>"""
}
