package entity;

import java.time.LocalDateTime;

/** 告警前后帧的持久化信息，文件路径只在服务端使用。 */
public record AlertTimelineFrame(int frameOffset, LocalDateTime capturedAt, String keyFramePath) { }
