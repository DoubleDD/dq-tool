package com.example.dq.model;

import java.time.LocalDateTime;

/** AI 生成的表说明 */
public record TableDocView(String tableName, String description, String model, LocalDateTime updatedAt) {
}
