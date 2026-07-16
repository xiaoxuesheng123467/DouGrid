package com.qiao.dougrid.data

import android.content.Context
import android.content.res.AssetManager
import com.qiao.dougrid.core.BeadPalette
import com.qiao.dougrid.core.PaletteColor
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class PaletteSummary(
    val id: String,
    val title: String,
    val description: String,
    val colorCount: Int,
    val version: String,
    val source: String,
)

class PaletteCatalog private constructor(
    private val assets: AssetManager,
) {
    constructor(context: Context) : this(context.applicationContext.assets)

    private val loadedById: Map<String, Lazy<LoadedPalette?>> = PALETTE_ASSETS.associate { asset ->
        asset.id to lazy(LazyThreadSafetyMode.SYNCHRONIZED) { loadPalette(asset) }
    }

    val summaries: List<PaletteSummary> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PALETTE_ASSETS.mapNotNull { asset -> loadedById.getValue(asset.id).value?.summary }
    }

    fun get(id: String): BeadPalette? =
        loadedById[id]?.value?.palette

    fun default(): BeadPalette = checkNotNull(get(DEFAULT_PALETTE_ID)) {
        "The packaged default palette '$DEFAULT_PALETTE_ID' could not be loaded"
    }

    private fun loadPalette(asset: PaletteAsset): LoadedPalette? = try {
        val root = assets.open(asset.assetPath).bufferedReader(Charsets.UTF_8).use { reader ->
            JSONObject(reader.readText())
        }
        if (root.stringOrNull("schema") != PALETTE_SCHEMA) return null
        if (root.stringOrNull("id") != asset.upstreamId) return null

        val colors = parseColors(root.optJSONArray("colors"))
        if (colors.isEmpty()) return null

        val generatedAt = root.stringOrNull("generated_at") ?: "undated"
        val version = "$generatedAt@${UPSTREAM_REVISION.take(12)}"
        val source = buildSource(root, asset)
        val palette = BeadPalette(
            id = asset.id,
            title = root.stringOrNull("title") ?: asset.fallbackTitle,
            colors = colors,
            version = version,
            source = source,
        )
        LoadedPalette(
            palette = palette,
            summary = PaletteSummary(
                id = palette.id,
                title = palette.title,
                description = root.stringOrNull("description").orEmpty(),
                colorCount = colors.size,
                version = version,
                source = source,
            ),
        )
    } catch (_: Exception) {
        null
    }

    private fun parseColors(array: JSONArray?): List<PaletteColor> {
        if (array == null) return emptyList()

        val colors = ArrayList<PaletteColor>(array.length())
        val seenCodes = HashSet<String>(array.length())
        for (index in 0 until array.length()) {
            val color = array.optJSONObject(index)?.toPaletteColor() ?: continue
            val identity = color.code.uppercase(Locale.ROOT)
            if (seenCodes.add(identity)) colors += color
        }
        return colors
    }

    private fun JSONObject.toPaletteColor(): PaletteColor? {
        val code = stringOrNull("code")?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val argb = parseHex(stringOrNull("hex")) ?: parseRgb(optJSONArray("rgb")) ?: return null
        val group = stringOrNull("group")?.trim().orEmpty()
        val name = stringOrNull("name")?.trim()?.takeIf(String::isNotEmpty) ?: code
        return PaletteColor(code = code, argb = argb, group = group, name = name)
    }

    private fun parseHex(raw: String?): Int? {
        val hex = raw?.trim()?.removePrefix("#") ?: return null
        if (hex.length != 6 && hex.length != 8) return null
        if (!hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null

        val rgb = hex.substring(0, 6).toLongOrNull(16)?.toInt() ?: return null
        val alpha = if (hex.length == 8) hex.substring(6, 8).toIntOrNull(16) ?: return null else 255
        return (alpha shl 24) or rgb
    }

    private fun parseRgb(array: JSONArray?): Int? {
        if (array == null || array.length() !in 3..4) return null
        val red = array.byteComponent(0) ?: return null
        val green = array.byteComponent(1) ?: return null
        val blue = array.byteComponent(2) ?: return null
        val alpha = if (array.length() == 4) array.byteComponent(3) ?: return null else 255
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun JSONArray.byteComponent(index: Int): Int? {
        val number = opt(index) as? Number ?: return null
        val value = number.toInt()
        if (value !in 0..255 || value.toDouble() != number.toDouble()) return null
        return value
    }

    private fun buildSource(root: JSONObject, asset: PaletteAsset): String {
        val snapshot = "$REPOSITORY_URL/tree/$UPSTREAM_REVISION/${asset.upstreamDirectory}"
        val original = root.optJSONArray("sources")
            ?.optJSONObject(0)
            ?.stringOrNull("url")
            ?.takeIf(String::isNotBlank)
        return if (original == null) snapshot else "$snapshot | original: $original"
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        opt(key)?.takeUnless { it == JSONObject.NULL }?.let { it as? String }

    private data class LoadedPalette(
        val palette: BeadPalette,
        val summary: PaletteSummary,
    )

    private data class PaletteAsset(
        val id: String,
        val upstreamId: String,
        val assetPath: String,
        val upstreamDirectory: String,
        val fallbackTitle: String,
    )

    companion object {
        const val DEFAULT_PALETTE_ID = "mard-221"

        private const val PALETTE_SCHEMA = "pindou-color-palette"
        private const val REPOSITORY_URL = "https://github.com/HansBug/pindou-color-data"
        private const val UPSTREAM_REVISION = "178dafbc9e77d3de556550dbd058270200129186"

        private val PALETTE_ASSETS = listOf(
            PaletteAsset(
                id = DEFAULT_PALETTE_ID,
                upstreamId = "mard-221-alfonse-doudou",
                assetPath = "palettes/mard-221.json",
                upstreamDirectory = "mard-221-alfonse-doudou",
                fallbackTitle = "MARD 221色",
            ),
            PaletteAsset(
                id = "mard-291",
                upstreamId = "mard-291-github",
                assetPath = "palettes/mard-291.json",
                upstreamDirectory = "mard-291-github",
                fallbackTitle = "MARD 291色",
            ),
            PaletteAsset(
                id = "coco-291",
                upstreamId = "coco-291",
                assetPath = "palettes/coco-291.json",
                upstreamDirectory = "coco-291",
                fallbackTitle = "COCO 291色",
            ),
            PaletteAsset(
                id = "artkal-c-197",
                upstreamId = "artkal-c-197-official",
                assetPath = "palettes/artkal-c-197.json",
                upstreamDirectory = "artkal-c-197-official",
                fallbackTitle = "Artkal C 197色",
            ),
        )
    }
}
