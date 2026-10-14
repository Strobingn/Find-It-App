package com.example.data

import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.WeakHashMap
import java.util.zip.CRC32

/** Immutable scalar products keyed by the actual DEM, units, masks and analysis inputs. */
internal class TerrainDerivedFieldCache(private val directory: File?, private val diskLimit: Long = 128L shl 20) {
    private val identities = WeakHashMap<ElevationGrid, String>()
    private val memory = LinkedHashMap<String, FloatArray>(16, .75f, true)
    private var memoryBytes = 0L
    private val trimHook: () -> Unit = { synchronized(memory) { memory.clear(); memoryBytes = 0 } }
    init { AppMemoryBudget.registerTrimmable(trimHook) }

    fun field(grid: ElevationGrid, kind: String, vegetation: Float, radius: Float,
              shouldContinue: () -> Boolean, compute: () -> FloatArray): FloatArray {
        if (!shouldContinue()) throw java.util.concurrent.CancellationException("Analysis superseded")
        val identity = synchronized(identities) { identities.getOrPut(grid) { fingerprint(grid) } }
        val key = digest("derived-v1:$identity:$kind:${vegetation.toBits()}:${radius.toBits()}".toByteArray())
        synchronized(memory) { memory[key] }?.let { return it }
        val file = directory?.let { File(it, "$key.field") }
        val cached = file?.takeIf { it.isFile }?.let { read(it, grid.bareEarth.size) }
        if (cached != null) { file.setLastModified(System.currentTimeMillis()); retain(key, cached); return cached }
        val values = compute()
        require(values.size == grid.bareEarth.size)
        if (!shouldContinue()) throw java.util.concurrent.CancellationException("Analysis superseded")
        retain(key, values)
        if (file != null) runCatching { write(file, values) }
        return values
    }

    private fun retain(key: String, values: FloatArray) = synchronized(memory) {
        val bytes = values.size * 4L
        val cap = minOf(24L shl 20, AppMemoryBudget.reservedHeadroomBytes / 4)
        if (bytes > cap) return@synchronized
        memory.remove(key)?.let { memoryBytes -= it.size * 4L }
        memory[key] = values; memoryBytes += bytes
        while (memoryBytes > cap) {
            val first = memory.entries.iterator(); val entry = first.next()
            memoryBytes -= entry.value.size * 4L; first.remove()
        }
    }

    private fun read(file: File, count: Int): FloatArray? = runCatching {
        require(count in 1..4_202_500 && file.length() == 16L + count * 4L)
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            require(input.readInt() == 0x46494431 && input.readInt() == count)
            val expected = input.readLong(); val crc = CRC32()
            val out = FloatArray(count)
            for (i in out.indices) { val bits = input.readInt(); crcInt(crc, bits); out[i] = Float.fromBits(bits) }
            require(crc.value == expected); out
        }
    }.getOrElse { file.delete(); null }

    private fun write(file: File, values: FloatArray) {
        if (values.size * 4L + 16 > diskLimit) return
        file.parentFile!!.mkdirs()
        val temporary = File.createTempFile("field-", ".partial", file.parentFile)
        try {
            val crc = CRC32(); values.forEach { crcInt(crc, it.toBits()) }
            DataOutputStream(BufferedOutputStream(temporary.outputStream())).use { output ->
                output.writeInt(0x46494431); output.writeInt(values.size); output.writeLong(crc.value)
                values.forEach { output.writeInt(it.toBits()) }
            }
            if (!temporary.renameTo(file)) { file.delete(); check(temporary.renameTo(file)) }
            var total = 0L
            val files = file.parentFile!!.listFiles { f -> f.extension == "field" }.orEmpty().sortedByDescending { it.lastModified() }
            for (entry in files) { total += entry.length(); if (total > diskLimit) entry.delete() }
        } finally { temporary.delete() }
    }

    companion object {
        private fun crcInt(crc: CRC32, value: Int) { crc.update(value ushr 24 and 255); crc.update(value ushr 16 and 255); crc.update(value ushr 8 and 255); crc.update(value and 255) }
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun fingerprint(grid: ElevationGrid): String {
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(8192)
            fun integer(value: Int) { if (buffer.remaining() < 4) { hash.update(buffer.array(),0,buffer.position()); buffer.clear() }; buffer.putInt(value) }
            integer(grid.width); integer(grid.height); integer(grid.cellSizeXMeters.toBits()); integer(grid.cellSizeYMeters.toBits())
            for (i in grid.bareEarth.indices) {
                integer(grid.bareEarth[i].toBits()); integer(grid.canopySpikes[i].toBits())
                integer((if(grid.validData[i]) 1 else 0) or (if(grid.interpolatedData[i]) 2 else 0))
            }
            hash.update(buffer.array(),0,buffer.position())
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

internal object TerrainDerivedFields {
    @Volatile private var cache = TerrainDerivedFieldCache(null)
    fun configure(directory: File) { cache = TerrainDerivedFieldCache(directory) }
    fun field(grid: ElevationGrid, kind: String, vegetation: Float, radius: Float,
              shouldContinue: () -> Boolean, compute: () -> FloatArray) =
        cache.field(grid,kind,vegetation,radius,shouldContinue,compute)
}
