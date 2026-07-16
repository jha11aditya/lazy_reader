package com.lazyreader.pdf

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Hand-builds a minimal, real, N-page PDF (no library) for instrumented tests
 * that need a genuine PdfRenderer-parseable file. Byte offsets in the xref
 * table are computed as the file is assembled rather than hard-coded, so the
 * output stays valid regardless of page count.
 */
object PdfFixture {

    fun writeMinimalPdf(target: File, pageCount: Int): File {
        require(pageCount >= 1)
        val out = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>() // index 0 unused, matches object numbers 1..N

        fun write(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun beginObject(objectNumber: Int) {
            while (offsets.size <= objectNumber) offsets.add(0)
            offsets[objectNumber] = out.size()
        }

        write("%PDF-1.4\n")

        val pagesObj = 2
        val firstPageObj = 3
        val firstContentObj = firstPageObj + pageCount

        beginObject(1)
        write("1 0 obj\n<< /Type /Catalog /Pages $pagesObj 0 R >>\nendobj\n")

        val kids = (0 until pageCount).joinToString(" ") { "${firstPageObj + it} 0 R" }
        beginObject(pagesObj)
        write("$pagesObj 0 obj\n<< /Type /Pages /Kids [$kids] /Count $pageCount >>\nendobj\n")

        for (i in 0 until pageCount) {
            val pageObj = firstPageObj + i
            val contentObj = firstContentObj + i
            beginObject(pageObj)
            write(
                "$pageObj 0 obj\n<< /Type /Page /Parent $pagesObj 0 R " +
                    "/MediaBox [0 0 200 200] /Resources << /Font << /F1 ${firstContentObj + pageCount} 0 R >> >> " +
                    "/Contents $contentObj 0 R >>\nendobj\n",
            )
        }

        for (i in 0 until pageCount) {
            val contentObj = firstContentObj + i
            val stream = "BT /F1 24 Tf 20 100 Td (Page ${i + 1}) Tj ET"
            beginObject(contentObj)
            write("$contentObj 0 obj\n<< /Length ${stream.length} >>\nstream\n$stream\nendstream\nendobj\n")
        }

        val fontObj = firstContentObj + pageCount
        beginObject(fontObj)
        write("$fontObj 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")

        val totalObjects = fontObj
        val xrefOffset = out.size()
        write("xref\n0 ${totalObjects + 1}\n")
        write("0000000000 65535 f \n")
        for (objNum in 1..totalObjects) {
            write("%010d 00000 n \n".format(offsets[objNum]))
        }
        write("trailer\n<< /Size ${totalObjects + 1} /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF")

        target.writeBytes(out.toByteArray())
        return target
    }
}
