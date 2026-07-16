package com.qiao.dougrid.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class ConversionMode { PHOTO, SPRITE }

data class QuantizeOptions(
    val mode: ConversionMode = ConversionMode.PHOTO,
    val maxColors: Int = 32,
    val ditherStrength: Float = 0f,
    val cleanupIslandSize: Int = 1,
    val removeLightBackground: Boolean = false,
    val backgroundLumaThreshold: Float = 0.94f,
)

data class QuantizeResult(
    val cells: IntArray,
    val selectedPaletteIndices: IntArray,
)

object Quantizer {
    private const val DISTANCE_EPSILON = 1e-12
    private const val OBJECTIVE_EPSILON = 1e-10
    private const val DETAIL_PROTECTION_MARGIN = 0.018
    private const val MAX_PROPAGATED_ERROR = 128f

    fun quantize(
        pixels: IntArray,
        width: Int,
        height: Int,
        palette: BeadPalette,
        options: QuantizeOptions,
        allowedPaletteIndices: IntArray? = null,
        cancellationCheck: () -> Unit = {},
    ): QuantizeResult {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        require(pixels.size == width * height) { "Pixel count must match image dimensions" }

        val available = if (allowedPaletteIndices == null) {
            palette.colors.indices.toList().toIntArray()
        } else {
            allowedPaletteIndices
                .asSequence()
                .filter { it in palette.colors.indices }
                .distinct()
                .sorted()
                .toList()
                .toIntArray()
        }
        if (available.isEmpty()) {
            cancellationCheck()
            return QuantizeResult(IntArray(pixels.size) { EMPTY_CELL }, IntArray(0))
        }
        val colorLimit = options.maxColors.coerceAtLeast(1).coerceAtMost(available.size)

        val prepared = IntArray(pixels.size)
        for (row in 0 until height) {
            cancellationCheck()
            for (column in 0 until width) {
                val index = row * width + column
                val color = pixels[index]
                prepared[index] = if (color ushr 24 and 0xFF < 32) {
                    0
                } else {
                    color or 0xFF000000.toInt()
                }
            }
        }
        if (options.removeLightBackground) {
            removeEdgeConnectedLightBackground(
                pixels = prepared,
                width = width,
                height = height,
                threshold = options.backgroundLumaThreshold,
                cancellationCheck = cancellationCheck,
            )
        }
        val sourceLabs = arrayOfNulls<Oklab>(prepared.size)
        for (row in 0 until height) {
            cancellationCheck()
            for (column in 0 until width) {
                val index = row * width + column
                if (prepared[index] ushr 24 != 0) {
                    sourceLabs[index] = ColorMath.toOklab(prepared[index])
                }
            }
        }
        val paletteLabs = Array(palette.colors.size) { index ->
            ColorMath.toOklab(palette.colors[index].opaqueArgb)
        }
        val selected = choosePaletteColors(
            sourceLabs = sourceLabs,
            width = width,
            height = height,
            paletteLabs = paletteLabs,
            available = available,
            limit = colorLimit,
            mode = options.mode,
            allowDitherBlends = options.mode == ConversionMode.PHOTO &&
                options.ditherStrength.isFinite() && options.ditherStrength > 0f,
            cancellationCheck = cancellationCheck,
        )

        if (selected.isEmpty()) {
            return QuantizeResult(IntArray(prepared.size) { EMPTY_CELL }, selected)
        }

        val matcher = PaletteMatcher(paletteLabs, selected)
        val cells = if (options.mode == ConversionMode.PHOTO &&
            options.ditherStrength.isFinite() && options.ditherStrength > 0f
        ) {
            dither(
                pixels = prepared,
                sourceLabs = sourceLabs,
                width = width,
                height = height,
                palette = palette.colors,
                matcher = matcher,
                strength = options.ditherStrength,
                cancellationCheck = cancellationCheck,
            )
        } else {
            IntArray(prepared.size).also { result ->
                for (row in 0 until height) {
                    cancellationCheck()
                    for (column in 0 until width) {
                        val index = row * width + column
                        result[index] = sourceLabs[index]?.let(matcher::nearest) ?: EMPTY_CELL
                    }
                }
            }
        }

        if (options.cleanupIslandSize > 0) {
            cleanupSmallIslands(
                cells = cells,
                width = width,
                height = height,
                maxIslandSize = options.cleanupIslandSize.coerceAtMost(4),
                sourceLabs = sourceLabs,
                paletteLabs = paletteLabs,
                cancellationCheck = cancellationCheck,
            )
        }
        return QuantizeResult(cells, selected)
    }

    private fun removeEdgeConnectedLightBackground(
        pixels: IntArray,
        width: Int,
        height: Int,
        threshold: Float,
        cancellationCheck: () -> Unit,
    ) {
        val normalizedThreshold = threshold.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0.94f
        val queued = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        var head = 0
        var tail = 0

        fun enqueue(index: Int) {
            if (!queued[index] && isLightBackgroundCandidate(pixels[index], normalizedThreshold)) {
                queued[index] = true
                queue[tail++] = index
            }
        }
        for (x in 0 until width) {
            enqueue(x)
            if (height > 1) enqueue((height - 1) * width + x)
        }
        for (y in 1 until height - 1) {
            enqueue(y * width)
            if (width > 1) enqueue(y * width + width - 1)
        }

        var processed = 0
        while (head < tail) {
            if (processed++ and 0x3FF == 0) cancellationCheck()
            val index = queue[head++]
            pixels[index] = 0
            val x = index % width
            val y = index / width
            if (x > 0) enqueue(index - 1)
            if (x + 1 < width) enqueue(index + 1)
            if (y > 0) enqueue(index - width)
            if (y + 1 < height) enqueue(index + width)
        }
    }

    private fun isLightBackgroundCandidate(argb: Int, threshold: Float): Boolean {
        if (argb ushr 24 == 0) return false
        val red = (argb ushr 16 and 0xFF) / 255f
        val green = (argb ushr 8 and 0xFF) / 255f
        val blue = (argb and 0xFF) / 255f
        val luma = 0.2126f * red + 0.7152f * green + 0.0722f * blue
        val chroma = max(red, max(green, blue)) - min(red, min(green, blue))
        return luma >= threshold && chroma < 0.1f
    }

    private fun choosePaletteColors(
        sourceLabs: Array<Oklab?>,
        width: Int,
        height: Int,
        paletteLabs: Array<Oklab>,
        available: IntArray,
        limit: Int,
        mode: ConversionMode,
        allowDitherBlends: Boolean,
        cancellationCheck: () -> Unit,
    ): IntArray {
        cancellationCheck()
        val opaqueCount = sourceLabs.count { it != null }
        if (opaqueCount == 0) return IntArray(0)

        val localContrasts = calculateLocalContrasts(
            sourceLabs,
            width,
            height,
            cancellationCheck,
        )
        val accumulators = arrayOfNulls<DemandAccumulator>(available.size)
        sourceLabs.indices.forEach { index ->
            if (index % width == 0) cancellationCheck()
            val source = sourceLabs[index] ?: return@forEach
            val contrast = localContrasts[index]
            val multiplier = when (mode) {
                ConversionMode.SPRITE -> 1.0 + (contrast * 7.0).coerceAtMost(6.0)
                ConversionMode.PHOTO -> 1.0 + (contrast * 3.5).coerceAtMost(2.5)
            }
            val candidatePosition = nearestCandidatePosition(source, paletteLabs, available)
            val accumulator = accumulators[candidatePosition]
                ?: DemandAccumulator().also { accumulators[candidatePosition] = it }
            accumulator.add(source, multiplier, contrast)
        }

        val coverageScale = sqrt(opaqueCount.toDouble()).coerceAtMost(20.0)
        val demands = accumulators.mapNotNull { accumulator ->
            accumulator?.toDemand(
                extraWeight = when (mode) {
                    ConversionMode.SPRITE -> coverageScale *
                        (0.4 + 0.6 * accumulator.maxContrast.coerceIn(0.0, 1.0))
                    ConversionMode.PHOTO -> coverageScale * 0.12 *
                        accumulator.maxContrast.coerceIn(0.0, 1.0)
                },
            )
        }
        if (demands.isEmpty()) return IntArray(0)

        val distances = Array(available.size) { candidatePosition ->
            DoubleArray(demands.size) { demandIndex ->
                demands[demandIndex].lab.distanceSquared(
                    paletteLabs[available[candidatePosition]],
                )
            }
        }
        val weights = DoubleArray(demands.size) { demands[it].weight }

        var firstCandidate = 0
        var firstCost = Double.POSITIVE_INFINITY
        available.indices.forEach { candidatePosition ->
            val cost = weightedCost(distances[candidatePosition], weights)
            if (cost < firstCost - OBJECTIVE_EPSILON ||
                abs(cost - firstCost) <= OBJECTIVE_EPSILON &&
                available[candidatePosition] < available[firstCandidate]
            ) {
                firstCandidate = candidatePosition
                firstCost = cost
            }
        }

        val selectedPositions = mutableListOf(firstCandidate)
        val selectedMask = BooleanArray(available.size)
        selectedMask[firstCandidate] = true
        val currentDistances = distances[firstCandidate].copyOf()
        val blendDistances = if (allowDitherBlends) {
            Array(available.size) { DoubleArray(demands.size) { Double.POSITIVE_INFINITY } }
        } else {
            null
        }
        if (blendDistances != null) {
            updateBlendDistances(
                blendDistances,
                newSelectedPosition = firstCandidate,
                demands = demands,
                available = available,
                paletteLabs = paletteLabs,
            )
        }

        while (selectedPositions.size < limit) {
            cancellationCheck()
            var bestCandidate = -1
            var bestGain = 0.0
            available.indices.forEach { candidatePosition ->
                if (selectedMask[candidatePosition]) return@forEach
                var gain = 0.0
                demands.indices.forEach { demandIndex ->
                    val candidateDistance = if (blendDistances == null) {
                        distances[candidatePosition][demandIndex]
                    } else {
                        min(
                            distances[candidatePosition][demandIndex],
                            blendDistances[candidatePosition][demandIndex],
                        )
                    }
                    gain += weights[demandIndex] *
                        (currentDistances[demandIndex] - candidateDistance).coerceAtLeast(0.0)
                }
                if (gain > bestGain + OBJECTIVE_EPSILON ||
                    abs(gain - bestGain) <= OBJECTIVE_EPSILON && gain > OBJECTIVE_EPSILON &&
                    (bestCandidate < 0 || available[candidatePosition] < available[bestCandidate])
                ) {
                    bestCandidate = candidatePosition
                    bestGain = gain
                }
            }
            if (bestCandidate < 0 || bestGain <= OBJECTIVE_EPSILON) break

            demands.indices.forEach { demandIndex ->
                val candidateDistance = if (blendDistances == null) {
                    distances[bestCandidate][demandIndex]
                } else {
                    min(
                        distances[bestCandidate][demandIndex],
                        blendDistances[bestCandidate][demandIndex],
                    )
                }
                currentDistances[demandIndex] = min(
                    currentDistances[demandIndex],
                    candidateDistance,
                )
            }
            selectedPositions += bestCandidate
            selectedMask[bestCandidate] = true
            if (blendDistances != null) {
                updateBlendDistances(
                    blendDistances,
                    newSelectedPosition = bestCandidate,
                    demands = demands,
                    available = available,
                    paletteLabs = paletteLabs,
                )
            }
        }

        if (allowDitherBlends) {
            refineInitialBlendRepresentative(
                selectedPositions = selectedPositions,
                demands = demands,
                weights = weights,
                distances = distances,
                available = available,
                paletteLabs = paletteLabs,
                cancellationCheck = cancellationCheck,
            )
        } else {
            refinePointSelection(
                selectedPositions,
                distances,
                weights,
                available,
                cancellationCheck,
            )
        }

        return selectedPositions
            .map(available::get)
            .distinct()
            .sorted()
            .toIntArray()
    }

    private fun calculateLocalContrasts(
        sourceLabs: Array<Oklab?>,
        width: Int,
        height: Int,
        cancellationCheck: () -> Unit,
    ): DoubleArray {
        val result = DoubleArray(sourceLabs.size)
        sourceLabs.indices.forEach { index ->
            if (index % width == 0) cancellationCheck()
            val source = sourceLabs[index] ?: return@forEach
            val x = index % width
            val y = index / width
            var sum = 0.0
            var count = 0
            fun include(next: Int) {
                val neighbor = sourceLabs[next] ?: return
                sum += sqrt(source.distanceSquared(neighbor))
                count++
            }
            if (x > 0) include(index - 1)
            if (x + 1 < width) include(index + 1)
            if (y > 0) include(index - width)
            if (y + 1 < height) include(index + width)
            if (count > 0) result[index] = sum / count
        }
        return result
    }

    private fun nearestCandidatePosition(
        source: Oklab,
        paletteLabs: Array<Oklab>,
        available: IntArray,
    ): Int {
        var bestPosition = 0
        var bestDistance = Double.POSITIVE_INFINITY
        available.indices.forEach { position ->
            val distance = source.distanceSquared(paletteLabs[available[position]])
            if (distance < bestDistance - DISTANCE_EPSILON ||
                abs(distance - bestDistance) <= DISTANCE_EPSILON &&
                available[position] < available[bestPosition]
            ) {
                bestPosition = position
                bestDistance = distance
            }
        }
        return bestPosition
    }

    private fun weightedCost(distances: DoubleArray, weights: DoubleArray): Double {
        var result = 0.0
        distances.indices.forEach { index -> result += distances[index] * weights[index] }
        return result
    }

    private fun updateBlendDistances(
        blendDistances: Array<DoubleArray>,
        newSelectedPosition: Int,
        demands: List<PaletteDemand>,
        available: IntArray,
        paletteLabs: Array<Oklab>,
    ) {
        val selectedLab = paletteLabs[available[newSelectedPosition]]
        available.indices.forEach { candidatePosition ->
            val candidateLab = paletteLabs[available[candidatePosition]]
            demands.indices.forEach { demandIndex ->
                blendDistances[candidatePosition][demandIndex] = min(
                    blendDistances[candidatePosition][demandIndex],
                    segmentDistanceSquared(demands[demandIndex].lab, selectedLab, candidateLab),
                )
            }
        }
    }

    private fun refinePointSelection(
        selectedPositions: MutableList<Int>,
        distances: Array<DoubleArray>,
        weights: DoubleArray,
        available: IntArray,
        cancellationCheck: () -> Unit,
    ) {
        repeat(4) {
            cancellationCheck()
            val selectedMask = BooleanArray(available.size)
            selectedPositions.forEach { selectedMask[it] = true }
            val bestDistances = DoubleArray(weights.size) { Double.POSITIVE_INFINITY }
            val secondDistances = DoubleArray(weights.size) { Double.POSITIVE_INFINITY }
            val owners = IntArray(weights.size) { -1 }
            weights.indices.forEach { demandIndex ->
                selectedPositions.indices.forEach { slot ->
                    val distance = distances[selectedPositions[slot]][demandIndex]
                    if (distance < bestDistances[demandIndex] - DISTANCE_EPSILON) {
                        secondDistances[demandIndex] = bestDistances[demandIndex]
                        bestDistances[demandIndex] = distance
                        owners[demandIndex] = slot
                    } else if (distance < secondDistances[demandIndex]) {
                        secondDistances[demandIndex] = distance
                    }
                }
            }
            val currentCost = weightedCost(bestDistances, weights)
            var bestCost = currentCost
            var bestSlot = -1
            var bestCandidate = -1
            selectedPositions.indices.forEach { slot ->
                cancellationCheck()
                available.indices.forEach { candidatePosition ->
                    if (selectedMask[candidatePosition]) return@forEach
                    var cost = 0.0
                    weights.indices.forEach { demandIndex ->
                        val retainedDistance = if (owners[demandIndex] == slot) {
                            secondDistances[demandIndex]
                        } else {
                            bestDistances[demandIndex]
                        }
                        cost += weights[demandIndex] * min(
                            retainedDistance,
                            distances[candidatePosition][demandIndex],
                        )
                    }
                    if (cost < bestCost - OBJECTIVE_EPSILON ||
                        abs(cost - bestCost) <= OBJECTIVE_EPSILON && bestSlot >= 0 &&
                        available[candidatePosition] < available[bestCandidate]
                    ) {
                        bestCost = cost
                        bestSlot = slot
                        bestCandidate = candidatePosition
                    }
                }
            }
            if (bestSlot < 0 || bestCost >= currentCost - OBJECTIVE_EPSILON) return
            selectedPositions[bestSlot] = bestCandidate
        }
    }

    private fun refineInitialBlendRepresentative(
        selectedPositions: MutableList<Int>,
        demands: List<PaletteDemand>,
        weights: DoubleArray,
        distances: Array<DoubleArray>,
        available: IntArray,
        paletteLabs: Array<Oklab>,
        cancellationCheck: () -> Unit,
    ) {
        cancellationCheck()
        if (selectedPositions.size < 2) return
        val fixed = selectedPositions.drop(1)
        val fixedMask = BooleanArray(available.size)
        fixed.forEach { fixedMask[it] = true }
        val baseDistances = DoubleArray(demands.size) { demandIndex ->
            var best = Double.POSITIVE_INFINITY
            fixed.forEach { position -> best = min(best, distances[position][demandIndex]) }
            fixed.indices.forEach { left ->
                for (right in left + 1 until fixed.size) {
                    best = min(
                        best,
                        segmentDistanceSquared(
                            demands[demandIndex].lab,
                            paletteLabs[available[fixed[left]]],
                            paletteLabs[available[fixed[right]]],
                        ),
                    )
                }
            }
            best
        }

        val currentCandidate = selectedPositions.first()
        var bestCandidate = currentCandidate
        var bestCost = blendReplacementCost(
            candidatePosition = currentCandidate,
            fixed = fixed,
            baseDistances = baseDistances,
            demands = demands,
            weights = weights,
            distances = distances,
            available = available,
            paletteLabs = paletteLabs,
        )
        available.indices.forEach { candidatePosition ->
            cancellationCheck()
            if (fixedMask[candidatePosition] || candidatePosition == currentCandidate) return@forEach
            val cost = blendReplacementCost(
                candidatePosition = candidatePosition,
                fixed = fixed,
                baseDistances = baseDistances,
                demands = demands,
                weights = weights,
                distances = distances,
                available = available,
                paletteLabs = paletteLabs,
            )
            if (cost < bestCost - OBJECTIVE_EPSILON ||
                abs(cost - bestCost) <= OBJECTIVE_EPSILON &&
                available[candidatePosition] < available[bestCandidate]
            ) {
                bestCandidate = candidatePosition
                bestCost = cost
            }
        }
        selectedPositions[0] = bestCandidate
    }

    private fun blendReplacementCost(
        candidatePosition: Int,
        fixed: List<Int>,
        baseDistances: DoubleArray,
        demands: List<PaletteDemand>,
        weights: DoubleArray,
        distances: Array<DoubleArray>,
        available: IntArray,
        paletteLabs: Array<Oklab>,
    ): Double {
        val candidateLab = paletteLabs[available[candidatePosition]]
        var cost = 0.0
        demands.indices.forEach { demandIndex ->
            var best = min(baseDistances[demandIndex], distances[candidatePosition][demandIndex])
            fixed.forEach { fixedPosition ->
                best = min(
                    best,
                    segmentDistanceSquared(
                        demands[demandIndex].lab,
                        candidateLab,
                        paletteLabs[available[fixedPosition]],
                    ),
                )
            }
            cost += best * weights[demandIndex]
        }
        return cost
    }

    private fun segmentDistanceSquared(point: Oklab, start: Oklab, end: Oklab): Double {
        val dl = end.l - start.l
        val da = end.a - start.a
        val db = end.b - start.b
        val lengthSquared = dl * dl + da * da + db * db
        if (lengthSquared <= DISTANCE_EPSILON) return point.distanceSquared(start)
        val projection = (
            (point.l - start.l) * dl +
                (point.a - start.a) * da +
                (point.b - start.b) * db
            ) / lengthSquared
        val amount = projection.coerceIn(0.0, 1.0)
        val projectedL = start.l + dl * amount
        val projectedA = start.a + da * amount
        val projectedB = start.b + db * amount
        val pointDl = point.l - projectedL
        val pointDa = point.a - projectedA
        val pointDb = point.b - projectedB
        return pointDl * pointDl + pointDa * pointDa + pointDb * pointDb
    }

    private fun dither(
        pixels: IntArray,
        sourceLabs: Array<Oklab?>,
        width: Int,
        height: Int,
        palette: List<PaletteColor>,
        matcher: PaletteMatcher,
        strength: Float,
        cancellationCheck: () -> Unit,
    ): IntArray {
        val errorRed = FloatArray(pixels.size)
        val errorGreen = FloatArray(pixels.size)
        val errorBlue = FloatArray(pixels.size)
        val result = IntArray(pixels.size) { EMPTY_CELL }
        val amount = strength.coerceIn(0f, 1f)

        for (row in 0 until height) {
            cancellationCheck()
            val forward = row % 2 == 0
            val columns = if (forward) 0 until width else width - 1 downTo 0
            for (column in columns) {
                val index = row * width + column
                val originalLab = sourceLabs[index] ?: continue
                val original = pixels[index]
                val red = ((original ushr 16 and 0xFF) + errorRed[index])
                    .coerceIn(0f, 255f)
                val green = ((original ushr 8 and 0xFF) + errorGreen[index])
                    .coerceIn(0f, 255f)
                val blue = ((original and 0xFF) + errorBlue[index])
                    .coerceIn(0f, 255f)
                val adjusted = ColorMath.argb(red.roundToInt(), green.roundToInt(), blue.roundToInt())
                val paletteIndex = matcher.nearest(ColorMath.toOklab(adjusted))
                result[index] = paletteIndex
                val target = palette[paletteIndex].opaqueArgb
                val differenceRed = (red - (target ushr 16 and 0xFF)) * amount
                val differenceGreen = (green - (target ushr 8 and 0xFF)) * amount
                val differenceBlue = (blue - (target and 0xFF)) * amount

                fun diffuse(x: Int, y: Int, factor: Float) {
                    if (x !in 0 until width || y !in 0 until height) return
                    val next = y * width + x
                    val nextLab = sourceLabs[next] ?: return
                    val edgeFactor = edgeDiffusionFactor(originalLab, nextLab)
                    if (edgeFactor <= 0f) return
                    val weightedFactor = factor * edgeFactor
                    errorRed[next] = (errorRed[next] + differenceRed * weightedFactor)
                        .coerceIn(-MAX_PROPAGATED_ERROR, MAX_PROPAGATED_ERROR)
                    errorGreen[next] = (errorGreen[next] + differenceGreen * weightedFactor)
                        .coerceIn(-MAX_PROPAGATED_ERROR, MAX_PROPAGATED_ERROR)
                    errorBlue[next] = (errorBlue[next] + differenceBlue * weightedFactor)
                        .coerceIn(-MAX_PROPAGATED_ERROR, MAX_PROPAGATED_ERROR)
                }

                val direction = if (forward) 1 else -1
                diffuse(column + direction, row, 7f / 16f)
                diffuse(column - direction, row + 1, 3f / 16f)
                diffuse(column, row + 1, 5f / 16f)
                diffuse(column + direction, row + 1, 1f / 16f)
            }
        }
        return result
    }

    private fun edgeDiffusionFactor(source: Oklab, neighbor: Oklab): Float {
        val distance = sqrt(source.distanceSquared(neighbor))
        return when {
            distance <= 0.04 -> 1f
            distance >= 0.28 -> 0f
            else -> ((0.28 - distance) / 0.24).toFloat()
        }
    }

    fun cleanupSmallIslands(cells: IntArray, width: Int, height: Int, maxIslandSize: Int) {
        cleanupSmallIslands(cells, width, height, maxIslandSize, null, null, {})
    }

    private fun cleanupSmallIslands(
        cells: IntArray,
        width: Int,
        height: Int,
        maxIslandSize: Int,
        sourceLabs: Array<Oklab?>?,
        paletteLabs: Array<Oklab>?,
        cancellationCheck: () -> Unit,
    ) {
        require(width > 0 && height > 0) { "Grid dimensions must be positive" }
        require(cells.size == width * height) { "Cell count must match grid dimensions" }
        if (maxIslandSize <= 0) return

        val snapshot = cells.copyOf()
        val components = labelComponents(snapshot, width, height, cancellationCheck)
        components.forEach { component ->
            cancellationCheck()
            val target = component.color
            var replacement = EMPTY_CELL
            var replacementComponent = -1
            var mixedBoundary = false
            var boundaryEdges = 0
            var touchesOuterEdge = false
            var touchesEmpty = false
            component.cells.forEach { index ->
                val x = index % width
                val y = index / width

                fun visit(xNext: Int, yNext: Int) {
                    if (xNext !in 0 until width || yNext !in 0 until height) {
                        touchesOuterEdge = true
                        return
                    }
                    val next = yNext * width + xNext
                    val color = snapshot[next]
                    when {
                        color == target -> Unit
                        color == EMPTY_CELL -> touchesEmpty = true
                        color != target -> {
                            boundaryEdges++
                            val nextComponent = components.labels[next]
                            if (replacement == EMPTY_CELL) {
                                replacement = color
                                replacementComponent = nextComponent
                            } else if (replacement != color ||
                                replacementComponent != nextComponent
                            ) {
                                mixedBoundary = true
                            }
                        }
                    }
                }

                visit(x - 1, y)
                visit(x + 1, y)
                visit(x, y - 1)
                visit(x, y + 1)
            }

            val replacementIsLarger = replacementComponent in components.items.indices &&
                components.items[replacementComponent].cells.size > component.cells.size
            val canReplace = component.cells.size <= maxIslandSize &&
                replacement != EMPTY_CELL &&
                !mixedBoundary &&
                boundaryEdges >= 4 &&
                !touchesOuterEdge &&
                !touchesEmpty &&
                replacementIsLarger
            if (canReplace && !sourceStronglySupportsTarget(
                    component = component.cells,
                    count = component.cells.size,
                    target = target,
                    replacement = replacement,
                    sourceLabs = sourceLabs,
                    paletteLabs = paletteLabs,
                )
            ) {
                component.cells.forEach { cells[it] = replacement }
            }
        }
    }

    private fun labelComponents(
        cells: IntArray,
        width: Int,
        height: Int,
        cancellationCheck: () -> Unit,
    ): ComponentMap {
        val labels = IntArray(cells.size) { -1 }
        val queue = IntArray(cells.size)
        val collected = IntArray(cells.size)
        val components = ArrayList<CellComponent>()
        cells.indices.forEach { start ->
            if (labels[start] >= 0 || cells[start] == EMPTY_CELL) return@forEach
            cancellationCheck()
            val label = components.size
            val color = cells[start]
            var head = 0
            var tail = 0
            var count = 0
            queue[tail++] = start
            labels[start] = label
            while (head < tail) {
                if (count and 0x3FF == 0) cancellationCheck()
                val index = queue[head++]
                collected[count++] = index
                val x = index % width
                val y = index / width
                for (yNext in max(0, y - 1)..min(height - 1, y + 1)) {
                    for (xNext in max(0, x - 1)..min(width - 1, x + 1)) {
                        val next = yNext * width + xNext
                        if (labels[next] < 0 && cells[next] == color) {
                            labels[next] = label
                            queue[tail++] = next
                        }
                    }
                }
            }
            components += CellComponent(color, collected.copyOf(count))
        }
        return ComponentMap(labels, components)
    }

    private fun sourceStronglySupportsTarget(
        component: IntArray,
        count: Int,
        target: Int,
        replacement: Int,
        sourceLabs: Array<Oklab?>?,
        paletteLabs: Array<Oklab>?,
    ): Boolean {
        if (sourceLabs == null || paletteLabs == null) return false
        if (target !in paletteLabs.indices || replacement !in paletteLabs.indices) return false
        var targetAdvantage = 0.0
        var samples = 0
        repeat(count) { componentIndex ->
            val source = sourceLabs[component[componentIndex]] ?: return@repeat
            targetAdvantage += source.distanceSquared(paletteLabs[replacement]) -
                source.distanceSquared(paletteLabs[target])
            samples++
        }
        return samples > 0 && targetAdvantage / samples > DETAIL_PROTECTION_MARGIN
    }

    private class PaletteMatcher(
        private val paletteLabs: Array<Oklab>,
        private val selected: IntArray,
    ) {
        fun nearest(source: Oklab): Int {
            var best = selected.first()
            var bestDistance = Double.POSITIVE_INFINITY
            selected.forEach { paletteIndex ->
                val distance = source.distanceSquared(paletteLabs[paletteIndex])
                if (distance < bestDistance - DISTANCE_EPSILON ||
                    abs(distance - bestDistance) <= DISTANCE_EPSILON && paletteIndex < best
                ) {
                    best = paletteIndex
                    bestDistance = distance
                }
            }
            return best
        }
    }

    private data class PaletteDemand(val lab: Oklab, val weight: Double)

    private data class CellComponent(val color: Int, val cells: IntArray)

    private data class ComponentMap(
        val labels: IntArray,
        val items: List<CellComponent>,
    ) : Iterable<CellComponent> by items

    private class DemandAccumulator {
        var weightedL = 0.0
        var weightedA = 0.0
        var weightedB = 0.0
        var weight = 0.0
        var maxContrast = 0.0

        fun add(lab: Oklab, sampleWeight: Double, contrast: Double) {
            weightedL += lab.l * sampleWeight
            weightedA += lab.a * sampleWeight
            weightedB += lab.b * sampleWeight
            weight += sampleWeight
            maxContrast = max(maxContrast, contrast)
        }

        fun toDemand(extraWeight: Double): PaletteDemand = PaletteDemand(
            lab = Oklab(weightedL / weight, weightedA / weight, weightedB / weight),
            weight = weight + extraWeight,
        )
    }
}
