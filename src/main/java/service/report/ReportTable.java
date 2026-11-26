package service.report;

import java.util.List;

record ReportTable(String title, List<String> columns, List<Object[]> rows, String scoreMonth) {
    ReportTable(String title, List<String> columns, List<Object[]> rows) {
        this(title, columns, rows, null);
    }
}
