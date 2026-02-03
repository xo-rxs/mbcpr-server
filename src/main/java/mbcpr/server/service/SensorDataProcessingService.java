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
    private final Map<String, LinkedList<Integer>> bpmHistory = new ConcurrentHashMap<>();

    private static final int WINDOW_SIZE = 3;
    private static final double MIN_VALID_PRESSURE = 5.0;
    private static final int BPM_AVG_WINDOW = 5; // 최근 5개 압박의 평균 사용

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

        if (p1 > p0 && p1 > p2 && p1 >= MIN_VALID_PRESSURE) {
            handleCompressionPeak(serialNumber, p1);
        }
    }

    private void handleCompressionPeak(String serialNumber, double peakPressure) {
        long currentTime = System.currentTimeMillis();
        Long lastTime = lastCompressionTime.get(serialNumber);

        int finalBpm = 0;
        String rateStatus = "waiting";

        if (lastTime != null) {
            long interval = lastTime - currentTime;

            if(interval < 300){
                return;
            }
            int currentRawBpm = (int) (60000 / interval);

            LinkedList<Integer> history = bpmHistory.computeIfAbsent(serialNumber, k -> new LinkedList<>());
            history.add(currentRawBpm);

            if (history.size() > BPM_AVG_WINDOW) {
                history.removeFirst();
            }

            double avgVal = history.stream().mapToInt(Integer::intValue).average().orElse(0);
            finalBpm = (int) avgVal;

            rateStatus = evaluateRateByBpm(finalBpm);
        }


        lastCompressionTime.put(serialNumber, currentTime);

        String depthStatus = evaluateDepthQuality(peakPressure);

        ProcessedSensorData processedData = new ProcessedSensorData(
                peakPressure, finalBpm, depthStatus, rateStatus, currentTime
        );

        cprCommunicationService.sendProcessedData(serialNumber, processedData);
    }

    private String evaluateDepthQuality(double pressure) {
        if (pressure < DEPTH_MIN) return "too_shallow";
        else if (DEPTH_APPROPRIATE <= pressure&& pressure <= 22) return "good";
        else return "too_deep";
    }

    private String evaluateRateByBpm(int bpm) {
        if (bpm >= 100 && bpm <= 120) return "good";
        return (bpm > 120) ? "too_fast" : "too_slow";
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
        bpmHistory.remove(serialNumber); // [추가] 통신 종료 시 기록 삭제
    }

    public boolean isProcessingActive(String serialNumber) {
        return activeProcessing.getOrDefault(serialNumber, false);
    }
}