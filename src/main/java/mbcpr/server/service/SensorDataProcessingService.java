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

    // 속도(Rate) 기준
    private static final int RATE_MIN = 100;
    private static final int RATE_MAX = 120;


    private static final double DEPTH_THRESHOLD_MIN = 12.0;
    private static final double DEPTH_THRESHOLD_GOOD = 18.0;
    private static final double DEPTH_THRESHOLD_MAX = 22.0;

    public void processSensorData(SensorData sensorData) {
        String serialNumber = sensorData.getSerialNumber();

        if (!isProcessingActive(serialNumber)) {
            return;
        }

        double currentPressure = sensorData.getPressure();

        // 1. 속도(BPM) 계산 (초반 1분 미만 보정 로직 포함)
        int compressionRate = calculateCompressionRate(serialNumber, currentPressure);

        // 2. 깊이 및 속도 상태 개별 판정
        String depthStatus = evaluateDepthQuality(currentPressure);
        String rateStatus = evaluateRateQuality(compressionRate);

        // 3. 결과 DTO 생성 (상태값 분리됨)
        ProcessedSensorData processedData = new ProcessedSensorData(
                currentPressure,
                compressionRate,
                depthStatus,
                rateStatus,
                System.currentTimeMillis()
        );

        log.debug("Data: {} - Rate: {}, DepthStat: {}, RateStat: {}",
                serialNumber, compressionRate, depthStatus, rateStatus);

        cprCommunicationService.sendProcessedData(serialNumber, processedData);
    }

    /**
     * 압박 주기(BPM) 계산
     * - 1분 미만일 경우: (횟수 / 경과시간) * 60 공식으로 환산하여 즉각적인 속도 반영
     */
    private int calculateCompressionRate(String serialNumber, double currentPressure) {
        Queue<Long> timestamps = compressionTimestamps.computeIfAbsent(
                serialNumber, k -> new LinkedList<>()
        );

        Double prevPressure = lastPressure.get(serialNumber);

        // [압박 감지 로직]
        // 1. 최소 깊이(12) 이상이고
        // 2. 이전 값보다 5 이상 급격히 상승했을 때 압박으로 인정
        if (prevPressure != null &&
                currentPressure >= DEPTH_THRESHOLD_MIN &&
                currentPressure > prevPressure + 5.0) {

            long currentTime = System.currentTimeMillis();

            // 노이즈 제거: 0.3초 이내 연속 감지 무시
            if (!timestamps.isEmpty() && currentTime - timestamps.peek() < 300) {
                lastPressure.put(serialNumber, currentPressure);
                return calculateBpmFromQueue(timestamps, currentTime);
            }

            timestamps.offer(currentTime);
        }

        // 1분(60초) 지난 데이터 제거
        long currentTime = System.currentTimeMillis();
        while (!timestamps.isEmpty() && currentTime - timestamps.peek() > 60000) {
            timestamps.poll();
        }

        lastPressure.put(serialNumber, currentPressure);

        return calculateBpmFromQueue(timestamps, currentTime);
    }

    /**
     * 큐 데이터를 기반으로 BPM 계산 (보정 로직 적용)
     */
    private int calculateBpmFromQueue(Queue<Long> timestamps, long currentTime) {
        int count = timestamps.size();

        // 데이터가 2개 미만이면 간격 계산 불가 -> 0 리턴
        if (count < 2) {
            return 0;
        }

        long firstTime = timestamps.peek();
        long durationSeconds = (currentTime - firstTime) / 1000; // 경과 시간(초)

        if (durationSeconds >= 5 && durationSeconds < 60) {
            return (int) ((count / (double) durationSeconds) * 60);
        }

        return count;
    }

    /**
     * 깊이 품질 평가
     */
    private String evaluateDepthQuality(double pressure) {
        if (pressure < DEPTH_THRESHOLD_MIN) {
            return "waiting";      // 12 미만
        } else if (pressure < DEPTH_THRESHOLD_GOOD) {
            return "too_shallow";  // 12 ~ 17.9
        } else if (pressure < DEPTH_THRESHOLD_MAX) {
            return "good";         // 18 ~ 21.9
        } else {
            return "too_deep";     // 22 이상
        }
    }

    /**
     * 속도 품질 평가
     */
    private String evaluateRateQuality(int bpm) {
        if (bpm == 0) {
            return "waiting";
        } else if (bpm < RATE_MIN) {
            return "too_slow";     // 100 미만
        } else if (bpm > RATE_MAX) {
            return "too_fast";     // 120 초과
        } else {
            return "good";         // 100 ~ 120
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