package fleet.rating;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.MultipleInputs;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DriverSafetyRatingJob extends Configured implements Tool {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CUTOFF = "fleet.rating.cutoff";
    private static final String ROSTER = "fleet.rating.roster";

    public static void main(String[] args) throws Exception {
        System.exit(ToolRunner.run(new Configuration(), new DriverSafetyRatingJob(), args));
    }

    @Override
    public int run(String[] args) throws Exception {
        if (args.length != 2 || !args[0].matches("/(?:[A-Za-z0-9_-]+/)*batches/[a-f0-9-]{36}")
                || !args[1].matches("(?:/[A-Za-z0-9_-]+)+")) {
            throw new IllegalArgumentException("Expected an HDFS rating batch path and archive root");
        }
        Path batch = new Path(args[0]);
        FileSystem fs = batch.getFileSystem(getConf());
        if (!fs.exists(new Path(batch, "ready.ok"))) { throw new IOException("Rating inputs are incomplete"); }
        if (fs.exists(new Path(batch, "outcome.tsv"))) { return 0; }

        String attempt = UUID.randomUUID().toString();
        Path work = new Path(batch, "attempts/" + attempt);
        String firstId = "-";
        String secondId = "-";
        try {
            LocalDateTime cutoff = LocalDateTime.parse(single(fs, new Path(batch, "cutoff.txt")));
            Path roster = new Path(batch, "roster.tsv");
            getConf().set(CUTOFF, cutoff.toString());
            getConf().set(ROSTER, roster.toString());
            Path latest = new Path(work, "confirmed");
            Path scores = new Path(work, "scores");
            Path manifest = new Path(batch, "manifest.txt");
            List<String> files = lines(fs, manifest);
            Job first = job("Fleet safety latest alert revisions", LatestReducer.class);
            first.setMapperClass(AlertMapper.class);
            if (files.isEmpty()) {
                FileInputFormat.addInputPath(first, manifest);
            } else {
                for (String file : files) {
                    if (!file.matches(java.util.regex.Pattern.quote(args[1])
                            + "/date=[0-9]{4}-[0-9]{2}-[0-9]{2}/batch-[a-f0-9-]{36}\\.jsonl")) {
                        throw new IOException("Invalid archive filename in manifest");
                    }
                    FileInputFormat.addInputPath(first, new Path(file));
                }
            }
            FileOutputFormat.setOutputPath(first, latest);
            first.submit();
            firstId = applicationId(first);
            publish(fs, batch, "stage1-job.txt", firstId);
            if (!first.waitForCompletion(true)) { throw new IOException("Latest-alert job failed"); }

            Job second = job("Fleet driver lifetime safety rating", RatingReducer.class);
            MultipleInputs.addInputPath(second, roster, TextInputFormat.class, RosterMapper.class);
            MultipleInputs.addInputPath(second, new Path(latest, "part-r-00000"), TextInputFormat.class, ViolationMapper.class);
            FileOutputFormat.setOutputPath(second, scores);
            second.submit();
            secondId = applicationId(second);
            publish(fs, batch, "stage2-job.txt", secondId);
            if (!second.waitForCompletion(true)) { throw new IOException("Driver-rating job failed"); }
            publish(fs, batch, "outcome.tsv", "SUCCEEDED\t" + attempt + "\t" + firstId + "\t" + secondId);
            return 0;
        } catch (Exception ex) {
            publish(fs, batch, "outcome.tsv", "FAILED\t" + attempt + "\t" + firstId + "\t" + secondId);
            throw ex;
        }
    }

    private Job job(String name, Class<? extends Reducer> reducer) throws IOException {
        Job job = Job.getInstance(getConf(), name);
        job.setJarByClass(DriverSafetyRatingJob.class);
        job.setMapOutputKeyClass(LongWritable.class);
        job.setMapOutputValueClass(Text.class);
        job.setOutputKeyClass(NullWritable.class);
        job.setOutputValueClass(Text.class);
        job.setReducerClass(reducer);
        job.setNumReduceTasks(1);
        return job;
    }

    public static final class AlertMapper extends Mapper<LongWritable, Text, LongWritable, Text> {
        @Override
        protected void map(LongWritable offset, Text value, Context ctx) throws IOException, InterruptedException {
            JsonNode alert = JSON.readTree(value.toString());
            long id = number(alert, "alertId");
            long revision = number(alert, "revision");
            LocalDateTime cutoff = LocalDateTime.parse(ctx.getConfiguration().get(CUTOFF));
            LocalDateTime recorded = time(alert, "recordedAt");
            LocalDateTime captured = time(alert, "capturedAt");
            if (id <= 0 || revision < 0) { throw new IOException("Invalid alert key"); }
            if (!captured.isAfter(cutoff) && (revision == 0 || !recorded.isAfter(cutoff))) {
                ctx.write(new LongWritable(id), value);
            }
        }
    }

    public static final class LatestReducer extends Reducer<LongWritable, Text, NullWritable, Text> {
        private final Map<Long, LocalDateTime> drivers = new HashMap<>();

        @Override
        protected void setup(Context ctx) throws IOException {
            Path roster = new Path(ctx.getConfiguration().get(ROSTER));
            for (String line : lines(roster.getFileSystem(ctx.getConfiguration()), roster)) {
                String[] row = columns(line, 3);
                if (drivers.put(Long.parseLong(row[0]), LocalDateTime.parse(row[1])) != null) {
                    throw new IOException("Duplicate driver in frozen roster");
                }
            }
        }

        @Override
        protected void reduce(LongWritable id, Iterable<Text> revisions, Context ctx)
                throws IOException, InterruptedException {
            JsonNode latest = null;
            long highest = -1;
            boolean conflicting = false;
            for (Text version : revisions) {
                JsonNode alert = JSON.readTree(version.toString());
                long revision = number(alert, "revision");
                if (revision > highest) {
                    latest = alert;
                    highest = revision;
                    conflicting = false;
                } else if (revision == highest && !alert.equals(latest)) {
                    conflicting = true;
                }
            }
            if (conflicting) { throw new IOException("Conflicting latest versions of an alert"); }
            if (latest == null || !"CONFIRMED".equals(latest.path("status").asText())
                    || latest.path("driverId").isNull()) { return; }
            long driver = number(latest, "driverId");
            LocalDateTime captured = time(latest, "capturedAt");
            LocalDateTime registered = drivers.get(driver);
            if (registered == null || captured.isBefore(registered)) { return; }
            String type = latest.path("recordType").asText();
            if ("SMS_ALERT".equals(type) || "BACKLOG".equals(type)) {
                ctx.write(NullWritable.get(), new Text(driver + "\t" + captured + "\t" + type));
            }
        }
    }

    public static final class RosterMapper extends Mapper<LongWritable, Text, LongWritable, Text> {
        @Override
        protected void map(LongWritable offset, Text line, Context ctx) throws IOException, InterruptedException {
            String[] row = columns(line.toString(), 3);
            if (!"true".equals(row[2]) && !"false".equals(row[2])) { throw new IOException("Invalid roster history"); }
            ctx.write(new LongWritable(Long.parseLong(row[0])), new Text("R\t" + row[2]));
        }
    }

    public static final class ViolationMapper extends Mapper<LongWritable, Text, LongWritable, Text> {
        @Override
        protected void map(LongWritable offset, Text line, Context ctx) throws IOException, InterruptedException {
            String[] row = columns(line.toString(), 3);
            ctx.write(new LongWritable(Long.parseLong(row[0])), new Text(row[2] + "\t" + row[1]));
        }
    }

    public static final class RatingReducer extends Reducer<LongWritable, Text, NullWritable, Text> {
        @Override
        protected void reduce(LongWritable driver, Iterable<Text> values, Context ctx) throws IOException, InterruptedException {
            LocalDateTime cutoff = LocalDateTime.parse(ctx.getConfiguration().get(CUTOFF));
            String history = null;
            long count = 0;
            BigDecimal penalty = BigDecimal.ZERO;
            for (Text value : values) {
                String[] row = columns(value.toString(), 2);
                if ("R".equals(row[0])) {
                    if (history != null) { throw new IOException("Duplicate driver in frozen roster"); }
                    history = row[1];
                } else {
                    double days = Duration.between(LocalDateTime.parse(row[1]), cutoff).toMillis() / 86_400_000.0;
                    int basis = "SMS_ALERT".equals(row[0]) ? 8 : 2;
                    penalty = penalty.add(BigDecimal.valueOf(basis * StrictMath.pow(0.5, days / 180)));
                    count++;
                }
            }
            if (history == null) { throw new IOException("Violation has no frozen driver"); }
            BigDecimal score = BigDecimal.valueOf(100).subtract(penalty).max(BigDecimal.ZERO).setScale(1, RoundingMode.HALF_UP);
            ctx.write(NullWritable.get(), new Text(driver.get() + "\t" + count + "\t" + score + "\t"
                    + level(score.doubleValue()) + "\t" + history));
        }
    }

    static String level(double score) {
        if (score >= 95) { return "S"; }
        if (score >= 85) { return "A"; }
        if (score >= 70) { return "B"; }
        if (score >= 60) { return "C"; }
        return "D";
    }

    private static String applicationId(Job job) {
        return job.getJobID().toString().replaceFirst("^job_", "application_");
    }

    private static long number(JsonNode node, String field) throws IOException {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) { throw new IOException("Invalid number: " + field); }
        return value.longValue();
    }

    private static LocalDateTime time(JsonNode node, String field) throws IOException {
        JsonNode value = node.path(field);
        if (!value.isTextual()) { throw new IOException("Invalid timestamp: " + field); }
        return LocalDateTime.parse(value.textValue());
    }

    private static String[] columns(String line, int count) throws IOException {
        String[] row = line.split("\t", -1);
        if (row.length != count) { throw new IOException("Invalid tab-delimited rating input"); }
        return row;
    }

    private static List<String> lines(FileSystem fs, Path path) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(fs.open(path), StandardCharsets.UTF_8))) {
            for (String line; (line = input.readLine()) != null;) { lines.add(line); }
        }
        return lines;
    }

    private static String single(FileSystem fs, Path path) throws IOException {
        List<String> content = lines(fs, path);
        if (content.size() != 1) { throw new IOException("Invalid cutoff"); }
        return content.get(0);
    }

    private static void publish(FileSystem fs, Path batch, String file, String text) throws IOException {
        Path tmp = new Path(batch, "." + file + "-" + UUID.randomUUID());
        Path dest = new Path(batch, file);
        try {
            try (org.apache.hadoop.fs.FSDataOutputStream out = fs.create(tmp, false)) {
                out.write((text + "\n").getBytes(StandardCharsets.UTF_8));
            }
            if (!fs.rename(tmp, dest) && !fs.exists(dest)) { throw new IOException("Cannot publish " + file); }
        } finally {
            fs.delete(tmp, false);
        }
    }
}
