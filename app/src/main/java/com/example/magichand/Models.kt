package com.example.magichand

import com.google.gson.annotations.SerializedName

// Data class for /ping response
data class PingResponse(
    @SerializedName("status") val status: String,
    @SerializedName("message") val message: String
)

// Data class for /post request body
data class ActionMapRequest(
    @SerializedName("app_name") val appName: String
)

// Data class for error responses
data class ErrorResponse(
    @SerializedName("error") val error: String
)
