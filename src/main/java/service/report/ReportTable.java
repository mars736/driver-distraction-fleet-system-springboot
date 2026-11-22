package service.report;

import java.util.List;

record ReportTable(String title, List<String> columns, List<Object[]> rows) { }
