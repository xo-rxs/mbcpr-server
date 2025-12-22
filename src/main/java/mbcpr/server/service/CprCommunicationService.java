package mbcpr.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import mbcpr.server.dto.ProcessedSensorData;
import org.springframework.scheduling.annotation.Scheduled;
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
    private static final long TIMEOUT = 60 * 60 * 1000L; // 1시간 (필요시 조정)

    public SseEmitter createEmitter(String serialNumber) {
        // 기존 연결 정리
        if (appEmitters.containsKey(serialNumber)) {
            closeEmitter(serialNumber);
        }

        // 새 Emitter 생성
        SseEmitter emitter = new SseEmitter(TIMEOUT);

        // 콜백 설정
        emitter.onCompletion(() -> {
            log.info("SSE 연결 완료(Completion): {}", serialNumber);
            appEmitters.remove(serialNumber);
        });

        emitter.onTimeout(() -> {
            log.warn("SSE 연결 타임아웃: {}", serialNumber);
            emitter.complete(); // 타임아웃 시 complete 호출하여 종료 처리
            appEmitters.remove(serialNumber);
        });

        emitter.onError(e -> {
            log.error("SSE 연결 에러: {}", serialNumber, e);
            emitter.completeWithError(e);
            appEmitters.remove(serialNumber);
        });

        appEmitters.put(serialNumber, emitter);
        log.info("SSE Emitter 생성됨: {}", serialNumber);

        // 초기 연결 확인 메시지 전송
        sendToEmitter(emitter, serialNumber, "connected", "SSE 연결 성공");

        return emitter;
    }

    public void sendProcessedData(String serialNumber, ProcessedSensorData data) {
        SseEmitter emitter = appEmitters.get(serialNumber);
        if (emitter != null) {
            try {
                String jsonData = objectMapper.writeValueAsString(data);
                sendToEmitter(emitter, serialNumber, "sensor-data", jsonData);
            } catch (IOException e) {
                log.error("JSON 변환 실패: {}", serialNumber, e);
            }
        }
    }

    // 동기화된 전송 메서드 (중요!)
    private void sendToEmitter(SseEmitter emitter, String serialNumber, String eventName, Object data) {
        synchronized (emitter) { // SseEmitter는 Thread-Safe하지 않으므로 동기화 필수
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data));
            } catch (IOException e) {
                log.warn("데이터 전송 실패 (클라이언트 연결 끊김 추정): {}", serialNumber);
                appEmitters.remove(serialNumber);
                emitter.completeWithError(e);
            } catch (Exception e) {
                log.error("전송 중 예기치 않은 오류: {}", serialNumber, e);
                appEmitters.remove(serialNumber);
                emitter.complete();
            }
        }
    }

    public void closeEmitter(String serialNumber) {
        SseEmitter emitter = appEmitters.remove(serialNumber);
        if (emitter != null) {
            try {
                emitter.complete();
                log.info("SSE 강제 종료: {}", serialNumber);
            } catch (Exception ignored) {}
        }
    }

    // Heartbeat: 30초마다 빈 데이터를 보내 연결 유지 (Nginx 등의 타임아웃 방지)
    @Scheduled(fixedRate = 30000)
    public void sendHeartbeat() {
        appEmitters.forEach((serial, emitter) -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException e) {
                // Heartbeat 실패 시 조용히 정리
                appEmitters.remove(serial);
                emitter.completeWithError(e);
            }
        });
    }

    public boolean hasActiveConnection(String serialNumber) {
        return appEmitters.containsKey(serialNumber);
    }
}