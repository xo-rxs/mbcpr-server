package mbcpr.server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedSensorData {
    private double pressure;         // 압력 센서 값
    private int compressionRate;     // 계산된 분당 압박 횟수
    private String quality;          // "good", "too_slow", "too_fast"
    private long timestamp;
}