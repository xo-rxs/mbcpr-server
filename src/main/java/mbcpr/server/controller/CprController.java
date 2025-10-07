package mbcpr.server.controller;

import mbcpr.server.dto.CommunicationRequest;
import mbcpr.server.dto.CommunicationResponse;
import mbcpr.server.dto.ConnectionCheckRequest;
import mbcpr.server.dto.ConnectionResponse;
import mbcpr.server.service.BoardCommunicationService;
import mbcpr.server.service.CprCommunicationService;
import mbcpr.server.service.SensorDataProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/api/cpr")
@RequiredArgsConstructor
public class CprController {

    private final BoardCommunicationService boardCommunicationService;
    private final CprCommunicationService cprCommunicationService;
    private final SensorDataProcessingService sensorDataProcessingService;

    /**
     * 보드 연결 상태 확인
     * 앱에서 호출하여 보드가 연결되어 있는지 확인
     */
    @PostMapping("/check-connection")
    public ResponseEntity<ConnectionResponse> checkConnection(@RequestBody ConnectionCheckRequest request) {
        String serialNumber = request.getSerialNumber();
        log.info("연결 확인 요청: {}", serialNumber);

        // 1. 보드의 시리얼 번호가 연결되어 있는지 확인
        if (!boardCommunicationService.isBoardConnected(serialNumber)) {
            log.info("보드 연결되지 않음: {}", serialNumber);
            return ResponseEntity.ok(new ConnectionResponse(false, "연결되어 있지 않음"));
        }

        // 2. 보드에 연결 확인 요청 (3초 타임아웃)
        boolean connected = boardCommunicationService.checkConnectionWithTimeout(serialNumber, 3);

        if (connected) {
            log.info("보드 연결 확인됨: {}", serialNumber);
            return ResponseEntity.ok(new ConnectionResponse(true, "연결되어 있음"));
        } else {
            log.info("보드 응답 없음: {}", serialNumber);
            return ResponseEntity.ok(new ConnectionResponse(false, "연결되어 있지 않음"));
        }
    }

    /**
     * 실시간 통신 시작
     * 앱에서 호출하여 보드와의 실시간 데이터 통신을 시작
     */
    @PostMapping("/start-communication")
    public ResponseEntity<CommunicationResponse> startCommunication(@RequestBody CommunicationRequest request) {
        String serialNumber = request.getSerialNumber();
        log.info("통신 시작 요청: {}", serialNumber);

        // 1. 보드가 연결되어 있는지 확인
        if (!boardCommunicationService.isBoardConnected(serialNumber)) {
            log.warn("보드 연결되지 않음: {}", serialNumber);
            return ResponseEntity.ok(new CommunicationResponse(false, "보드가 연결되어 있지 않습니다"));
        }

        // 2. 보드에 통신 시작 요청
        boolean started = boardCommunicationService.startCommunication(serialNumber);

        if (started) {
            // 3. 센서 데이터 처리 시작
            sensorDataProcessingService.startProcessing(serialNumber);
            log.info("통신 시작 성공: {}", serialNumber);
            return ResponseEntity.ok(new CommunicationResponse(true, "통신이 시작되었습니다"));
        } else {
            log.warn("통신 시작 실패: {}", serialNumber);
            return ResponseEntity.ok(new CommunicationResponse(false, "통신 시작에 실패했습니다"));
        }
    }

    /**
     * 실시간 통신 중단
     * 앱에서 호출하여 보드와의 실시간 데이터 통신을 중단
     */
    @PostMapping("/stop-communication")
    public ResponseEntity<CommunicationResponse> stopCommunication(@RequestBody CommunicationRequest request) {
        String serialNumber = request.getSerialNumber();
        log.info("통신 중단 요청: {}", serialNumber);

        // 1. 보드에 통신 중단 요청
        boardCommunicationService.stopCommunication(serialNumber);

        // 2. 센서 데이터 처리 중단
        sensorDataProcessingService.stopProcessing(serialNumber);

        // 3. SSE 연결 종료
        cprCommunicationService.closeEmitter(serialNumber);

        log.info("통신 중단 완료: {}", serialNumber);
        return ResponseEntity.ok(new CommunicationResponse(true, "통신이 중단되었습니다"));
    }

    /**
     * 실시간 센서 데이터 스트림 (SSE)
     * 앱이 이 엔드포인트에 연결하여 실시간으로 센서 데이터를 받음
     */
    @GetMapping(value = "/stream/{serialNumber}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSensorData(@PathVariable String serialNumber) {
        log.info("센서 데이터 스트림 연결: {}", serialNumber);
        return cprCommunicationService.createEmitter(serialNumber);
    }

    /**
     * 서버 상태 확인
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
