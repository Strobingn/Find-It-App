package com.example

import com.example.data.DemGenerator
import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun `custom grid parses rectangular finite values`() {
        val grid = DemGenerator.parseCustomGrid("1,2,3\n4,5,6")

        assertNotNull(grid)
        assertEquals(3, grid?.width)
        assertEquals(2, grid?.height)
        assertEquals(6f, grid?.getElevationAt(2, 1, 1f))
    }

    @Test
    fun `custom grid rejects malformed rows and values`() {
        assertNull(DemGenerator.parseCustomGrid("1,2\n3"))
        assertNull(DemGenerator.parseCustomGrid("1,wat\n3,4"))
        assertNull(DemGenerator.parseCustomGrid("1,NaN\n3,4"))
    }

    @Test
    fun `asc parser supports center-based headers`() {
        val asc = """
            ncols 2
            nrows 2
            xllcenter 0
            yllcenter 0
            cellsize 1
            NODATA_value -9999
            -5 -4
            -3 -2
        """.trimIndent()

        val grid = DemGenerator.parseAscDem(BufferedReader(StringReader(asc)))

        assertNotNull(grid)
        assertEquals(-5f, grid?.getElevationAt(0, 0, 1f))
        assertEquals(-2f, grid?.getElevationAt(99, 99, 1f))
    }
}
