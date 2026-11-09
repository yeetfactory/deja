package top.yeetfactory.deja.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {
    @Test fun `plain values are left alone`() {
        assertEquals("Home,3,", Csv.row(listOf("Home", 3, null)))
    }

    @Test fun `separators and quotes are escaped`() {
        assertEquals("\"a,b\",\"say \"\"hi\"\"\"", Csv.row(listOf("a,b", "say \"hi\"")))
    }

    @Test fun `text that a spreadsheet would run is neutralised`() {
        assertEquals("'=HYPERLINK(1)", Csv.row(listOf("=HYPERLINK(1)")))
    }

    @Test fun `negative numbers stay numbers`() {
        assertEquals("-60", Csv.row(listOf(-60)))
    }
}
