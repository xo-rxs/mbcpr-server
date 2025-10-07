package mbcpr.server.service;

import mbcpr.server.dto.ProcessedSensorData;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class CprCommunicationService {

    private final Map<String, SseEmitter> appEmitters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SseEmitter createEmitter(String serialNumber) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        emitter.onCompletion(() -> {
            appEmitters.remove(serialNumber);
            log.info("SSE 연결 완료: {}", serialNumber);
        });

        emitter.onTimeout(() -> {
            appEmitters.remove(serialNumber);
            log.info("SSE 연결 타임아웃: {}", serialNumber);
        });

        emitter.onError(e -> {
            appEmitters.remove(serialNumber);
            log.error("SSE 연결 에러: {}", serialNumber, e);
        });

        appEmitters.put(serialNumber, emitter);
        log.info("SSE 연결 생성: {}", serialNumber);

        return emitter;
    }

    public void sendProcessedData(String serialNumber, ProcessedSensorData data) {
        SseEmitter emitter = appEmitters.get(serialNumber);
        if (emitter == null) {
            return;
        }

        try {
            String jsonData = objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event()
                    .name("sensor-data")
                    .data(jsonData));
            log.debug("앱으로 데이터 전송: {} - {}", serialNumber, jsonData);
        } catch (IOException e) {
            log.error("앱으로 데이터 전송 실패: {}", serialNumber, e);
            appEmitters.remove(serialNumber);
        }
    }

    public void closeEmitter(String serialNumber) {
        SseEmitter emitter = appEmitters.remove(serialNumber);
        if (emitter != null) {
            emitter.complete();
            log.info("SSE 연결 종료: {}", serialNumber);
        }
    }

    public boolean hasActiveConnection(String serialNumber) {
        return appEmitters.containsKey(serialNumber);
    }
}