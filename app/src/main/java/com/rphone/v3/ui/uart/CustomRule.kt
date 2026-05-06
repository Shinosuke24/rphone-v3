package com.rphone.v3.ui.uart

import java.util.UUID

data class CustomRule(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val pattern: String,        // regex string
    val group: Int = 0,         // capture group index (0 = full match)
    val status: String = "NORMAL" // "NORMAL" | "OK" | "WARNING" | "ERROR"
) {
    fun toStatus(): ParsedItemStatus = when (status.uppercase()) {
        "OK"      -> ParsedItemStatus.OK
        "WARNING" -> ParsedItemStatus.WARNING
        "ERROR"   -> ParsedItemStatus.ERROR
        else      -> ParsedItemStatus.NORMAL
    }
}
