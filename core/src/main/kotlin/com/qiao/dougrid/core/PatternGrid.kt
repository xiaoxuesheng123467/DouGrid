package com.qiao.dougrid.core

const val EMPTY_CELL = -1

class PatternGrid(
    val width: Int,
    val height: Int,
    val cells: IntArray = IntArray(width * height) { EMPTY_CELL },
    val completed: ByteArray = ByteArray(width * height),
) {
    init {
        require(width in 1..512 && height in 1..512) { "Grid dimensions must be in 1...512" }
        require(cells.size == width * height) { "Cell count does not match dimensions" }
        require(completed.size == cells.size) { "Completion count does not match cells" }
    }

    val size: Int get() = cells.size

    fun indexOf(column: Int, row: Int): Int = row * width + column

    fun isInside(column: Int, row: Int): Boolean =
        column in 0 until width && row in 0 until height

    operator fun get(column: Int, row: Int): Int = cells[indexOf(column, row)]

    operator fun set(column: Int, row: Int, colorIndex: Int) {
        cells[indexOf(column, row)] = colorIndex
    }

    fun deepCopy(): PatternGrid = PatternGrid(
        width = width,
        height = height,
        cells = cells.copyOf(),
        completed = completed.copyOf(),
    )

    fun beadCount(): Int = cells.count { it != EMPTY_CELL }

    fun colorCounts(): Map<Int, Int> = buildMap {
        cells.forEach { color ->
            if (color != EMPTY_CELL) put(color, (get(color) ?: 0) + 1)
        }
    }

    fun completedCount(): Int = completed.count { it.toInt() != 0 }

    fun toggleCompleted(index: Int): Boolean {
        if (index !in cells.indices || cells[index] == EMPTY_CELL) return false
        completed[index] = if (completed[index].toInt() == 0) 1 else 0
        return completed[index].toInt() != 0
    }

    fun floodFill(column: Int, row: Int, replacement: Int): CellDelta? {
        if (!isInside(column, row)) return null
        val start = indexOf(column, row)
        val target = cells[start]
        if (target == replacement) return null

        val queue = IntArray(size)
        val touched = IntArray(size)
        var head = 0
        var tail = 0
        var count = 0
        queue[tail++] = start
        cells[start] = replacement

        while (head < tail) {
            val index = queue[head++]
            touched[count++] = index
            val x = index % width
            val y = index / width
            fun visit(next: Int) {
                if (cells[next] == target) {
                    cells[next] = replacement
                    queue[tail++] = next
                }
            }
            if (x > 0) visit(index - 1)
            if (x + 1 < width) visit(index + 1)
            if (y > 0) visit(index - width)
            if (y + 1 < height) visit(index + width)
        }

        return CellDelta(
            indices = touched.copyOf(count),
            before = IntArray(count) { target },
            after = IntArray(count) { replacement },
            label = "填充",
        )
    }

    fun replaceAll(target: Int, replacement: Int): CellDelta? {
        if (target == replacement) return null
        val indices = cells.indices.filter { cells[it] == target }.toIntArray()
        if (indices.isEmpty()) return null
        indices.forEach { cells[it] = replacement }
        return CellDelta(
            indices = indices,
            before = IntArray(indices.size) { target },
            after = IntArray(indices.size) { replacement },
            label = "同色替换",
        )
    }

    fun mirrorHorizontal(): CellDelta {
        val before = cells.copyOf()
        for (row in 0 until height) {
            for (column in 0 until width / 2) {
                val left = indexOf(column, row)
                val right = indexOf(width - 1 - column, row)
                val value = cells[left]
                cells[left] = cells[right]
                cells[right] = value
            }
        }
        return fullGridDelta(before, "水平镜像")
    }

    fun mirrorVertical(): CellDelta {
        val before = cells.copyOf()
        for (row in 0 until height / 2) {
            for (column in 0 until width) {
                val top = indexOf(column, row)
                val bottom = indexOf(column, height - 1 - row)
                val value = cells[top]
                cells[top] = cells[bottom]
                cells[bottom] = value
            }
        }
        return fullGridDelta(before, "垂直镜像")
    }

    private fun fullGridDelta(before: IntArray, label: String): CellDelta {
        val changed = cells.indices.filter { before[it] != cells[it] }.toIntArray()
        return CellDelta(
            indices = changed,
            before = IntArray(changed.size) { before[changed[it]] },
            after = IntArray(changed.size) { cells[changed[it]] },
            label = label,
        )
    }
}

data class CellDelta(
    val indices: IntArray,
    val before: IntArray,
    val after: IntArray,
    val label: String,
) {
    init {
        require(indices.size == before.size && indices.size == after.size)
    }

    fun applyTo(grid: PatternGrid) {
        indices.indices.forEach { grid.cells[indices[it]] = after[it] }
    }

    fun revertOn(grid: PatternGrid) {
        indices.indices.forEach { grid.cells[indices[it]] = before[it] }
    }
}

class EditorHistory(private val capacity: Int = 80) {
    private val undoStack = ArrayDeque<CellDelta>()
    private val redoStack = ArrayDeque<CellDelta>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun record(delta: CellDelta?) {
        if (delta == null || delta.indices.isEmpty()) return
        undoStack.addLast(delta)
        while (undoStack.size > capacity) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo(grid: PatternGrid): CellDelta? = undoStack.removeLastOrNull()?.also {
        it.revertOn(grid)
        redoStack.addLast(it)
    }

    fun redo(grid: PatternGrid): CellDelta? = redoStack.removeLastOrNull()?.also {
        it.applyTo(grid)
        undoStack.addLast(it)
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
