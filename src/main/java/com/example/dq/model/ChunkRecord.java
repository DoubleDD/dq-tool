package com.example.dq.model;

/** scan_chunk 记录 */
public record ChunkRecord(
        long id,
        long scanTableId,
        int seq,
        String rangeStart,
        String rangeEnd,
        boolean nullChunk,
        ScanStatus status,
        long rowCount,
        String colStatsJson,
        int attempts
) {

    public Range toRange() {
        return new Range(rangeStart, rangeEnd, nullChunk);
    }
}
