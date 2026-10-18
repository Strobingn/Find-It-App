package com.example.data.historicmap

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.system.measureTimeMillis

@RunWith(RobolectricTestRunner::class)
class HistoricMapCatalogClientBenchmarkTest {
    @Test
    fun benchmarkConcurrentDiscover() {
        val mockOpener: (String) -> String? = { url ->
            val items = JSONArray()
            for (i in 0 until 200) {
                items.put(JSONObject().put("sourceId", "id$i").put("downloadURL", "url").put("title", "t").put("publicationDate", "1900").put("minY", 43.0).put("maxY", 43.2).put("minX", -75.5).put("maxX", -75.2))
            }
            JSONObject().put("items", items).toString()
        }

        val client = HistoricMapCatalogClient(pauseMillis = 50, opener = mockOpener)

        val time = measureTimeMillis {
            runBlocking(Dispatchers.Default) {
                val jobs = List(300) {
                    async {
                        client.usgsHistorical(listOf(43.0, 43.2, -75.5, -75.2))
                    }
                }
                jobs.forEach { it.await() }
            }
        }
        println("Concurrent Benchmark time for 300 tasks: $time ms")
        // Assert that time is reasonable (e.g. less than 20 seconds, well under the ~74s block time)
        assert(time < 20000) { "Benchmark took too long ($time ms), suspending may not be working correctly." }
    }
}
