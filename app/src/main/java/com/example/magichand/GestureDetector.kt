package com.example.magichand

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

fun calculateDTW(liveData: List<SensorPoint>, templateData: List<SensorPoint>): Float {
    val n = liveData.size
    val m = templateData.size

    // Edge case: If either list is empty, return an impossibly high score
    if (n == 0 || m == 0) return Float.POSITIVE_INFINITY

    // Create the 2D grid filled with Infinity
    // The grid is 1 size larger than the data to allow for the [0][0] starting point
    val grid = Array(n + 1) { FloatArray(m + 1) { Float.POSITIVE_INFINITY } }

    // The starting cost is strictly 0
    grid[0][0] = 0f

    // Loop through the matrix
    for (i in 1..n) {
        for (j in 1..m) {
            // Arrays are 0-indexed, but our grid loop starts at 1
            // So we subtract 1 to get the actual 3D points
            val cost = calculateDistance(liveData[i - 1], templateData[j - 1])

            // Find the lowest cost among the three adjacent cells (Left, Top, Top-Left)
            val minPrevious = min(
                grid[i - 1][j],       // Cell above
                min(
                    grid[i][j - 1],   // Cell to the left
                    grid[i - 1][j - 1] // Cell diagonally top-left
                )
            )

            // Add the current cost to the cheapest previous path
            grid[i][j] = cost + minPrevious
        }
    }

    // The final accumulated DTW distance is located in the very last bottom-right cell
    return grid[n][m]
}

fun classifyGesture(
    liveData: List<SensorPoint>,
    library: List<GestureTemplate>,
    threshold: Float = 150f // You will need to test and adjust this number!
): String {

    var bestMatch = "UNKNOWN"
    var lowestScore = Float.POSITIVE_INFINITY

    // Loop through every saved gesture in the library
    for (template in library) {
        val score = calculateDTW(liveData, template.data)

        // Optional: Print the scores to Logcat so you can calibrate your threshold
        println("DTW Score for ${template.name}: $score")

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