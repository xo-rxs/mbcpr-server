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

            // 연결 확인 메시지 전송
            session.sendMessage(new TextMessage("CONNECTED"));
        } else {
            log.error("❌ Serial Number가 없어서 연결 실패");
            session.close();
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        String serialNumber = getSerialNumberFromSession(session);

        log.info("보드로부터 메시지 수신: {} - {}", serialNumber, payload);

        if ("PONG".equals(payload)) {
            lastPingResponse.put(serialNumber, System.currentTimeMillis());
            return;
        }

        if ("YES".equals(payload)) {
            // 연결 확인 응답
            return;
        }

        if ("ACCEPTED".equals(payload)) {
            // 통신 시작 수락 응답
            return;
        }

        if ("STOPPED".equals(payload)) {
            // 통신 중지 완료 응답
            return;
        }

        // 센서 데이터로 처리
        try {
            SensorData sensorData = objectMapper.readValue(payload, SensorData.class);
            sensorData.setSerialNumber(serialNumber);
            sensorDataProcessingService.processSensorData(sensorData);
        } catch (Exception e) {
            log.error("센서 데이터 파싱 실패: {}", payload, e);
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

        // 15초 이상 응답이 없으면 연결 끊김으로 판단
        return System.currentTimeMillis() - lastPing < 15000;
    }

    public boolean checkConnectionWithTimeout(String serialNumber, int timeoutSeconds) {
        if (!boardSessions.containsKey(serialNumber)) {
            return false;
        }

        try {
            WebSocketSession session = boardSessions.get(serialNumber);
            session.sendMessage(new TextMessage("CHECK"));

            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < timeoutSeconds * 1000) {
                Thread.sleep(100);
                // 여기서는 간단하게 처리하고, 실제로는 응답을 기다리는 로직 필요
            }
            return true;
        } catch (Exception e) {
            log.error("연결 확인 실패: {}", serialNumber, e);
            return false;
        }
    }

    public boolean startCommunication(String serialNumber) {
        WebSocketSession session = boardSessions.get(serialNumber);
        if (session == null || !session.isOpen()) {
            return false;
        }

        try {
            session.sendMessage(new TextMessage("START"));
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
            // STOP 메시지 전송
            session.sendMessage(new TextMessage("STOP"));
            log.info("STOP 메시지 전송: {}", serialNumber);

            // 짧은 대기 후 EXIT 메시지 전송
            Thread.sleep(100);
            session.sendMessage(new TextMessage("EXIT"));
            log.info("EXIT 메시지 전송 (통신 종료 신호): {}", serialNumber);

            return true;
        } catch (Exception e) {
            log.error("통신 중지 요청 실패: {}", serialNumber, e);
            return false;
        }
    }

    @Scheduled(fixedRate = 10000) // 10초마다 실행
    public void sendPingToAllBoards() {
        log.debug("모든 보드에 PING 전송");
        boardSessions.forEach((serialNumber, session) -> {
            if (session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage("PING"));
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
            log.debug("Session URI: {}", session.getUri());
            log.debug("Query String: {}", query);

            if (query != null && query.contains("serial=")) {
                String[] params = query.split("&");
                for (String param : params) {
                    if (param.startsWith("serial=")) {
                        String serial = param.substring(7);
                        log.info("Serial Number 추출 성공: {}", serial);
                        return serial;
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