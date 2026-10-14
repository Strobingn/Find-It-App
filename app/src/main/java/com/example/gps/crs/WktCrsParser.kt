package com.example.gps.crs

/** Minimal OGC WKT1 (and ESRI-flavoured) PROJCS parser; falls back to an EPSG id for WKT2. */
object WktCrsParser {
    private class Node(val name: String, val args: MutableList<Any> = mutableListOf()) {
        fun child(vararg names: String): Node? =
            args.firstOrNull { it is Node && names.any { n -> it.name.equals(n, ignoreCase = true) } } as Node?

        fun children(name: String): List<Node> = args.filterIsInstance<Node>().filter { it.name.equals(name, ignoreCase = true) }
        fun str(i: Int): String? = args.getOrNull(i) as? String
        fun num(i: Int): Double? = (args.getOrNull(i) as? String)?.toDoubleOrNull()
        fun find(vararg names: String): Node? {
            if (names.any { name.equals(it, ignoreCase = true) }) return this
            for (a in args) if (a is Node) a.find(*names)?.let { return it }
            return null
        }
    }

    private fun parse(text: String): Node? {
        var i = 0
        fun skip() { while (i < text.length && (text[i].isWhitespace() || text[i] == ',')) i++ }
        fun node(): Node? {
            skip()
            val start = i
            while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
            if (start == i) return null
            val n = Node(text.substring(start, i))
            skip()
            if (i >= text.length || (text[i] != '[' && text[i] != '(')) return n
            val close = if (text[i] == '[') ']' else ')'
            i++
            while (true) {
                skip()
                if (i >= text.length) return n
                val c = text[i]
                when {
                    c == close -> { i++; return n }
                    c == '"' -> {
                        val end = text.indexOf('"', i + 1).let { if (it < 0) text.length else it }
                        n.args.add(text.substring(i + 1, end)); i = end + 1
                    }
                    c.isDigit() || c == '-' || c == '+' || c == '.' -> {
                        val s = i
                        while (i < text.length && (text[i].isDigit() || text[i] in "+-.eE")) i++
                        n.args.add(text.substring(s, i))
                    }
                    else -> node()?.let { n.args.add(it) } ?: run { i++ }
                }
            }
        }
        return runCatching { node() }.getOrNull()
    }

    /** Linear unit even when the projection itself is not implemented by the map transform. */
    fun horizontalUnitToMeters(wkt: String): Double? {
        val projected = parse(wkt.trim().trimEnd('\u0000'))?.find("PROJCS", "PROJCRS") ?: return null
        val unit = projected.children("UNIT").lastOrNull() ?: projected.children("LENGTHUNIT").lastOrNull()
            ?: projected.children("AXIS").firstNotNullOfOrNull { it.child("LENGTHUNIT", "UNIT") }
        return unit?.num(1)?.takeIf { it.isFinite() && it > 0.0 }
    }

    /** Read only the vertical CRS unit; horizontal feet do not imply vertical feet. */
    fun verticalUnitToMeters(wkt: String): Double? {
        val vertical = parse(wkt.trim().trimEnd('\u0000'))?.find("VERT_CS", "VERTCRS", "VERTICALCRS") ?: return null
        return vertical.find("LENGTHUNIT", "UNIT")?.num(1)?.takeIf { it.isFinite() && it > 0.0 }
    }

    fun parseProjected(wkt: String): ProjectedCrs? {
        val root = parse(wkt.trim().trimEnd('\u0000')) ?: return null
        val projcs = root.find("PROJCS") ?: return root.find("PROJCRS")?.let(::fromWkt2Id)
        val name = projcs.str(0) ?: "Projected CRS"
        val epsg = projcs.children("AUTHORITY").firstOrNull()?.takeIf { it.str(0) == "EPSG" }?.str(1)?.toIntOrNull()
        val geog = projcs.child("GEOGCS") ?: return epsg?.let(ProjectedCrs::fromEpsg)
        val datum = geog.child("DATUM")
        val datumName = datum?.str(0).orEmpty()
        val spheroid = datum?.child("SPHEROID", "ELLIPSOID")
        val ell = Ellipsoid(spheroid?.num(1) ?: 6_378_137.0, spheroid?.num(2) ?: 298.257222101)
        val upper = datumName.uppercase()
        val frame = when {
            "1983" in upper || "NAD83" in upper || "NAD_1983" in upper -> GeodeticFrame.NAD83_2011
            "WGS" in upper || "ITRF" in upper -> GeodeticFrame.ITRF
            else -> return epsg?.let(ProjectedCrs::fromEpsg)
        }
        val assumed = frame == GeodeticFrame.NAD83_2011 && "2011" !in upper
        val unit = projcs.children("UNIT").lastOrNull()?.num(1) ?: 1.0
        val params = projcs.children("PARAMETER").associate { (it.str(0) ?: "").lowercase() to (it.num(1) ?: 0.0) }
        fun p(vararg keys: String, default: Double = 0.0) = keys.firstNotNullOfOrNull { params[it] } ?: default
        val fe = p("false_easting") * unit
        val fn = p("false_northing") * unit
        val lat0 = p("latitude_of_origin", "latitude_of_center")
        val lon0 = p("central_meridian", "longitude_of_center", "longitude_of_origin")
        val method = projcs.child("PROJECTION")?.str(0)?.lowercase().orEmpty()
        val projection = when {
            "transverse_mercator" in method -> TransverseMercator(ell, lat0, lon0, p("scale_factor", default = 1.0), fe, fn)
            "lambert_conformal_conic" in method -> {
                val sp1 = params["standard_parallel_1"]
                val sp2 = params["standard_parallel_2"]
                if ("1sp" in method || sp1 == null || sp2 == null) {
                    LambertConformalConic(ell, lat0, lon0, null, null, p("scale_factor", default = 1.0), fe, fn)
                } else {
                    LambertConformalConic(ell, lat0, lon0, sp1, sp2, 1.0, fe, fn)
                }
            }
            else -> return epsg?.let(ProjectedCrs::fromEpsg)
        }
        return ProjectedCrs(name, frame, projection, unit, epsg, assumed)
    }

    private fun fromWkt2Id(projcrs: Node): ProjectedCrs? {
        val id = projcrs.children("ID").firstOrNull { it.str(0) == "EPSG" } ?: return null
        return id.num(1)?.toInt()?.let(ProjectedCrs::fromEpsg)
    }
}
