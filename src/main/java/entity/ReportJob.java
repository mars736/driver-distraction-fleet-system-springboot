package entity;

import lombok.Data;
import service.report.ReportFormat;
import service.report.ReportType;

import java.time.LocalDateTime;

@Data
public class ReportJob {
    public enum Status { PENDING, RUNNING, COMPLETED, FAILED, EXPIRED }

    private Long id;
    private Long ownerId;
    private String requestKey;
    private ReportType reportType;
    private ReportFormat format;
    private String filtersJson;
    private Status status;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime dataAsOf;
    private LocalDateTime finishedAt;
    private LocalDateTime expiresAt;
    private String runToken;
    private int attempts;
    private Integer rowCount;
    private String errorMessage;
}
