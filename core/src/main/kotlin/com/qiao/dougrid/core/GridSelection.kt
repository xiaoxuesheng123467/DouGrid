package com.qiao.dougrid.core

/** A non-empty rectangular range of cells within a pattern grid. */
data class GridRegion(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0) { "Region dimensions must be positive" }
        require(left.toLong() + width <= Int.MAX_VALUE) { "Region exceeds the coordinate range" }
        require(top.toLong() + height <= Int.MAX_VALUE) { "Region exceeds the coordinate range" }
    }

    val rightExclusive: Int get() = left + width
    val bottomExclusive: Int get() = top + height

    /** Returns this region's intersection with a grid, or `null` when they do not overlap. */
    fun clampedTo(gridWidth: Int, gridHeight: Int): GridRegion? {
        require(gridWidth > 0 && gridHeight > 0) { "Grid dimensions must be positive" }
        val clampedLeft = left.coerceAtLeast(0)
        val clampedTop = top.coerceAtLeast(0)
        val clampedRight = rightExclusive.coerceAtMost(gridWidth)
        val clampedBottom = bottomExclusive.coerceAtMost(gridHeight)
        if (clampedRight <= clampedLeft || clampedBottom <= clampedTop) return null
        return GridRegion(
            left = clampedLeft,
            top = clampedTop,
            width = clampedRight - clampedLeft,
            height = clampedBottom - clampedTop,
        )
    }

    companion object {
        /** Creates a normalized region from two inclusive cell coordinates. */
        fun fromCellCorners(
            firstColumn: Int,
            firstRow: Int,
            secondColumn: Int,
            secondRow: Int,
        ): GridRegion {
            val left = minOf(firstColumn, secondColumn)
            val top = minOf(firstRow, secondRow)
            val right = maxOf(firstColumn, secondColumn)
            val bottom = maxOf(firstRow, secondRow)
            val width = right.toLong() - left + 1L
            val height = bottom.toLong() - top + 1L
            require(width <= Int.MAX_VALUE && height <= Int.MAX_VALUE) {
                "Region exceeds the coordinate range"
            }
            return GridRegion(left, top, width.toInt(), height.toInt())
        }
    }
}

/**
 * An immutable rectangular snapshot of pattern cells.
 *
 * Empty cells are retained so pasting a selection reproduces the complete rectangular shape.
 */
class GridSelection(
    val width: Int,
    val height: Int,
    cells: IntArray,
) {
    private val values = cells.copyOf()

    init {
        require(width > 0 && height > 0) { "Selection dimensions must be positive" }
        require(width.toLong() * height == cells.size.toLong()) {
            "Selection cell count does not match dimensions"
        }
    }

    operator fun get(column: Int, row: Int): Int {
        require(column in 0 until width && row in 0 until height) {
            "Selection coordinates are outside its bounds"
        }
        return values[row * width + column]
    }

    fun toIntArray(): IntArray = values.copyOf()

    fun rotateClockwise(): GridSelection {
        val rotated = IntArray(values.size)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val targetColumn = height - 1 - row
                val targetRow = column
                rotated[targetRow * height + targetColumn] = values[row * width + column]
            }
        }
        return GridSelection(width = height, height = width, cells = rotated)
    }

    fun rotateCounterClockwise(): GridSelection {
        val rotated = IntArray(values.size)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val targetColumn = row
                val targetRow = width - 1 - column
                rotated[targetRow * height + targetColumn] = values[row * width + column]
            }
        }
        return GridSelection(width = height, height = width, cells = rotated)
    }

    fun mirrorHorizontal(): GridSelection {
        val mirrored = IntArray(values.size)
        for (row in 0 until height) {
            for (column in 0 until width) {
                mirrored[row * width + width - 1 - column] = values[row * width + column]
            }
        }
        return GridSelection(width, height, mirrored)
    }

    fun mirrorVertical(): GridSelection {
        val mirrored = IntArray(values.size)
        for (row in 0 until height) {
            for (column in 0 until width) {
                mirrored[(height - 1 - row) * width + column] = values[row * width + column]
            }
        }
        return GridSelection(width, height, mirrored)
    }

    override fun equals(other: Any?): Boolean =
        other is GridSelection &&
            width == other.width &&
            height == other.height &&
            values.contentEquals(other.values)

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + values.contentHashCode()
        return result
    }

    override fun toString(): String =
        "GridSelection(width=$width, height=$height, cells=${values.contentToString()})"
}
