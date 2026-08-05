package com.example.dq.model

/** scan_chunk 记录 */
data class ChunkRecord(
    val id: Long,
    val scanTableId: Long,
    val seq: Int,
    val rangeStart: String?,
    val rangeEnd: String?,
    val nullChunk: Boolean,
    val status: ScanStatus?,
    val rowCount: Long,
    val colStatsJson: String?,
    val attempts: Int
) {

    fun toRange(): Range {
        return Range(rangeStart, rangeEnd, nullChunk)
    }
}
