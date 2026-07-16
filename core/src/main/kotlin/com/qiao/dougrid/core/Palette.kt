package com.qiao.dougrid.core

data class PaletteColor(
    val code: String,
    val argb: Int,
    val group: String = "",
    val name: String = code,
) {
    val opaqueArgb: Int = argb or (0xFF shl 24)
}

data class BeadPalette(
    val id: String,
    val title: String,
    val colors: List<PaletteColor>,
    val version: String,
    val source: String,
) {
    init {
        require(colors.isNotEmpty()) { "Palette must contain colors" }
        require(colors.map { it.code }.distinct().size == colors.size) {
            "Palette color codes must be unique inside one palette"
        }
    }
}
