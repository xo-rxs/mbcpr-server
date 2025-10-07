package mbcpr.server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SensorData {
    private String serialNumber;
    private double pressure;  // 압력 센서 값
    private long timestamp;
}