package com.qiao.dougrid.data

import com.qiao.dougrid.core.BeadPalette
import com.qiao.dougrid.core.ColorMath
import com.qiao.dougrid.core.ConversionMode
import com.qiao.dougrid.core.EMPTY_CELL
import com.qiao.dougrid.core.PatternGrid
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

data class BeadTemplate(
    val id: String,
    val title: String,
    val category: String,
    val grid: PatternGrid,
    val paletteId: String,
)

object TemplateCatalog {
    fun builtIns(palette: BeadPalette): List<BeadTemplate> = listOf(
        // 花植
        tulipCoaster(palette),
        sunflowerMorning(palette),
        botanicalBookmark(palette),
        // 食物
        lemonFizz(palette),
        berryCake(palette),
        ramenBowl(palette),
        // 动物
        littleFox(palette),
        oceanWhale(palette),
        gardenGiraffe(palette),
        // 风景
        sunsetCabin(palette),
        coastalLighthouse(palette),
        lakesideCamper(palette),
        // 节日
        luckyLantern(palette),
        winterSnowGlobe(palette),
        birthdayParty(palette),
        // 几何
        quiltStar(palette),
        rhythmWaves(palette),
        stainedGlassBloom(palette),
        // 实用
        pencilBookmark(palette),
        mountainKeyTag(palette),
        sunMoonCoasterPair(palette),
    )

    fun instantiate(template: BeadTemplate): BeadProject = BeadProject(
        title = template.title,
        paletteId = template.paletteId,
        grid = template.grid.deepCopy(),
        sourceMode = ConversionMode.SPRITE,
        status = ProjectStatus.READY,
    )

    private fun tulipCoaster(palette: BeadPalette): BeadTemplate {
        val coral = color(palette, 0xFFF06467.toInt())
        val blush = color(palette, 0xFFFFA9A5.toInt())
        val leaf = color(palette, 0xFF2A8C72.toInt())
        val lightLeaf = color(palette, 0xFF78C6A3.toInt())
        val cream = color(palette, 0xFFFFF4D6.toInt())
        val gold = color(palette, 0xFFF4C84A.toInt())
        return template("tulip", "郁金香杯垫", "花植", 29, 29, palette) {
            fillCircle(14, 14, 13, gold)
            fillCircle(14, 14, 11, cream)
            line(14, 12, 14, 24, leaf, 2)
            fillEllipse(10, 20, 5, 2, lightLeaf)
            fillEllipse(18, 21, 5, 2, leaf)
            fillEllipse(10, 11, 4, 5, coral)
            fillEllipse(18, 11, 4, 5, coral)
            fillEllipse(14, 9, 4, 6, blush)
            fillTriangle(8, 10, 14, 17, 20, 10, coral)
            setSafe(12, 7, blush)
            setSafe(16, 7, blush)
        }
    }

    private fun sunflowerMorning(palette: BeadPalette): BeadTemplate {
        val sky = color(palette, 0xFFCDEBE5.toInt())
        val cream = color(palette, 0xFFFFF4D6.toInt())
        val yellow = color(palette, 0xFFF4C84A.toInt())
        val amber = color(palette, 0xFFE99B32.toInt())
        val brown = color(palette, 0xFF704536.toInt())
        val green = color(palette, 0xFF377D58.toInt())
        val mint = color(palette, 0xFF80B77D.toInt())
        return template("sunflower", "向日葵早晨", "花植", 29, 29, palette) {
            fillCircle(14, 14, 13, cream)
            circleRing(14, 14, 13, 11, sky)
            line(14, 14, 14, 26, green, 2)
            fillEllipse(9, 21, 5, 2, mint)
            fillEllipse(19, 23, 5, 2, green)
            listOf(
                14 to 5, 14 to 17, 5 to 11, 23 to 11,
                8 to 6, 20 to 6, 8 to 16, 20 to 16,
            ).forEachIndexed { index, (x, y) ->
                fillEllipse(x, y, if (index < 2) 2 else 3, if (index < 2) 4 else 2, yellow)
            }
            fillCircle(14, 11, 6, amber)
            fillCircle(14, 11, 4, brown)
            for (y in 8..14 step 2) for (x in 11..17 step 2) {
                if ((x - 14) * (x - 14) + (y - 11) * (y - 11) <= 13) setSafe(x, y, amber)
            }
        }
    }

    private fun botanicalBookmark(palette: BeadPalette): BeadTemplate {
        val cream = color(palette, 0xFFFFF6DE.toInt())
        val gold = color(palette, 0xFFE4AF45.toInt())
        val forest = color(palette, 0xFF2D7358.toInt())
        val mint = color(palette, 0xFF87B989.toInt())
        val coral = color(palette, 0xFFE9786A.toInt())
        val blush = color(palette, 0xFFF6B0A8.toInt())
        return template("botanical_bookmark", "花叶书签", "花植", 29, 58, palette) {
            fillRoundedRect(3, 1, 25, 52, 3, gold)
            fillRoundedRect(5, 3, 23, 50, 2, cream)
            fillCircle(14, 7, 2, gold)
            setSafe(14, 7, EMPTY_CELL)
            line(13, 12, 16, 45, forest, 1)
            line(13, 20, 8, 17, forest)
            line(14, 27, 20, 23, forest)
            line(15, 35, 9, 32, forest)
            line(16, 42, 21, 38, forest)
            fillEllipse(8, 16, 4, 2, mint)
            fillEllipse(20, 22, 4, 2, forest)
            fillEllipse(9, 31, 4, 2, forest)
            fillEllipse(21, 37, 4, 2, mint)
            fillCircle(12, 14, 3, coral)
            fillCircle(17, 45, 3, blush)
            setSafe(12, 14, gold)
            setSafe(17, 45, gold)
            line(14, 52, 14, 56, gold, 2)
            fillTriangle(11, 57, 17, 57, 14, 53, coral)
        }
    }

    private fun lemonFizz(palette: BeadPalette): BeadTemplate {
        val yellow = color(palette, 0xFFF7D33D.toInt())
        val pale = color(palette, 0xFFFFF1A8.toInt())
        val white = color(palette, 0xFFFFFAEA.toInt())
        val green = color(palette, 0xFF388E6C.toInt())
        val teal = color(palette, 0xFF57B7B1.toInt())
        return template("lemon", "柠檬气泡", "食物", 29, 29, palette) {
            fillCircle(14, 14, 13, teal)
            fillCircle(14, 14, 11, white)
            fillCircle(14, 14, 10, yellow)
            fillCircle(14, 14, 8, pale)
            for (end in listOf(14 to 5, 14 to 23, 5 to 14, 23 to 14, 8 to 8, 20 to 8, 8 to 20, 20 to 20)) {
                line(14, 14, end.first, end.second, yellow)
            }
            fillCircle(14, 14, 2, white)
            fillEllipse(23, 5, 4, 2, green)
            setSafe(25, 7, teal)
            setSafe(4, 4, white)
            setSafe(25, 19, white)
        }
    }

    private fun berryCake(palette: BeadPalette): BeadTemplate {
        val background = color(palette, 0xFFDCEBE7.toInt())
        val plate = color(palette, 0xFF4A9B99.toInt())
        val sponge = color(palette, 0xFFE9B95E.toInt())
        val cream = color(palette, 0xFFFFF4DE.toInt())
        val berry = color(palette, 0xFFD94D5D.toInt())
        val leaf = color(palette, 0xFF438062.toInt())
        val cocoa = color(palette, 0xFF81513F.toInt())
        return template("berry_cake", "草莓蛋糕", "食物", 29, 29, palette) {
            fillCircle(14, 14, 13, background)
            fillEllipse(14, 23, 11, 3, plate)
            fillEllipse(14, 22, 9, 2, cream)
            fillTriangle(5, 20, 23, 20, 20, 7, sponge)
            fillTriangle(7, 17, 22, 17, 20, 9, cream)
            line(7, 18, 22, 18, cocoa)
            fillCircle(19, 7, 3, berry)
            fillCircle(13, 11, 2, berry)
            fillEllipse(21, 5, 3, 1, leaf)
            setSafe(9, 15, cream)
            setSafe(12, 19, berry)
            setSafe(17, 19, berry)
        }
    }

    private fun ramenBowl(palette: BeadPalette): BeadTemplate {
        val cream = color(palette, 0xFFFFF1D0.toInt())
        val navy = color(palette, 0xFF263F4B.toInt())
        val bowl = color(palette, 0xFFE85D5D.toInt())
        val broth = color(palette, 0xFF9B623E.toInt())
        val noodle = color(palette, 0xFFF4CE54.toInt())
        val egg = color(palette, 0xFFFFF7E8.toInt())
        val yolk = color(palette, 0xFFF1A52D.toInt())
        val green = color(palette, 0xFF4A8762.toInt())
        return template("ramen", "热腾拉面", "食物", 29, 29, palette) {
            fillCircle(14, 14, 13, cream)
            line(18, 2, 10, 14, navy)
            line(22, 2, 14, 14, navy)
            line(19, 2, 11, 14, bowl)
            fillEllipse(14, 13, 10, 4, broth)
            for (x in 7..20 step 3) line(x, 11, x + 2, 16, noodle)
            fillEllipse(10, 12, 4, 3, egg)
            fillCircle(10, 12, 1, yolk)
            fillCircle(19, 12, 2, green)
            for (y in 14..22) {
                val inset = (y - 14) / 3
                line(5 + inset, y, 23 - inset, y, bowl)
            }
            line(8, 18, 20, 18, cream)
            line(10, 23, 18, 23, navy)
            line(8, 7, 10, 4, navy)
            line(13, 7, 14, 3, navy)
        }
    }

    private fun littleFox(palette: BeadPalette): BeadTemplate {
        val sage = color(palette, 0xFFC8DDD2.toInt())
        val rust = color(palette, 0xFFE16F45.toInt())
        val amber = color(palette, 0xFFF19A45.toInt())
        val cream = color(palette, 0xFFFFEED8.toInt())
        val dark = color(palette, 0xFF3B3435.toInt())
        val blush = color(palette, 0xFFF29A91.toInt())
        return template("little_fox", "林间小狐", "动物", 29, 29, palette) {
            fillCircle(14, 14, 13, sage)
            fillTriangle(4, 4, 12, 8, 7, 17, rust)
            fillTriangle(25, 4, 17, 8, 22, 17, rust)
            fillTriangle(7, 7, 11, 9, 8, 13, cream)
            fillTriangle(22, 7, 18, 9, 21, 13, cream)
            fillEllipse(14, 15, 10, 9, amber)
            fillTriangle(5, 13, 14, 25, 14, 12, rust)
            fillTriangle(24, 13, 14, 25, 14, 12, rust)
            fillEllipse(10, 18, 5, 4, cream)
            fillEllipse(18, 18, 5, 4, cream)
            fillCircle(10, 14, 1, dark)
            fillCircle(18, 14, 1, dark)
            fillCircle(14, 20, 2, dark)
            setSafe(8, 18, blush)
            setSafe(20, 18, blush)
        }
    }

    private fun oceanWhale(palette: BeadPalette): BeadTemplate {
        val sky = color(palette, 0xFFBFE4E6.toInt())
        val sea = color(palette, 0xFF3C8F9D.toInt())
        val wave = color(palette, 0xFF74C2C2.toInt())
        val whale = color(palette, 0xFF355F78.toInt())
        val belly = color(palette, 0xFFB8D6D6.toInt())
        val white = color(palette, 0xFFFFFBEC.toInt())
        val dark = color(palette, 0xFF203A48.toInt())
        return template("ocean_whale", "蓝鲸跃浪", "动物", 58, 29, palette) {
            fillRect(0, 0, 57, 18, sky)
            fillRect(0, 19, 57, 28, sea)
            for (x in 0 until width step 8) {
                line(x, 20, x + 3, 18, white)
                line(x + 3, 18, x + 7, 20, wave)
            }
            fillEllipse(28, 13, 14, 7, whale)
            fillTriangle(14, 13, 6, 7, 10, 16, whale)
            fillTriangle(14, 13, 6, 19, 11, 12, whale)
            fillEllipse(30, 16, 10, 3, belly)
            fillTriangle(26, 17, 34, 22, 37, 16, whale)
            fillCircle(37, 11, 1, dark)
            setSafe(38, 10, white)
            line(39, 6, 39, 2, sea)
            line(39, 3, 35, 0, sea)
            line(39, 3, 43, 0, sea)
            fillCircle(48, 7, 1, white)
            fillCircle(52, 4, 2, white)
        }
    }

    private fun gardenGiraffe(palette: BeadPalette): BeadTemplate {
        val background = color(palette, 0xFFD8EEE3.toInt())
        val border = color(palette, 0xFF4A8C6F.toInt())
        val gold = color(palette, 0xFFF0B84D.toInt())
        val ochre = color(palette, 0xFFB96938.toInt())
        val cream = color(palette, 0xFFFFE8B7.toInt())
        val dark = color(palette, 0xFF443A36.toInt())
        val leaf = color(palette, 0xFF2F7654.toInt())
        return template("garden_giraffe", "花园长颈鹿", "动物", 29, 58, palette) {
            fillRoundedRect(2, 1, 26, 56, 4, border)
            fillRoundedRect(4, 3, 24, 54, 3, background)
            fillRect(10, 18, 18, 53, gold)
            fillEllipse(14, 15, 9, 7, gold)
            fillEllipse(14, 18, 7, 4, cream)
            fillEllipse(5, 12, 4, 2, gold)
            fillEllipse(23, 12, 4, 2, gold)
            line(10, 9, 8, 5, ochre, 2)
            line(18, 9, 20, 5, ochre, 2)
            fillCircle(8, 4, 2, gold)
            fillCircle(20, 4, 2, gold)
            fillCircle(11, 14, 1, dark)
            fillCircle(18, 14, 1, dark)
            setSafe(12, 19, dark)
            setSafe(16, 19, dark)
            listOf(13 to 24, 16 to 30, 12 to 36, 16 to 43, 12 to 49).forEach { (x, y) ->
                fillCircle(x, y, 2, ochre)
            }
            line(6, 42, 9, 37, leaf)
            fillEllipse(6, 40, 4, 2, leaf)
            line(20, 50, 23, 45, leaf)
            fillEllipse(22, 45, 4, 2, leaf)
        }
    }

    private fun sunsetCabin(palette: BeadPalette): BeadTemplate {
        val sky = color(palette, 0xFF8CC9CF.toInt())
        val dusk = color(palette, 0xFFF4A38B.toInt())
        val sun = color(palette, 0xFFF7CE55.toInt())
        val mountain = color(palette, 0xFF436D68.toInt())
        val dark = color(palette, 0xFF243B43.toInt())
        val cabin = color(palette, 0xFFB85C4E.toInt())
        val light = color(palette, 0xFFFFE398.toInt())
        return template("sunset", "山野日落", "风景", 58, 29, palette) {
            fillRect(0, 0, 57, 11, sky)
            fillRect(0, 12, 57, 28, dusk)
            fillCircle(44, 8, 5, sun)
            for (x in 0 until width) {
                val ridge = (14 + abs(x - 12) / 3).coerceAtMost(23)
                line(x, ridge, x, 28, mountain)
                val frontRidge = (19 + abs(x - 42) / 5).coerceAtMost(26)
                line(x, frontRidge, x, 28, dark)
            }
            fillRect(22, 18, 34, 25, cabin)
            fillTriangle(19, 19, 28, 13, 37, 19, dark)
            fillRect(26, 21, 29, 25, light)
            fillRect(31, 20, 33, 23, dark)
            line(4, 25, 4, 19, dark, 2)
            fillTriangle(0, 22, 4, 15, 8, 22, dark)
        }
    }

    private fun coastalLighthouse(palette: BeadPalette): BeadTemplate {
        val sky = color(palette, 0xFFB8DCE2.toInt())
        val sea = color(palette, 0xFF4D91A2.toInt())
        val white = color(palette, 0xFFFFF4DF.toInt())
        val red = color(palette, 0xFFD95852.toInt())
        val dark = color(palette, 0xFF304852.toInt())
        val light = color(palette, 0xFFFFD65A.toInt())
        val rock = color(palette, 0xFF61736C.toInt())
        return template("lighthouse", "海岸灯塔", "风景", 58, 29, palette) {
            fillRect(0, 0, 57, 18, sky)
            fillRect(0, 19, 57, 28, sea)
            fillCircle(8, 7, 4, light)
            fillEllipse(45, 6, 7, 2, white)
            fillEllipse(51, 9, 5, 2, white)
            fillTriangle(26, 9, 7, 14, 26, 15, light)
            fillTriangle(32, 9, 51, 14, 32, 15, light)
            fillTriangle(18, 28, 30, 20, 43, 28, rock)
            for (y in 10..25) {
                val halfWidth = 3 + (y - 10) / 6
                line(29 - halfWidth, y, 29 + halfWidth, y, if ((y / 4) % 2 == 0) white else red)
            }
            fillRect(25, 7, 33, 10, dark)
            fillTriangle(23, 7, 35, 7, 29, 3, red)
            fillRect(28, 8, 30, 10, light)
            fillRect(27, 21, 30, 25, dark)
            for (x in 0 until width step 8) line(x, 23, x + 4, 22, white)
        }
    }

    private fun lakesideCamper(palette: BeadPalette): BeadTemplate {
        val sky = color(palette, 0xFFAED8D3.toInt())
        val mountain = color(palette, 0xFF66867B.toInt())
        val grass = color(palette, 0xFF48715B.toInt())
        val camper = color(palette, 0xFFFFE8C4.toInt())
        val coral = color(palette, 0xFFE16D5B.toInt())
        val blue = color(palette, 0xFF67A8AF.toInt())
        val dark = color(palette, 0xFF304342.toInt())
        val fire = color(palette, 0xFFF5B53B.toInt())
        return template("lakeside_camper", "湖畔露营", "风景", 58, 29, palette) {
            fillRect(0, 0, 57, 18, sky)
            fillTriangle(0, 18, 15, 5, 31, 18, mountain)
            fillTriangle(19, 18, 35, 8, 50, 18, blue)
            fillRect(0, 19, 57, 28, grass)
            fillCircle(50, 6, 4, fire)
            fillRoundedRect(13, 13, 42, 24, 3, camper)
            fillRect(13, 13, 42, 16, coral)
            fillRect(17, 17, 26, 21, blue)
            strokeRect(17, 17, 26, 21, dark)
            fillRect(31, 17, 37, 24, coral)
            setSafe(36, 21, fire)
            fillCircle(20, 24, 4, dark)
            fillCircle(20, 24, 2, camper)
            fillCircle(36, 24, 4, dark)
            fillCircle(36, 24, 2, camper)
            fillTriangle(46, 25, 50, 17, 54, 25, dark)
            line(44, 27, 49, 22, fire, 2)
            line(55, 27, 50, 22, coral, 2)
        }
    }

    private fun luckyLantern(palette: BeadPalette): BeadTemplate {
        val night = color(palette, 0xFF29394D.toInt())
        val red = color(palette, 0xFFD84B4B.toInt())
        val coral = color(palette, 0xFFF06B58.toInt())
        val gold = color(palette, 0xFFF2C14D.toInt())
        val cream = color(palette, 0xFFFFE6A3.toInt())
        return template("lucky_lantern", "好运灯笼", "节日", 29, 29, palette) {
            fillCircle(14, 14, 13, night)
            line(14, 1, 14, 5, gold)
            fillRect(10, 4, 18, 6, gold)
            fillRoundedRect(6, 6, 22, 20, 5, red)
            fillEllipse(14, 13, 5, 7, coral)
            line(9, 7, 9, 19, gold)
            line(19, 7, 19, 19, gold)
            fillRect(9, 20, 19, 22, gold)
            line(14, 22, 14, 27, gold, 2)
            line(11, 25, 14, 28, red)
            line(17, 25, 14, 28, red)
            setSafe(4, 8, cream)
            setSafe(24, 5, cream)
            setSafe(25, 16, gold)
        }
    }

    private fun winterSnowGlobe(palette: BeadPalette): BeadTemplate {
        val sky = color(palette, 0xFF8EC7D2.toInt())
        val white = color(palette, 0xFFFFFBEE.toInt())
        val blue = color(palette, 0xFF447E9A.toInt())
        val red = color(palette, 0xFFD95757.toInt())
        val dark = color(palette, 0xFF394652.toInt())
        val gold = color(palette, 0xFFE8B746.toInt())
        return template("snow_globe", "冬日雪球", "节日", 29, 29, palette) {
            fillCircle(14, 12, 11, white)
            fillCircle(14, 12, 9, sky)
            fillEllipse(14, 18, 8, 3, white)
            fillCircle(14, 16, 4, white)
            fillCircle(14, 10, 3, white)
            fillCircle(13, 10, 1, dark)
            setSafe(16, 10, dark)
            line(14, 12, 18, 13, gold)
            fillRect(10, 6, 18, 7, dark)
            fillRect(12, 3, 16, 6, dark)
            line(10, 14, 18, 14, red, 2)
            listOf(7 to 8, 21 to 7, 7 to 14, 22 to 15, 18 to 3).forEach { (x, y) -> setSafe(x, y, white) }
            fillTriangle(5, 27, 23, 27, 20, 21, blue)
            fillRect(7, 24, 21, 27, dark)
            line(9, 25, 19, 25, gold)
        }
    }

    private fun birthdayParty(palette: BeadPalette): BeadTemplate {
        val background = color(palette, 0xFFFFE9D7.toInt())
        val teal = color(palette, 0xFF4DA0A0.toInt())
        val coral = color(palette, 0xFFE85F68.toInt())
        val yellow = color(palette, 0xFFF3C64E.toInt())
        val cream = color(palette, 0xFFFFFAEA.toInt())
        val cocoa = color(palette, 0xFF815546.toInt())
        return template("birthday_party", "生日派对", "节日", 29, 29, palette) {
            fillCircle(14, 14, 13, background)
            line(3, 5, 25, 7, teal)
            for (x in 5..23 step 6) fillTriangle(x - 2, 6, x + 2, 6, x, 10, if (x % 2 == 1) coral else yellow)
            fillRect(7, 17, 21, 24, coral)
            fillRect(5, 22, 23, 25, teal)
            fillRect(7, 16, 21, 18, cream)
            for (x in 9..19 step 5) {
                fillRect(x, 12, x + 1, 16, yellow)
                setSafe(x, 11, coral)
            }
            line(8, 21, 20, 21, yellow)
            setSafe(4, 13, coral)
            setSafe(24, 13, teal)
            setSafe(2, 18, yellow)
            setSafe(26, 20, cocoa)
        }
    }

    private fun quiltStar(palette: BeadPalette): BeadTemplate {
        val cream = color(palette, 0xFFFFF0D2.toInt())
        val navy = color(palette, 0xFF31475A.toInt())
        val teal = color(palette, 0xFF4F9D91.toInt())
        val coral = color(palette, 0xFFE66D62.toInt())
        val gold = color(palette, 0xFFE9B94E.toInt())
        return template("quilt_star", "拼布八角星", "几何", 29, 29, palette) {
            fillRect(1, 1, 27, 27, navy)
            fillRect(3, 3, 25, 25, cream)
            fillDiamond(14, 14, 11, teal)
            fillTriangle(14, 2, 17, 11, 11, 11, gold)
            fillTriangle(14, 26, 17, 17, 11, 17, gold)
            fillTriangle(2, 14, 11, 11, 11, 17, coral)
            fillTriangle(26, 14, 17, 11, 17, 17, coral)
            fillDiamond(14, 14, 5, navy)
            fillDiamond(5, 5, 2, coral)
            fillDiamond(23, 5, 2, teal)
            fillDiamond(5, 23, 2, teal)
            fillDiamond(23, 23, 2, coral)
            setSafe(14, 14, gold)
        }
    }

    private fun rhythmWaves(palette: BeadPalette): BeadTemplate {
        val navy = color(palette, 0xFF293B52.toInt())
        val teal = color(palette, 0xFF4FA5A1.toInt())
        val mint = color(palette, 0xFF91CCB8.toInt())
        val coral = color(palette, 0xFFEA7161.toInt())
        val gold = color(palette, 0xFFF1C34F.toInt())
        val cream = color(palette, 0xFFFFE9CC.toInt())
        val bands = intArrayOf(navy, teal, mint, gold, coral, cream)
        return template("rhythm_waves", "律动波纹", "几何", 58, 29, palette) {
            for (x in 0 until width) {
                val wave = (2.2 * sin((x / 10.0) * PI)).roundToInt()
                val shifted = (1.5 * sin((x / 8.0) * PI + PI / 3)).roundToInt()
                val boundaries = intArrayOf(0, 4 + wave, 9 + shifted, 14 + wave, 19 + shifted, 24 + wave, 29)
                for (band in bands.indices) {
                    for (y in boundaries[band] until boundaries[band + 1]) setSafe(x, y, bands[band])
                }
            }
            line(0, 14, 57, 14, cream)
            fillCircle(47, 7, 3, gold)
            circleRing(47, 7, 5, 4, navy)
        }
    }

    private fun stainedGlassBloom(palette: BeadPalette): BeadTemplate {
        val grout = color(palette, 0xFF293A43.toInt())
        val cream = color(palette, 0xFFFFEBC9.toInt())
        val coral = color(palette, 0xFFE96E67.toInt())
        val rose = color(palette, 0xFFD8516D.toInt())
        val gold = color(palette, 0xFFF0BE4D.toInt())
        val teal = color(palette, 0xFF479C98.toInt())
        val mint = color(palette, 0xFF8EC7A9.toInt())
        val blue = color(palette, 0xFF507FA0.toInt())
        val violet = color(palette, 0xFF776A9D.toInt())
        val panes = intArrayOf(coral, gold, mint, teal, blue, violet, rose, cream)
        return template("stained_glass", "玻璃花窗", "几何", 58, 58, palette) {
            val cx = 28
            val cy = 28
            for (y in 1 until 57) for (x in 1 until 57) {
                val dx = x - cx
                val dy = y - cy
                val radiusSquared = dx * dx + dy * dy
                if (radiusSquared <= 26 * 26) {
                    val angle = (atan2(dy.toDouble(), dx.toDouble()) + 2 * PI) % (2 * PI)
                    val sector = ((angle / (PI / 4)).toInt()) % panes.size
                    val ringOffset = if (radiusSquared < 12 * 12) 2 else if (radiusSquared < 20 * 20) 1 else 0
                    setSafe(x, y, panes[(sector + ringOffset) % panes.size])
                }
            }
            circleRing(cx, cy, 28, 25, grout)
            circleRing(cx, cy, 20, 18, grout)
            circleRing(cx, cy, 12, 10, grout)
            for (step in 0 until 8) {
                val angle = step * PI / 4
                val x = cx + (26 * cos(angle)).roundToInt()
                val y = cy + (26 * sin(angle)).roundToInt()
                line(cx, cy, x, y, grout, 2)
            }
            fillCircle(cx, cy, 5, gold)
            circleRing(cx, cy, 6, 5, grout)
        }
    }

    private fun pencilBookmark(palette: BeadPalette): BeadTemplate {
        val navy = color(palette, 0xFF2E4252.toInt())
        val teal = color(palette, 0xFF55A39C.toInt())
        val yellow = color(palette, 0xFFF2C64D.toInt())
        val coral = color(palette, 0xFFE86F63.toInt())
        val wood = color(palette, 0xFFE5BB80.toInt())
        val dark = color(palette, 0xFF3D3838.toInt())
        val white = color(palette, 0xFFFFF1D5.toInt())
        return template("pencil_bookmark", "铅笔书签", "实用", 29, 58, palette) {
            fillRoundedRect(3, 1, 25, 55, 3, teal)
            fillRoundedRect(5, 3, 23, 53, 2, navy)
            fillCircle(14, 6, 2, teal)
            setSafe(14, 6, EMPTY_CELL)
            fillRect(10, 12, 18, 43, yellow)
            fillRect(10, 12, 12, 43, white)
            fillRect(10, 8, 18, 12, coral)
            line(10, 13, 18, 13, dark)
            fillTriangle(10, 43, 18, 43, 14, 52, wood)
            fillTriangle(12, 48, 16, 48, 14, 52, dark)
            setSafe(8, 19, white)
            setSafe(21, 25, coral)
            setSafe(7, 34, coral)
            setSafe(21, 40, white)
            line(14, 55, 14, 57, coral, 2)
        }
    }

    private fun mountainKeyTag(palette: BeadPalette): BeadTemplate {
        val border = color(palette, 0xFF2F4A54.toInt())
        val sky = color(palette, 0xFF9ED3D0.toInt())
        val mountain = color(palette, 0xFF527668.toInt())
        val snow = color(palette, 0xFFFFF1D5.toInt())
        val sun = color(palette, 0xFFF2BE45.toInt())
        val coral = color(palette, 0xFFE66B5C.toInt())
        return template("mountain_key_tag", "远山钥匙牌", "实用", 29, 29, palette) {
            fillTriangle(5, 3, 23, 3, 27, 8, border)
            fillRect(2, 8, 26, 24, border)
            fillTriangle(2, 24, 7, 28, 7, 24, border)
            fillTriangle(26, 24, 21, 28, 21, 24, border)
            fillRoundedRect(4, 7, 24, 24, 2, sky)
            fillCircle(14, 5, 3, border)
            fillCircle(14, 5, 1, EMPTY_CELL)
            fillCircle(20, 11, 3, sun)
            fillTriangle(3, 23, 12, 12, 20, 23, mountain)
            fillTriangle(11, 23, 19, 15, 26, 23, coral)
            fillTriangle(9, 16, 12, 12, 15, 16, snow)
            fillTriangle(17, 18, 19, 15, 22, 18, snow)
            line(5, 24, 23, 24, border)
        }
    }

    private fun sunMoonCoasterPair(palette: BeadPalette): BeadTemplate {
        val cream = color(palette, 0xFFFFF0D3.toInt())
        val gold = color(palette, 0xFFF0BF48.toInt())
        val coral = color(palette, 0xFFE9755F.toInt())
        val navy = color(palette, 0xFF2F4358.toInt())
        val blue = color(palette, 0xFF5E8FA8.toInt())
        val white = color(palette, 0xFFFFF9E8.toInt())
        return template("sun_moon_coasters", "日月杯垫组", "实用", 58, 29, palette) {
            fillCircle(14, 14, 13, coral)
            fillCircle(14, 14, 11, cream)
            fillCircle(14, 14, 5, gold)
            for (step in 0 until 8) {
                val angle = step * PI / 4
                val x1 = 14 + (7 * cos(angle)).roundToInt()
                val y1 = 14 + (7 * sin(angle)).roundToInt()
                val x2 = 14 + (10 * cos(angle)).roundToInt()
                val y2 = 14 + (10 * sin(angle)).roundToInt()
                line(x1, y1, x2, y2, gold)
            }
            fillCircle(43, 14, 13, navy)
            fillCircle(43, 14, 11, blue)
            fillCircle(41, 13, 7, white)
            fillCircle(45, 10, 7, blue)
            setSafe(49, 18, white)
            setSafe(36, 7, white)
            fillDiamond(49, 7, 1, gold)
            fillDiamond(36, 20, 1, gold)
        }
    }

    private fun template(
        id: String,
        title: String,
        category: String,
        width: Int,
        height: Int,
        palette: BeadPalette,
        draw: PatternGrid.() -> Unit,
    ): BeadTemplate {
        val grid = PatternGrid(width, height).apply(draw)
        return BeadTemplate(id, title, category, grid, palette.id)
    }

    private fun color(palette: BeadPalette, argb: Int): Int = ColorMath.nearestColor(argb, palette.colors)

    private fun PatternGrid.setSafe(x: Int, y: Int, color: Int) {
        if (isInside(x, y)) this[x, y] = color
    }

    private fun PatternGrid.fillRect(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        for (y in top..bottom) for (x in left..right) setSafe(x, y, color)
    }

    private fun PatternGrid.strokeRect(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        line(left, top, right, top, color)
        line(right, top, right, bottom, color)
        line(right, bottom, left, bottom, color)
        line(left, bottom, left, top, color)
    }

    private fun PatternGrid.fillRoundedRect(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        radius: Int,
        color: Int,
    ) {
        fillRect(left + radius, top, right - radius, bottom, color)
        fillRect(left, top + radius, right, bottom - radius, color)
        fillCircle(left + radius, top + radius, radius, color)
        fillCircle(right - radius, top + radius, radius, color)
        fillCircle(left + radius, bottom - radius, radius, color)
        fillCircle(right - radius, bottom - radius, radius, color)
    }

    private fun PatternGrid.fillCircle(cx: Int, cy: Int, radius: Int, color: Int) {
        val radiusSquared = radius * radius
        for (y in cy - radius..cy + radius) for (x in cx - radius..cx + radius) {
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy <= radiusSquared) setSafe(x, y, color)
        }
    }

    private fun PatternGrid.circleRing(
        cx: Int,
        cy: Int,
        outerRadius: Int,
        innerRadius: Int,
        color: Int,
    ) {
        val outerSquared = outerRadius * outerRadius
        val innerSquared = innerRadius * innerRadius
        for (y in cy - outerRadius..cy + outerRadius) for (x in cx - outerRadius..cx + outerRadius) {
            val dx = x - cx
            val dy = y - cy
            val distance = dx * dx + dy * dy
            if (distance <= outerSquared && distance > innerSquared) setSafe(x, y, color)
        }
    }

    private fun PatternGrid.fillEllipse(cx: Int, cy: Int, radiusX: Int, radiusY: Int, color: Int) {
        val radiusXSquared = radiusX * radiusX
        val radiusYSquared = radiusY * radiusY
        val limit = radiusXSquared * radiusYSquared
        for (y in cy - radiusY..cy + radiusY) for (x in cx - radiusX..cx + radiusX) {
            val dx = x - cx
            val dy = y - cy
            if (dx * dx * radiusYSquared + dy * dy * radiusXSquared <= limit) setSafe(x, y, color)
        }
    }

    private fun PatternGrid.fillDiamond(cx: Int, cy: Int, radius: Int, color: Int) {
        for (y in cy - radius..cy + radius) {
            val halfWidth = radius - abs(y - cy)
            line(cx - halfWidth, y, cx + halfWidth, y, color)
        }
    }

    private fun PatternGrid.fillTriangle(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        x3: Int,
        y3: Int,
        color: Int,
    ) {
        val minX = minOf(x1, x2, x3)
        val maxX = maxOf(x1, x2, x3)
        val minY = minOf(y1, y2, y3)
        val maxY = maxOf(y1, y2, y3)
        val area = edge(x1, y1, x2, y2, x3, y3)
        if (area == 0) return
        for (y in minY..maxY) for (x in minX..maxX) {
            val first = edge(x1, y1, x2, y2, x, y)
            val second = edge(x2, y2, x3, y3, x, y)
            val third = edge(x3, y3, x1, y1, x, y)
            if ((area > 0 && first >= 0 && second >= 0 && third >= 0) ||
                (area < 0 && first <= 0 && second <= 0 && third <= 0)
            ) {
                setSafe(x, y, color)
            }
        }
    }

    private fun PatternGrid.line(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        color: Int,
        thickness: Int = 1,
    ) {
        var x = startX
        var y = startY
        val dx = abs(endX - startX)
        val stepX = if (startX < endX) 1 else -1
        val dy = -abs(endY - startY)
        val stepY = if (startY < endY) 1 else -1
        var error = dx + dy
        while (true) {
            val offset = (thickness - 1) / 2
            for (py in y - offset..y + thickness / 2) {
                for (px in x - offset..x + thickness / 2) setSafe(px, py, color)
            }
            if (x == endX && y == endY) break
            val twiceError = 2 * error
            if (twiceError >= dy) {
                error += dy
                x += stepX
            }
            if (twiceError <= dx) {
                error += dx
                y += stepY
            }
        }
    }

    private fun edge(ax: Int, ay: Int, bx: Int, by: Int, px: Int, py: Int): Int =
        (px - ax) * (by - ay) - (py - ay) * (bx - ax)
}
