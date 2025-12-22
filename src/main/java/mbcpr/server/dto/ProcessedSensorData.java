package mbcpr.server.dto;

public record ProcessedSensorData(
        double pressure,
        int compressionRate,
        String quality,
        long timestamp
) {}