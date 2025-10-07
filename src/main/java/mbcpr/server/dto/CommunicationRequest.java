package mbcpr.server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommunicationRequest {
    private String serialNumber;
    private String action; // "start" or "stop"
}