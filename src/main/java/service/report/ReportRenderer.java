package service.report;

import common.exception.BusinessException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
class ReportRenderer {
    private static final DateTimeFormatter TEXT_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private final String pdfFont;

    ReportRenderer(@Value("${report.pdf-font:}") String pdfFont) {
        this.pdfFont = pdfFont;
    }

    void render(ReportFormat format, ReportTable table, LocalDateTime dataAsOf, OutputStream output) throws Exception {
        LocalDateTime generatedAt = ReportJobService.now();
        switch (format) {
            case XLSX -> excel(table, dataAsOf, generatedAt, output);
            case PDF -> pdf(table, dataAsOf, generatedAt, output);
        }
    }

    private void excel(ReportTable table, LocalDateTime dataAsOf, LocalDateTime generatedAt, OutputStream output) throws Exception {
        try (var workbook = new SXSSFWorkbook(100)) {
            var sheet = workbook.createSheet(table.title());
            var heading = workbook.createCellStyle();
            var bold = workbook.createFont();
            bold.setBold(true);
            heading.setFont(bold);
            heading.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            heading.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var time = workbook.createCellStyle();
            time.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss.000"));
            var header = sheet.createRow(0);
            for (int column = 0; column < table.columns().size(); column++) {
                var cell = header.createCell(column);
                String name = table.columns().get(column);
                cell.setCellValue(name);
                cell.setCellStyle(heading);
                sheet.setColumnWidth(column, (name.contains("时间") ? 27 : Math.max(14, name.length() * 2 + 4)) * 256);
            }
            sheet.createFreezePane(0, 1);
            for (int index = 0; index < table.rows().size(); index++) {
                Object[] values = table.rows().get(index);
                var row = sheet.createRow(index + 1);
                for (int column = 0; column < values.length; column++) {
                    writeExcelCell(row.createCell(column), values[column], time);
                }
            }
            var metadata = workbook.createSheet("取数信息");
            metadata.setColumnWidth(0, 24 * 256);
            metadata.setColumnWidth(1, 28 * 256);
            var snapshotRow = metadata.createRow(0);
            snapshotRow.createCell(0).setCellValue("取数时间（北京时间）");
            writeExcelCell(snapshotRow.createCell(1), dataAsOf, time);
            var generatedRow = metadata.createRow(1);
            generatedRow.createCell(0).setCellValue("生成时间（北京时间）");
            writeExcelCell(generatedRow.createCell(1), generatedAt, time);
            if (table.scoreMonth() != null) {
                var monthRow = metadata.createRow(2);
                monthRow.createCell(0).setCellValue("统计月份");
                monthRow.createCell(1).setCellValue(table.scoreMonth());
            }
            workbook.write(output);
        }
    }

    private static void writeExcelCell(Cell cell, Object value, CellStyle time) {
        if (value instanceof LocalDateTime date && date.getYear() >= 1900) {
            cell.setCellValue(date);
            cell.setCellStyle(time);
        } else if (value instanceof Long number && (number > 999_999_999_999_999L || number < -999_999_999_999_999L)) {
            cell.setCellValue(number.toString());
        } else if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
            cell.setCellValue(number.doubleValue());
        } else if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
        } else if (value != null) {
            cell.setCellValue(value.toString());
        }
    }

    private void pdf(ReportTable table, LocalDateTime dataAsOf, LocalDateTime generatedAt, OutputStream output) throws Exception {
        BaseFont cjk = BaseFont.createFont(findFont(), BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
        if (!cjk.charExists('报')) {
            throw new BusinessException(422, "配置的 PDF 字体缺少中文字符");
        }
        Document document = new Document(table.columns().size() > 12 ? PageSize.A3.rotate() : PageSize.A4.rotate(),
                28, 28, 30, 30);
        PdfWriter writer = PdfWriter.getInstance(document, output);
        writer.setCloseStream(false);
        document.open();
        try {
            document.add(new Paragraph(table.title(), new Font(cjk, 16, Font.BOLD)));
            var metadataFont = new Font(cjk, 9);
            document.add(new Paragraph("取数时间（北京时间） " + TEXT_DATE.format(dataAsOf), metadataFont));
            document.add(new Paragraph("生成时间（北京时间） " + TEXT_DATE.format(generatedAt), metadataFont));
            if (table.scoreMonth() != null) {
                document.add(new Paragraph("统计月份 " + table.scoreMonth(), metadataFont));
            }
            var grid = new PdfPTable(table.columns().size());
            grid.setWidthPercentage(100);
            grid.setSpacingBefore(12);
            grid.setHeaderRows(1);
            grid.setSplitLate(false);
            float[] widths = new float[table.columns().size()];
            for (int i = 0; i < widths.length; i++) {
                widths[i] = table.columns().get(i).contains("时间") ? 2.1f : 1f;
            }
            grid.setWidths(widths);
            var heading = new Font(cjk, 9, Font.BOLD);
            var body = new Font(cjk, 8);
            for (String title : table.columns()) { grid.addCell(pdfCell(title, heading, true)); }
            for (Object[] row : table.rows()) {
                for (Object value : row) { grid.addCell(pdfCell(pdfText(value), body, false)); }
            }
            if (table.rows().isEmpty()) {
                var cell = pdfCell("无匹配记录", body, false);
                cell.setColspan(table.columns().size());
                grid.addCell(cell);
            }
            document.add(grid);
        } finally {
            document.close();
        }
    }

    private static PdfPCell pdfCell(String value, Font font, boolean header) {
        var cell = new PdfPCell(new Phrase(value, font));
        cell.setPadding(4);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        if (header) { cell.setBackgroundColor(new Color(226, 235, 249)); }
        return cell;
    }

    private static String pdfText(Object value) {
        if (value instanceof LocalDateTime date) { return TEXT_DATE.format(date); }
        return value == null ? "" : value.toString();
    }

    private String findFont() {
        if (!pdfFont.isBlank()) { return pdfFont; }
        String windowsDir = System.getenv("WINDIR");
        if (windowsDir != null) {
            Path windows = Path.of(windowsDir, "Fonts", "simhei.ttf");
            if (Files.isRegularFile(windows)) { return windows.toString(); }
        }
        for (String path : List.of("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/google-noto-cjk/NotoSansCJK-Regular.ttc")) {
            if (Files.isRegularFile(Path.of(path))) { return path + ",2"; }
        }
        throw new BusinessException(422, "未找到 PDF 中文字体，请配置 report.pdf-font");
    }
}
