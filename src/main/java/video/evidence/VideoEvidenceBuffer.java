package video.evidence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

@Component
public class VideoEvidenceBuffer {
    private static final long BUFFER_MS = 90_000;
    private static final long CLOSED_SESSION_MS = 180_000;
    private static final DateTimeFormatter SEGMENT_TIME = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .appendLiteral('T').appendPattern("HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .appendOffset("+HHMM", "Z").toFormatter();

    private final VideoEvidenceProperties properties;
    private final Path workspace;
    private final HashMap<String, Session> sessions = new HashMap<>();

    public VideoEvidenceBuffer(VideoEvidenceProperties properties, @Value("${file.upload-dir:./upload}") String uploadDir)
            throws IOException {
        this.properties = properties;
        workspace = properties.getWorkspace().toAbsolutePath().normalize();
        if (properties.isEnabled()) {
            properties.validate();
            Path publicFiles = Path.of(uploadDir).toAbsolutePath().normalize();
            if (workspace.startsWith(publicFiles)
                    || (Files.exists(publicFiles) && Files.createDirectories(workspace).toRealPath()
                            .startsWith(publicFiles.toRealPath()))) {
                throw new IllegalArgumentException("录像缓存必须位于静态资源目录之外");
            }
            Files.createDirectories(sessionsRoot());
            Files.createDirectories(staging());
        }
    }

    public Path workspace() { return workspace; }

    public Path open(String streamId) throws IOException {
        if (!properties.isEnabled()) { return null; }
        Path dir = sessionsRoot().resolve(UUID.fromString(streamId.substring("managed:".length())).toString());
        Files.createDirectory(dir);
        synchronized (this) { sessions.put(streamId, new Session(dir)); }
        return dir;
    }

    public synchronized void sample(String streamId, Long driverId, long time) {
        Session session = sessions.get(streamId);
        if (session == null) { return; }
        Run last = session.runs.peekLast();
        if (last != null && Objects.equals(last.driverId, driverId)) {
            last.end = time;
        } else {
            session.runs.addLast(new Run(driverId, time));
        }
        while (session.runs.size() > 1 && session.runs.getFirst().end < time - BUFFER_MS) {
            session.runs.removeFirst();
        }
    }

    public synchronized void close(String streamId) {
        var session = sessions.get(streamId);
        if (session != null) { session.closedAt = System.currentTimeMillis(); }
    }

    public Path staging() { return workspace.resolve("staging"); }
    private Path sessionsRoot() { return workspace.resolve("sessions"); }

    public Clip plan(String streamId, long driverId, long capturedAt, long before, long after) throws IOException {
        Path dir;
        long from;
        long to;
        synchronized (this) {
            var session = sessions.get(streamId);
            if (session == null) { return null; }
            var run = session.runs.stream().filter(it -> Objects.equals(it.driverId, driverId)
                    && it.start <= capturedAt && it.end >= capturedAt).findFirst().orElse(null);
            if (run == null) { return null; }
            dir = session.dir;
            from = Math.max(capturedAt - before, run.start);
            to = Math.min(capturedAt + after, run.end);
        }
        return clip(dir, from, to, capturedAt);
    }

    public synchronized boolean mayExtend(String streamId, long driverId, long capturedAt) {
        var session = sessions.get(streamId);
        if (session == null || session.closedAt != 0) { return false; }
        var current = session.runs.peekLast();
        return current != null && Objects.equals(current.driverId, driverId) && current.start <= capturedAt;
    }

    static Clip clip(Path dir, long from, long to, long capturedAt) throws IOException {
        if (to - from < 500 || !Files.isRegularFile(dir.resolve("index.m3u8"))) { return null; }
        var segments = playlist(dir.resolve("index.m3u8"));
        var groups = new ArrayList<List<Segment>>();
        List<Segment> current = new ArrayList<>();
        for (Segment segment : segments) {
            if (!Files.isRegularFile(dir.resolve(segment.name())) || segment.end() <= from || segment.start() >= to) {
                continue;
            }
            if (!current.isEmpty() && Math.abs(segment.start() - current.getLast().end()) > 150) {
                groups.add(current);
                current = new ArrayList<>();
            }
            current.add(segment);
        }
        if (!current.isEmpty()) { groups.add(current); }
        for (var group : groups) {
            long start = Math.max(from, group.getFirst().start());
            long end = Math.min(to, group.getLast().end());
            if (start <= capturedAt && capturedAt <= end && end - start >= 500) {
                return new Clip(dir, group.stream().map(Segment::name).toList(), start, end,
                        start - group.getFirst().start());
            }
        }
        return null;
    }

    private static List<Segment> playlist(Path manifest) throws IOException {
        List<Segment> segments = new ArrayList<>();
        Long start = null;
        Long duration = null;
        for (String line : Files.readAllLines(manifest)) {
            if (line.startsWith("#EXTINF:")) {
                duration = Math.round(Double.parseDouble(line.substring(8, line.indexOf(','))) * 1000);
            } else if (line.startsWith("#EXT-X-PROGRAM-DATE-TIME:")) {
                String time = line.substring("#EXT-X-PROGRAM-DATE-TIME:".length());
                start = OffsetDateTime.parse(time, SEGMENT_TIME).toInstant().toEpochMilli();
            } else if (line.matches("part-[0-9]{6,12}\\.ts")) {
                if (start != null && duration != null && duration > 0) {
                    segments.add(new Segment(line, start, start + duration));
                    start += duration;
                }
                duration = null;
            }
        }
        return segments;
    }

    public void prune(long now) throws IOException {
        if (!properties.isEnabled()) { return; }
        List<Path> active;
        synchronized (this) {
            sessions.values().removeIf(session -> session.closedAt != 0 && session.closedAt < now - CLOSED_SESSION_MS);
            active = sessions.values().stream().map(session -> session.dir).toList();
        }
        try (Stream<Path> directories = Files.list(sessionsRoot())) {
            for (Path dir : directories.toList()) {
                if (!active.contains(dir) && Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        && dir.getFileName().toString().matches("[0-9a-f-]{36}")
                        && Files.getLastModifiedTime(dir).toMillis() < now - CLOSED_SESSION_MS) {
                    try (var paths = Files.list(dir)) {
                        for (Path file : paths.toList()) { Files.deleteIfExists(file); }
                    }
                    Files.deleteIfExists(dir);
                }
            }
        }
    }

    public record Clip(Path directory, List<String> parts, long start, long end, long offset) { }
    private record Segment(String name, long start, long end) { }

    private static final class Session {
        private final Path dir;
        private final ArrayDeque<Run> runs = new ArrayDeque<>();
        private long closedAt;

        private Session(Path dir) { this.dir = dir; }
    }

    private static final class Run {
        private final Long driverId;
        private final long start;
        private long end;

        private Run(Long driverId, long at) {
            this.driverId = driverId;
            start = at;
            end = at;
        }
    }
}
