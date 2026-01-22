package mbcpr.server.dto;

public record ProcessedSensorData(
        double pressure,        // 현재 압력(깊이) 값
        int compressionRate,    // 현재 분당 압박 횟수
        String depthStatus,     // 깊이 판정
        String rateStatus,      // 속도 판정
        long timestamp
) {}