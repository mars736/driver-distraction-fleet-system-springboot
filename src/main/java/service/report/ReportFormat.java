package service.report;

public enum ReportFormat {
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PDF("pdf", "application/pdf");

    private final String extension;
    private final String mediaType;

    ReportFormat(String extension, String mediaType) {
        this.extension = extension;
        this.mediaType = mediaType;
    }

    public String extension() { return extension; }
    public String mediaType() { return mediaType; }
}
