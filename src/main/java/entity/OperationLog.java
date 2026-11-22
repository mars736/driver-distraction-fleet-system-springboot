package entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OperationLog {
    private Long id;
    private Long actorId;
    private Long fleetId;
    private String action;
    private String targetType;
    private Long targetId;
    private String outcome;
    private String detailCode;
    private String requestId;
    private LocalDateTime operatedAt;
}
