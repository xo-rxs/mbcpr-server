package mbcpr.server.service;

import mbcpr.server.dto.ProcessedSensorData;
import mbcpr.server.dto.SensorData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;@Slf4j

@Service
@RequiredArgsConstructor
public class SensorDataProcessingService {

    private final CprCommunicationService cprCommunicationService;

    private final Map<String, Boolean> activeProcessing = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCompressionTime = new ConcurrentHashMap<>();
    private final Map<String, LinkedList<Double>> pressureWindow = new ConcurrentHashMap<>();

    private static final int WINDOW_SIZE = 3;
    private static final double MIN_VALID_PRESSURE = 5.0;
    private static final long INACTIVITY_THRESHOLD = 2000; // 1.5초 동안 압박 없으면 경고

    private static final double DEPTH_MIN = 12.0;
    private static final double DEPTH_APPROPRIATE = 18.0;

    // 1. 주기적으로 압박 부재 확인 (0.5초마다 실행)
/*    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 500)
    public void checkInactivity() {
        long currentTime = System.currentTimeMillis();

        activeProcessing.forEach((serialNumber, isActive) -> {
            if (isActive) {
                Long lastTime = lastCompressionTime.get(serialNumber);
                // 마지막 압박으로부터 1.5초가 지났다면
                if (lastTime != null && (currentTime - lastTime) > INACTIVITY_THRESHOLD) {
                    sendInactivityWarning(serialNumber);
                    // 경고를 보낸 후 시간을 갱신하여 연속으로 메시지가 날아가지 않게 함 (선택 사항)
                    lastCompressionTime.put(serialNumber, currentTime);
                }
            }
        });
    }

    private void sendInactivityWarning(String serialNumber) {
        log.warn("Inactivity detected for device: {}", serialNumber);
        ProcessedSensorData warningData = new ProcessedSensorData(
                0.0,            // 압력 0
                0,              // BPM 0
                "too_shallow",  // 안 누르고 있으므로 부족 상태
                "waiting",      // 속도 대기
                System.currentTimeMillis()
        );
        cprCommunicationService.sendProcessedData(serialNumber, warningData);
    }*/

    public void processSensorData(SensorData sensorData) {
        String serialNumber = sensorData.getSerialNumber();
        if (!isProcessingActive(serialNumber)) return;

        double currentPressure = sensorData.getPressure();
        LinkedList<Double> window = pressureWindow.computeIfAbsent(serialNumber, k -> new LinkedList<>());

        window.add(currentPressure);
        if (window.size() > WINDOW_SIZE) window.removeFirst();
        if (window.size() < WINDOW_SIZE) return;

        double p0 = window.get(0);
        double p1 = window.get(1);
        double p2 = window.get(2);

        if (p1 > p0 && p1 > p2 && p1 >= MIN_VALID_PRESSURE) {
            handleCompressionPeak(serialNumber, p1);
        }
    }

    private void handleCompressionPeak(String serialNumber, double peakPressure) {
        long currentTime = System.currentTimeMillis();
        Long lastTime = lastCompressionTime.get(serialNumber);

        int bpm = 0;
        String rateStatus = "waiting";

        if (lastTime != null) {
            long interval = currentTime - lastTime;
            bpm = (int) (60000 / interval);
            rateStatus = evaluateRateByInterval(interval);
        }

        lastCompressionTime.put(serialNumber, currentTime);
        String depthStatus = evaluateDepthQuality(peakPressure);

        ProcessedSensorData processedData = new ProcessedSensorData(
                peakPressure, bpm, depthStatus, rateStatus, currentTime
        );

        cprCommunicationService.sendProcessedData(serialNumber, processedData);
    }

    private String evaluateDepthQuality(double pressure) {
        if (pressure < DEPTH_MIN) return "too_shallow";
        else if (DEPTH_APPROPRIATE <= pressure&& pressure <= 22) return "good";
        else return "too_deep";
    }

    private String evaluateRateByInterval(long intervalMs) {
        if (intervalMs >= 300 && intervalMs <= 600) return "good";
        return (intervalMs < 300) ? "too_fast" : "too_slow";
    }

    public void startProcessing(String serialNumber) {
        activeProcessing.put(serialNumber, true);
        pressureWindow.put(serialNumber, new LinkedList<>());
        lastCompressionTime.put(serialNumber, System.currentTimeMillis()); // 시작 시각 기록
    }

    public void stopProcessing(String serialNumber) {
        activeProcessing.put(serialNumber, false);
        pressureWindow.remove(serialNumber);
        lastCompressionTime.remove(serialNumber);
    }

    public boolean isProcessingActive(String serialNumber) {
        return activeProcessing.getOrDefault(serialNumber, false);
    }
}