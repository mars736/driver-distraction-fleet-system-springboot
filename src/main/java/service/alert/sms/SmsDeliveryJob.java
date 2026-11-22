package service.alert.sms;

import java.time.LocalDateTime;

public record SmsDeliveryJob(long alertId, Long driverId, Long vehicleId,
                             String status, String outId, String recipient, LocalDateTime attemptedAt,
                             String providerMessageId) { }
