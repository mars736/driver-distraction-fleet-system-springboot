package video.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VideoEvidenceBufferTest {
    @TempDir Path temp;

    @Test
    void footageStopsBeforeAnObservedDriverChangeAndExcludesAnotherSession() throws Exception {
        var options = new VideoEvidenceProperties();
        options.setEnabled(true);
        options.setWorkspace(temp.resolve("private"));
        var buffer = new VideoEvidenceBuffer(options, temp.resolve("public").toString());
        String stream = "managed:" + UUID.randomUUID();
        var path = buffer.open(stream);
        long start = Instant.parse("2026-11-28T09:00:00Z").toEpochMilli();

        Files.writeString(path.resolve("index.m3u8"), """
                #EXTM3U
                #EXTINF:3.0,
                #EXT-X-PROGRAM-DATE-TIME:2026-11-28T17:00:00.000+0800
                part-000000.ts
                #EXTINF:3.0,
                #EXT-X-PROGRAM-DATE-TIME:2026-11-28T17:00:03.000+0800
                part-000001.ts
                #EXTINF:3.0,
                #EXT-X-PROGRAM-DATE-TIME:2026-11-28T17:00:06.000+0800
                part-000002.ts
                """);
        for (int i = 0; i < 3; i++) { Files.createFile(path.resolve("part-00000" + i + ".ts")); }
        buffer.sample(stream, 10L, start);
        buffer.sample(stream, 10L, start + 4_000);
        buffer.sample(stream, 10L, start + 6_000);
        buffer.sample(stream, 20L, start + 7_000);
        buffer.sample(stream, 20L, start + 9_000);
        buffer.close(stream);

        var clip = buffer.plan(stream, 10, start + 4_000, 5_000, 10_000);
        assertThat(clip.parts()).containsExactly("part-000000.ts", "part-000001.ts");
        assertThat(clip.start()).isEqualTo(start);
        assertThat(clip.end()).isEqualTo(start + 6_000);
        assertThat(buffer.plan(stream, 20, start + 4_000, 5_000, 10_000)).isNull();
        assertThat(buffer.plan("managed:" + UUID.randomUUID(), 10, start + 4_000, 5_000, 10_000)).isNull();

        Files.delete(path.resolve("part-000001.ts"));
        assertThat(buffer.plan(stream, 10, start + 4_000, 5_000, 10_000)).isNull();
    }
}
