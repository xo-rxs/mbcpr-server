package mbcpr.server.service;

import mbcpr.server.dto.SensorData;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class BoardCommunicationService extends TextWebSocketHandler {

    private final Map<String, WebSocketSession> boardSessions = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPingResponse = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SensorDataProcessingService sensorDataProcessingService;
    private final CprCommunicationService cprCommunicationService;

    public BoardCommunicationService(SensorDataProcessingService sensorDataProcessingService,
                                     CprCommunicationService cprCommunicationService) {
        this.sensorDataProcessingService = sensorDataProcessingService;
        this.cprCommunicationService = cprCommunicationService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("=== WebSocket 연결 시도 ===");
        log.info("Session ID: {}", session.getId());
        log.info("Remote Address: {}", session.getRemoteAddress());
        log.info("URI: {}", session.getUri());

        String serialNumber = getSerialNumberFromSession(session);
        if (serialNumber != null) {
            boardSessions.put(serialNumber, session);
            lastPingResponse.put(serialNumber, System.currentTimeMillis());
            log.info("보드 연결 성공: {}", serialNumber);

            // 연결 초기 메시지 전송 (동기화 적용)
            synchronized (session) {
                session.sendMessage(new TextMessage("CONNECTED"));
            }
        } else {
            log.error("Serial Number 누락으로 연결 거부: {}", session.getRemoteAddress());
            session.close(CloseStatus.BAD_DATA);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        String serialNumber = getSerialNumberFromSession(session);

        if (serialNumber == null) return;

        // (타임아웃 방지)
        lastPingResponse.put(serialNumber, System.currentTimeMillis());

        // PONG 처리 등 간단한 메시지는 빠르게 리턴
        if ("PONG".equals(payload)) {
            return;
        }
        if ("YES".equals(payload) || "ACCEPTED".equals(payload) || "STOPPED".equals(payload)) {
            return;
        }

        try {
            SensorData sensorData = objectMapper.readValue(payload, SensorData.class);
            sensorData.setSerialNumber(serialNumber);
            sensorDataProcessingService.processSensorData(sensorData);
        } catch (Exception e) {
            log.error("데이터 처리 실패 [{}]: {}", serialNumber, payload, e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String serialNumber = getSerialNumberFromSession(session);
        if (serialNumber != null) {
            boardSessions.remove(serialNumber);
            lastPingResponse.remove(serialNumber);
            log.info("보드 연결 해제됨: {}", serialNumber);
        }
    }

    public boolean isBoardConnected(String serialNumber) {
        if (!boardSessions.containsKey(serialNumber)) {
            return false;
        }

        Long lastPing = lastPingResponse.get(serialNumber);
        if (lastPing == null) {
            return false;
        }

        // 15초 이상 응답(데이터 포함)이 없으면 연결 끊김으로 판단
        return System.currentTimeMillis() - lastPing < 15000;
    }

    public boolean checkConnectionWithTimeout(String serialNumber, int timeoutSeconds) {
        if (!boardSessions.containsKey(serialNumber)) {
            return false;
        }

        try {
            WebSocketSession session = boardSessions.get(serialNumber);
            if (session.isOpen()) {
                // 동기화 적용
                synchronized (session) {
                    session.sendMessage(new TextMessage("CHECK"));
                }

                long startTime = System.currentTimeMillis();
                while (System.currentTimeMillis() - startTime < timeoutSeconds * 1000) {
                    Thread.sleep(100);
                }
                return true;
            }
        } catch (Exception e) {
            log.error("연결 확인 실패: {}", serialNumber, e);
        }
        return false;
    }

    public boolean startCommunication(String serialNumber) {
        WebSocketSession session = boardSessions.get(serialNumber);
        if (session == null || !session.isOpen()) {
            return false;
        }

        try {
            // 동기화 적용
            synchronized (session) {
                session.sendMessage(new TextMessage("START"));
            }
            return true;
        } catch (IOException e) {
            log.error("통신 시작 요청 실패: {}", serialNumber, e);
            return false;
        }
    }

    public boolean stopCommunication(String serialNumber) {
        WebSocketSession session = boardSessions.get(serialNumber);
        if (session == null || !session.isOpen()) {
            return false;
        }

        try {
            // STOP 메시지 전송 (동기화 적용)
            synchronized (session) {
                session.sendMessage(new TextMessage("STOP"));
            }
            log.info("STOP 메시지 전송: {}", serialNumber);

            // 짧은 대기 후 EXIT 메시지 전송
            Thread.sleep(100);

            // EXIT 메시지 전송 (동기화 적용)
            synchronized (session) {
                session.sendMessage(new TextMessage("EXIT"));
            }
            log.info("EXIT 메시지 전송 (통신 종료 신호): {}", serialNumber);

            return true;
        } catch (Exception e) {
            log.error("통신 중지 요청 실패: {}", serialNumber, e);
            return false;
        }
    }

    @Scheduled(fixedRate = 10000)
    public void sendPingToAllBoards() {
        log.debug("모든 보드에 PING 전송");
        boardSessions.forEach((serialNumber, session) -> {
            if (session.isOpen()) {
                try {
                    // PING 전송 (동기화 적용)
                    synchronized (session) {
                        session.sendMessage(new TextMessage("PING"));
                    }
                    log.debug("PING 전송: {}", serialNumber);
                } catch (IOException e) {
                    log.error("PING 전송 실패: {}", serialNumber, e);
                }
            }
        });
    }

    private String getSerialNumberFromSession(WebSocketSession session) {
        try {
            String query = session.getUri().getQuery();
            if (query != null && query.contains("serial=")) {
                String[] params = query.split("&");
                for (String param : params) {
                    if (param.startsWith("serial=")) {
                        return param.substring(7);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Serial Number 추출 실패", e);
        }
        log.warn("Serial Number를 찾을 수 없습니다");
        return null;
    }
}