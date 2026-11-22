package service.report;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Predicate;

@Component
class ReportFileStore {
    private static final String OUTPUT_FILE = "[a-f0-9-]{36}\\.(xlsx|pdf)(?:\\.part)?";
    private final Path uploadRoot;

    ReportFileStore(@Value("${file.upload-dir:./upload}") String uploadDir) {
        uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    Path partial(String token, ReportFormat format) throws IOException { return output(token, format, true); }

    Path publish(String token, ReportFormat format) throws IOException {
        Path complete = output(token, format, false);
        return Files.move(partial(token, format), complete, StandardCopyOption.ATOMIC_MOVE);
    }

    Path readable(String token, ReportFormat format) throws IOException {
        Path file = output(token, format, false);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(file)) {
            throw new NoSuchFileException("Missing report");
        }
        return file;
    }

    void delete(String token, ReportFormat format) throws IOException {
        if (token == null) { return; }
        Files.deleteIfExists(partial(token, format));
        Files.deleteIfExists(output(token, format, false));
    }

    void discardOrphans(Instant before, Predicate<String> inUse) throws IOException {
        try (var entries = Files.list(directory())) {
            for (Path path : (Iterable<Path>) entries::iterator) {
                String name = path.getFileName().toString();
                if (name.matches(OUTPUT_FILE) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(before)
                        && !inUse.test(name.substring(0, 36))) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private Path output(String token, ReportFormat format, boolean partial) throws IOException {
        String canonical = UUID.fromString(token).toString();
        if (!canonical.equals(token)) { throw new IOException("Invalid report file identifier"); }
        return directory().resolve(canonical + "." + format.extension() + (partial ? ".part" : ""));
    }

    private Path directory() throws IOException {
        Path root = uploadRoot.resolve("reports");
        Files.createDirectories(root);
        Path real = root.toRealPath();
        if (!real.equals(uploadRoot.toRealPath().resolve("reports"))) {
            throw new IOException("Invalid report directory");
        }
        return real;
    }
}
