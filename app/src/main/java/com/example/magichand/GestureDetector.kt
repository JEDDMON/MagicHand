package com.example.magichand

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

// Holds a single moment in time from your accelerometer
data class SensorPoint(val x: Float, val y: Float, val z: Float)

// Holds a full saved gesture (e.g., "Swipe") and its list of points
data class GestureTemplate(val name: String, val data: List<SensorPoint>)

fun calculateDistance(p1: SensorPoint, p2: SensorPoint): Float {
    val dx = p2.x - p1.x
    val dy = p2.y - p1.y
    val dz = p2.z - p1.z
    // We convert to Double for the sqrt, then back to Float for Android
    return sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
}

/**
 * Feature A: Data Normalization
 * Normalizes the points so that the maximum amplitude in any direction is 1.0.
 * This ensures that a "slow swipe" and a "fast swipe" look similar to the algorithm.
 */
private fun normalizeGesture(points: List<SensorPoint>): List<SensorPoint> {
    if (points.isEmpty()) return points
    var maxVal = 0.001f // Avoid division by zero
    for (p in points) {
        val currentMax = maxOf(abs(p.x), abs(p.y), abs(p.z))
        if (currentMax > maxVal) maxVal = currentMax
    }
    return points.map { SensorPoint(it.x / maxVal, it.y / maxVal, it.z / maxVal) }
}

/**
 * Feature B: Axis Invariance (via Deltas)
 * Converts absolute sensor values into the change (delta) between points.
 * This helps focus on the *shape* of the movement rather than the absolute orientation.
 */
private fun toDeltas(points: List<SensorPoint>): List<SensorPoint> {
    if (points.size < 2) return points
    return points.zipWithNext { a, b ->
        SensorPoint(b.x - a.x, b.y - a.y, b.z - a.z)
    }
}

/**
 * Feature C: Sakoe-Chiba Band
 * Restricts the DTW search to a window around the diagonal to improve performance
 * and prevent "garbage" matches (e.g., matching the start of a gesture to the end).
 */
fun calculateDTW(liveData: List<SensorPoint>, templateData: List<SensorPoint>, windowSize: Int = 15): Float {
    val n = liveData.size
    val m = templateData.size

    // Edge case: If either list is empty, return an impossibly high score
    if (n == 0 || m == 0) return Float.POSITIVE_INFINITY

    // Create the 2D grid filled with Infinity
    val grid = Array(n + 1) { FloatArray(m + 1) { Float.POSITIVE_INFINITY } }

    // The starting cost is strictly 0
    grid[0][0] = 0f

    // Calculate the Sakoe-Chiba window constraint
    val window = maxOf(windowSize, abs(n - m))

    // Loop through the matrix within the Sakoe-Chiba band
    for (i in 1..n) {
        val start = maxOf(1, i - window)
        val end = minOf(m, i + window)
        for (j in start..end) {
            val cost = calculateDistance(liveData[i - 1], templateData[j - 1])

            // Find the lowest cost among the three adjacent cells (Left, Top, Top-Left)
            val minPrevious = minOf(
                grid[i - 1][j],       // Top
                grid[i][j - 1],       // Left
                grid[i - 1][j - 1]    // Diagonal
            )

            if (minPrevious != Float.POSITIVE_INFINITY) {
                grid[i][j] = cost + minPrevious
            }
        }
    }

    // Return the final accumulated DTW distance
    return grid[n][m]
}

fun classifyGesture(
    liveData: List<SensorPoint>,
    library: List<GestureTemplate>,
    threshold: Float = 60f // Adjusted threshold for normalized delta data
): String {

    // Pre-process live data: Convert to deltas and normalize scale
    val processedLive = normalizeGesture(toDeltas(liveData))
    if (processedLive.isEmpty()) return "UNKNOWN"

    var bestMatch = "UNKNOWN"
    var lowestScore = Float.POSITIVE_INFINITY

    // Loop through every saved gesture in the library
    for (template in library) {
        // Pre-process template data: Convert to deltas and normalize scale
        val processedTemplate = normalizeGesture(toDeltas(template.data))
        
        val score = calculateDTW(processedLive, processedTemplate)

        if (score < lowestScore) {
            lowestScore = score
            bestMatch = template.name
        }
    }

    // Reject it if even the "best" match is still too messy
    if (lowestScore > threshold) {
        return "UNKNOWN"
    }

    return bestMatch
}
