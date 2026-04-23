package com.example.magichand

import kotlin.math.abs
import kotlin.math.sqrt

// Holds a single moment in time from your accelerometer
data class SensorPoint(val x: Float, val y: Float, val z: Float)

// Holds a full saved gesture (e.g., "Swipe") and its list of points
data class GestureTemplate(val name: String, val data: List<SensorPoint>)

/**
 * Calculates the Euclidean distance between two points in 3D space.
 */
fun calculateDistance(p1: SensorPoint, p2: SensorPoint): Float {
    val dx = p2.x - p1.x
    val dy = p2.y - p1.y
    val dz = p2.z - p1.z
    return sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
}

/**
 * Trims silence from the start and end of a list of sensor points.
 * Used for recording gestures, with a higher activity threshold.
 */
fun trimSilenceForRecording(points: List<SensorPoint>): List<SensorPoint> {
    val activityThreshold = 1.0f // Magnitude threshold for "active" movement during recording
    val firstActive = points.indexOfFirst { p -> 
        sqrt((p.x * p.x + p.y * p.y + p.z * p.z).toDouble()) > activityThreshold 
    }
    val lastActive = points.indexOfLast { p -> 
        sqrt((p.x * p.x + p.y * p.y + p.z * p.z).toDouble()) > activityThreshold 
    }
    
    if (firstActive == -1 || lastActive == -1) return emptyList()
    return points.subList(firstActive, lastActive + 1).toList()
}

/**
 * Recommendation 5: Trimming Silence (private helper for classification)
 * Removes points from the start and end where movement is near zero.
 * This ensures the DTW algorithm doesn't get confused by "standing still" time.
 */
private fun trimSilenceForClassification(points: List<SensorPoint>): List<SensorPoint> {
    val activityThreshold = 0.5f // Magnitude threshold for "active" movement during classification
    val firstActive = points.indexOfFirst { p -> 
        sqrt((p.x * p.x + p.y * p.y + p.z * p.z).toDouble()) > activityThreshold 
    }
    val lastActive = points.indexOfLast { p -> 
        sqrt((p.x * p.x + p.y * p.y + p.z * p.z).toDouble()) > activityThreshold 
    }
    
    if (firstActive == -1 || lastActive == -1) return emptyList()
    return points.subList(firstActive, lastActive + 1)
}

/**
 * Recommendation 3: toUnitDirections (Replacing toDeltas)
 * Converts movement into a series of direction vectors with length 1.0.
 * This makes the gesture detection independent of how far/fast the user moved.
 */
private fun toUnitDirections(points: List<SensorPoint>): List<SensorPoint> {
    if (points.size < 2) return emptyList()
    return points.zipWithNext { a, b ->
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dz = b.z - a.z
        val mag = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
        if (mag > 0.1f) { // Noise gate: only record if there was actual movement
            SensorPoint(dx / mag, dy / mag, dz / mag)
        } else {
            SensorPoint(0f, 0f, 0f)
        }
    }
}

/**
 * Feature C: Sakoe-Chiba Band + Recommendation 1 & 4
 * Calculates the Dynamic Time Warping distance between two series of points.
 */
fun calculateDTW(liveData: List<SensorPoint>, templateData: List<SensorPoint>, windowSize: Int = 15): Float {
    val n = liveData.size
    val m = templateData.size

    if (n == 0 || m == 0) return Float.POSITIVE_INFINITY

    // Create the 2D grid filled with Infinity
    val grid = Array(n + 1) { FloatArray(m + 1) { Float.POSITIVE_INFINITY } }
    grid[0][0] = 0f

    // Sakoe-Chiba constraint window
    val window = maxOf(windowSize, abs(n - m))

    for (i in 1..n) {
        val start = maxOf(1, i - window)
        val end = minOf(m, i + window)
        for (j in start..end) {
            val cost = calculateDistance(liveData[i - 1], templateData[j - 1])

            // Recommendation 4: Slope Constraint
            // Penalize horizontal and vertical steps to favor the diagonal (perfect timing).
            val minPrevious = minOf(
                grid[i - 1][j] + 0.1f,       // Top (Stalling live)
                grid[i][j - 1] + 0.1f,       // Left (Stalling template)
                grid[i - 1][j - 1]           // Diagonal (Match)
            )

            if (minPrevious != Float.POSITIVE_INFINITY) {
                grid[i][j] = cost + minPrevious
            }
        }
    }

    // Recommendation 1: Normalize by Average Cost (Length of path)
    // This removes the bias towards shorter gestures.
    return grid[n][m] / (n + m)
}

/**
 * Machine Learning Classification Logic
 */
fun classifyGesture(
    liveData: List<SensorPoint>,
    library: List<GestureTemplate>,
    threshold: Float = 1.0f // Tuned for normalized Unit Directional data
): String {

    // Recommendation 5: Trim silence from the live buffer
    val trimmedLive = trimSilenceForClassification(liveData)
    val processedLive = toUnitDirections(trimmedLive)
    if (processedLive.isEmpty()) return "UNKNOWN"

    var bestMatch = "UNKNOWN"
    var lowestScore = Float.POSITIVE_INFINITY

    for (template in library) {
        // Recommendation 5: Trim silence from the templates
        val trimmedTemplate = trimSilenceForClassification(template.data)
        val processedTemplate = toUnitDirections(trimmedTemplate)
        
        if (processedTemplate.isEmpty()) continue

        val score = calculateDTW(processedLive, processedTemplate)

        if (score < lowestScore) {
            lowestScore = score
            bestMatch = template.name
        }
    }

    // Return the name if it's within the confidence threshold
    if (lowestScore > threshold) {
        return "UNKNOWN"
    }

    return bestMatch
}
