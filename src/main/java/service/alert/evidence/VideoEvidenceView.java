package service.alert.evidence;

import java.time.LocalDateTime;

public record VideoEvidenceView(AlertVideoEvidence.Status status, LocalDateTime actualStartAt,
                                LocalDateTime actualEndAt, String playbackUrl) { }
