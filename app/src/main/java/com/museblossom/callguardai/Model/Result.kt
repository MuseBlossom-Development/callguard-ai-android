package com.museblossom.callguardai.Model

//data class ServerResponse(
//    val statusCode: Int,
//    val message: String,
//    val now: String,
//    val body: Features
//)
//
//data class Features(
//    val feature_1: List<Double>,
//    val feature_2: List<Double>,
//    val feature_3: Double,
//    val feature_4: Double,
//    val feature_5: Double,
//    val feature_6: Double,
//    val feature_7: Double,
//    val feature_8: Double,
//    val feature_9: Double,
//    val feature_10: Double,
//    val feature_11: Double,
//    val ai_probability: Double
//)
data class ServerResponse(
    val statusCode: Int,
    val message: String,
    val now: String,
    val body: Features
)

data class Features(
    val ai_probability: Int
)