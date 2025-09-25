package com.example.androidcodeclassifier


data class HvLookupRaw(
    val opcodes: Map<String, List<Int>> = emptyMap(),
    val methods: Map<String, List<Int>> = emptyMap()
)


data class HvLookupFloat(
    val opcodes: Map<String, FloatArray>,
    val methods: Map<String, FloatArray>,
    val dimension: Int
)