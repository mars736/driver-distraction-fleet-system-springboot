package service.alert;

import vo.alert.AlertView;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class AlertReportCsv {
    private AlertReportCsv() { }

    public static byte[] encode(List<AlertView> alerts) {
        // BOM 让 Windows 表格软件默认按 UTF-8 打开中文报表。
        var out = new StringBuilder("\uFEFF");
        row(out, "告警ID", "采集时间（北京时间）", "车队ID", "车队名称", "车辆ID", "车牌号", "司机ID", "司机姓名",
                "告警类型", "行为类别", "置信度", "状态", "短信状态", "车速(km/h)", "车速状态");
        for (AlertView alert : alerts) {
            row(out, alert.getId(), alert.getCapturedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    alert.getFleetId(), alert.getFleetName(), alert.getVehicleId(), alert.getPlateNo(),
                    alert.getDriverId(), alert.getDriverName(), alert.getRecordType(), alert.getBehaviorCode(),
                    alert.getMaxP(), alert.getStatus(), alert.getSmsStatus(), alert.getSpeedKmh(), alert.getSpeedStatus());
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void row(StringBuilder out, Object... fields) {
        for (int index = 0; index < fields.length; index++) {
            if (index > 0) { out.append(','); }
            Object field = fields[index];
            String value = field == null ? "" : field.toString();
            if (field instanceof String && looksLikeFormula(value)) { value = "'" + value; }
            out.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        out.append("\r\n");
    }

    private static boolean looksLikeFormula(String value) {
        // 姓名、车牌等文本可能由用户填写，避免表格软件将它们当作公式。
        if (value.isEmpty()) { return false; }
        if ("\t\r\n".indexOf(value.charAt(0)) >= 0) { return true; }
        int first = value.codePoints().dropWhile(cp -> Character.isWhitespace(cp)
                        || Character.isSpaceChar(cp) || Character.getType(cp) == Character.FORMAT)
                .findFirst().orElse(-1);
        return first >= 0 && "=+-@＝＋－＠".indexOf(first) >= 0;
    }
}
