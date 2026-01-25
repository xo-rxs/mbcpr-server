package mbcpr.server.service;

import mbcpr.server.dto.ProcessedSensorData;
import mbcpr.server.dto.SensorData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
@Slf4j
@Service
@RequiredArgsConstructor
public class SensorDataProcessingService {

    private final CprCommunicationService cprCommunicationService;

    private final Map<String, Boolean> activeProcessing = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCompressionTime = new ConcurrentHashMap<>();
    private final Map<String, LinkedList<Double>> pressureWindow = new ConcurrentHashMap<>();

    private static final int WINDOW_SIZE = 3;
    private static final double MIN_VALID_PRESSURE = 5.0;

    // 수정된 깊이 기준값
    private static final double DEPTH_MIN = 12.0;
    private static final double DEPTH_APPROPRIATE = 18.0;

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

        // 피크 탐지: 이전 값들보다 크고 하강하기 시작하는 시점
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
            if (interval < 250) return; // 노이즈 필터링

            bpm = (int) (60000 / interval);
            rateStatus = evaluateRateByInterval(interval);
        }

        lastCompressionTime.put(serialNumber, currentTime);

        // 수정된 깊이 판정 호출
        String depthStatus = evaluateDepthQuality(peakPressure);

        ProcessedSensorData processedData = new ProcessedSensorData(
                peakPressure, bpm, depthStatus, rateStatus, currentTime
        );

        log.info("Peak: {} - Depth: {} ({}), BPM: {} ({})",
                serialNumber, peakPressure, depthStatus, bpm, rateStatus);

        cprCommunicationService.sendProcessedData(serialNumber, processedData);
    }

    /**
     * 수정된 깊이 품질 평가 로직
     */
    private String evaluateDepthQuality(double pressure) {
        if (pressure < DEPTH_MIN) {
            return "too_shallow"; // 12 미만
        } else if (pressure <= DEPTH_APPROPRIATE) {
            return "good";        // 12 이상 ~ 18 이하 (적정)
        } else {
            return "too_deep";    // 18 초과 (깊음)
        }
    }

    private String evaluateRateByInterval(long intervalMs) {
        if (intervalMs >= 500 && intervalMs <= 600) return "good";
        return (intervalMs < 500) ? "too_fast" : "too_slow";
    }

    public void startProcessing(String serialNumber) {
        activeProcessing.put(serialNumber, true);
        pressureWindow.put(serialNumber, new LinkedList<>());
        lastCompressionTime.remove(serialNumber);
        log.info("CPR 측정 시작: {}", serialNumber);
    }

    public void stopProcessing(String serialNumber) {
        activeProcessing.put(serialNumber, false);
        pressureWindow.remove(serialNumber);
        lastCompressionTime.remove(serialNumber);
        log.info("CPR 측정 종료: {}", serialNumber);
    }

    public boolean isProcessingActive(String serialNumber) {
        return activeProcessing.getOrDefault(serialNumber, false);
    }
}