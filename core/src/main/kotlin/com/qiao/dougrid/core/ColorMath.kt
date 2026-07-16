package com.qiao.dougrid.core

import kotlin.math.cbrt
import kotlin.math.pow

data class Oklab(val l: Double, val a: Double, val b: Double) {
    fun distanceSquared(other: Oklab): Double {
        val dl = l - other.l
        val da = a - other.a
        val db = b - other.b
        return dl * dl + da * da + db * db
    }
}

object ColorMath {
    fun toOklab(argb: Int): Oklab {
        val red = srgbToLinear((argb ushr 16 and 0xFF) / 255.0)
        val green = srgbToLinear((argb ushr 8 and 0xFF) / 255.0)
        val blue = srgbToLinear((argb and 0xFF) / 255.0)

        val l = 0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue
        val m = 0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue
        val s = 0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue
        val lRoot = cbrt(l)
        val mRoot = cbrt(m)
        val sRoot = cbrt(s)

        return Oklab(
            l = 0.2104542553 * lRoot + 0.7936177850 * mRoot - 0.0040720468 * sRoot,
            a = 1.9779984951 * lRoot - 2.4285922050 * mRoot + 0.4505937099 * sRoot,
            b = 0.0259040371 * lRoot + 0.7827717662 * mRoot - 0.8086757660 * sRoot,
        )
    }

    fun nearestColor(argb: Int, palette: List<PaletteColor>, allowed: IntArray? = null): Int {
        val target = toOklab(argb)
        val candidates = allowed ?: palette.indices.toList().toIntArray()
        var best = candidates.first()
        var bestDistance = Double.POSITIVE_INFINITY
        candidates.forEach { index ->
            val distance = target.distanceSquared(toOklab(palette[index].opaqueArgb))
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

    fun argb(red: Int, green: Int, blue: Int, alpha: Int = 255): Int =
        (alpha.coerceIn(0, 255) shl 24) or
            (red.coerceIn(0, 255) shl 16) or
            (green.coerceIn(0, 255) shl 8) or
            blue.coerceIn(0, 255)

    private fun srgbToLinear(value: Double): Double =
        if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
}
