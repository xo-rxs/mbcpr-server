package mbcpr.server.service;

import mbcpr.server.dto.ProcessedSensorData;
import mbcpr.server.dto.SensorData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class SensorDataProcessingService {

    private final CprCommunicationService cprCommunicationService;
    private final Map<String, Boolean> activeProcessing = new ConcurrentHashMap<>();
    private final Map<String, Queue<Long>> compressionTimestamps = new ConcurrentHashMap<>();
    private final Map<String, Double> lastPressure = new ConcurrentHashMap<>();

    // CPR 가이드라인 기준 (대한심폐소생협회 기준)
    private static final double MIN_DEPTH = 5.0; // cm (최소 깊이)
    private static final double MAX_DEPTH = 6.0; // cm (최대 깊이)
    private static final int MIN_RATE = 100; // 분당 압박 횟수 (최소)
    private static final int MAX_RATE = 120; // 분당 압박 횟수 (최대)

    // 압력 센서 설정
    private static final double PRESSURE_THRESHOLD = 10.0; // 압박 감지 임계값
    private static final double PRESSURE_TO_DEPTH_RATIO = 0.12; // 압력-깊이 변환 계수 (실험으로 보정 필요)

    public void processSensorData(SensorData sensorData) {
        String serialNumber = sensorData.getSerialNumber();

        if (!isProcessingActive(serialNumber)) {
            return;
        }

        // 압력 센서로 압박 깊이 계산
        double depth = calculateDepthFromPressure(sensorData.getPressure());

        // 압력 변화로 압박 주기 계산
        int compressionRate = calculateCompressionRate(serialNumber, sensorData.getPressure());

        ProcessedSensorData processedData = new ProcessedSensorData();
        processedData.setPressure(sensorData.getPressure());
        processedData.setCompressionRate(compressionRate);
        processedData.setTimestamp(System.currentTimeMillis());

        // 품질 평가
        String quality = evaluateQuality(depth, compressionRate);
        processedData.setQuality(quality);

        log.debug("센서 데이터 정제 완료: {} - Pressure: {}, Depth: {}, Rate: {}, Quality: {}",
                serialNumber, sensorData.getPressure(), depth, compressionRate, quality);

        // 앱으로 전송
        cprCommunicationService.sendProcessedData(serialNumber, processedData);
    }

    /**
     * 압력 센서 값으로 압박 깊이 계산
     */
    private double calculateDepthFromPressure(double pressure) {
        // 압력값을 깊이(cm)로 변환
        double depth = pressure * PRESSURE_TO_DEPTH_RATIO;

        // 0~10cm 범위로 제한
        return Math.max(0, Math.min(depth, 10.0));
    }

    /**
     * 압력 변화로 압박 주기 계산 (분당 횟수)
     */
    private int calculateCompressionRate(String serialNumber, double currentPressure) {
        Queue<Long> timestamps = compressionTimestamps.computeIfAbsent(
                serialNumber, k -> new LinkedList<>()
        );

        Double prevPressure = lastPressure.get(serialNumber);

        // 압력이 임계값을 넘고, 이전보다 증가했으면 압박으로 감지
        if (prevPressure != null &&
                currentPressure > PRESSURE_THRESHOLD &&
                currentPressure > prevPressure + 5.0) { // 급격한 압력 증가 감지

            long currentTime = System.currentTimeMillis();

            // 너무 짧은 간격(0.3초 미만) 압박은 무시 (노이즈 제거)
            if (!timestamps.isEmpty() && currentTime - timestamps.peek() < 300) {
                lastPressure.put(serialNumber, currentPressure);
                return timestamps.size();
            }

            timestamps.offer(currentTime);

            // 60초(1분) 이상 된 데이터 제거
            while (!timestamps.isEmpty() &&
                    currentTime - timestamps.peek() > 60000) {
                timestamps.poll();
            }
        }

        lastPressure.put(serialNumber, currentPressure);

        // 최근 1분간의 압박 횟수 = 분당 압박 횟수
        return timestamps.size();
    }

    private String evaluateQuality(double depth, int rate) {
        if (depth < MIN_DEPTH) {
            return "too_shallow";
        } else if (depth > MAX_DEPTH) {
            return "too_deep";
        } else if (rate < MIN_RATE) {
            return "too_slow";
        } else if (rate > MAX_RATE) {
            return "too_fast";
        } else {
            return "good";
        }
    }

    public void startProcessing(String serialNumber) {
        activeProcessing.put(serialNumber, true);
        compressionTimestamps.put(serialNumber, new LinkedList<>());
        lastPressure.put(serialNumber, 0.0);
        log.info("센서 데이터 처리 시작: {}", serialNumber);
    }

    public void stopProcessing(String serialNumber) {
        activeProcessing.put(serialNumber, false);
        compressionTimestamps.remove(serialNumber);
        lastPressure.remove(serialNumber);
        log.info("센서 데이터 처리 중지: {}", serialNumber);
    }

    public boolean isProcessingActive(String serialNumber) {
        return activeProcessing.getOrDefault(serialNumber, false);
    }
}