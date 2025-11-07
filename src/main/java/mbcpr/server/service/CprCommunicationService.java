package mbcpr.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import mbcpr.server.dto.ProcessedSensorData;
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
        // 기존 연결이 있으면 종료
        SseEmitter oldEmitter = appEmitters.get(serialNumber);
        if (oldEmitter != null) {
            try {
                oldEmitter.complete();
            } catch (Exception e) {
                log.warn("기존 Emitter 종료 실패: {}", serialNumber);
            }
        }

        // 새 Emitter 생성 (타임아웃 30분)
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

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
        log.info("SSE Emitter 생성 및 저장 완료: {}", serialNumber);

        // 연결 확인용 초기 메시지 전송
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("SSE 연결 성공"));
            log.info("SSE 초기 메시지 전송 완료: {}", serialNumber);
        } catch (IOException e) {
            log.error("SSE 초기 메시지 전송 실패: {}", serialNumber, e);
            appEmitters.remove(serialNumber);
        }

        return emitter;
    }

    public void sendProcessedData(String serialNumber, ProcessedSensorData data) {
        SseEmitter emitter = appEmitters.get(serialNumber);
        if (emitter == null) {
            log.debug("SSE Emitter 없음, 데이터 전송 스킵: {}", serialNumber);
            return;
        }

        try {
            String jsonData = objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event()
                    .name("sensor-data")
                    .data(jsonData));
            log.debug("앱으로 데이터 전송 성공: {} - {}", serialNumber, jsonData);
        } catch (IOException e) {
            log.error("앱으로 데이터 전송 실패: {}", serialNumber, e);
            appEmitters.remove(serialNumber);
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {}
        }
    }

    public void closeEmitter(String serialNumber) {
        SseEmitter emitter = appEmitters.remove(serialNumber);
        if (emitter != null) {
            try {
                emitter.complete();
                log.info("SSE 연결 정상 종료: {}", serialNumber);
            } catch (Exception e) {
                log.warn("SSE 연결 종료 실패: {}", serialNumber, e);
            }
        }
    }

    public boolean hasActiveConnection(String serialNumber) {
        return appEmitters.containsKey(serialNumber);
    }
}