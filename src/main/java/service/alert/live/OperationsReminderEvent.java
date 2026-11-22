package service.alert.live;

public record OperationsReminderEvent(long id, String sourceType, long sourceId, long fleetId,
                                      String subjectType, long subjectId, String stage) { }
