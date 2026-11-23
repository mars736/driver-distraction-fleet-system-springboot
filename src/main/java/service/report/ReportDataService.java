package service.report;

import common.exception.BusinessException;
import dto.alert.AlertFilterRequest;
import dto.recognition.RecognitionQueryRequest;
import dto.report.ReportRequest;
import dto.score.ScoreQueryRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import service.alert.AlertQueryService;
import service.dashboard.DashboardService;
import service.recognition.RecognitionQueryService;
import service.score.DriverScoreService;
import service.score.ScorePeriod;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportDataService {
    private final AlertQueryService alerts;
    private final RecognitionQueryService recognitions;
    private final DriverScoreService scores;
    private final DashboardService dashboard;

    public void validate(ReportRequest request) {
        if (!validDate(request.startTime()) || !validDate(request.endTime())
                || request.startTime() != null && request.endTime() != null
                && !request.startTime().isBefore(request.endTime())) {
            throw new BusinessException(400, "时间范围无效，请使用不带时区的北京时间");
        }
        boolean hasRecognitionFilters = request.cameraId() != null || request.ood() != null || request.participated() != null;
        boolean hasAlertFilters = request.recordType() != null || request.status() != null;
        boolean hasDetailFilters = hasRecognitionFilters || hasAlertFilters || request.behaviorCode() != null
                || request.vehicleId() != null;
        boolean supported = switch (request.type()) {
            case ALERT -> !hasRecognitionFilters && request.month() == null;
            case RECOGNITION -> !hasAlertFilters && request.month() == null;
            case DRIVER_SCORE -> !hasDetailFilters && request.startTime() == null && request.endTime() == null
                    && request.month() != null;
            case FLEET_STATISTICS -> !hasDetailFilters && request.driverId() == null && request.month() == null
                    && request.startTime() != null && request.endTime() != null;
        };
        if (!supported) { throw new BusinessException(400, "筛选字段与报表类型不匹配"); }
        if (request.type() == ReportType.DRIVER_SCORE) {
            ScorePeriod.parse(request.month(), Clock.system(ScorePeriod.ZONE));
        }
    }

    ReportTable load(ReportRequest request) {
        return switch (request.type()) {
            case ALERT -> alertTable(request);
            case RECOGNITION -> recognitionTable(request);
            case DRIVER_SCORE -> scoreTable(request);
            case FLEET_STATISTICS -> fleetTable(request);
        };
    }

    private ReportTable alertTable(ReportRequest request) {
        var filter = new AlertFilterRequest();
        filter.setFleetId(request.fleetId());
        filter.setVehicleId(request.vehicleId());
        filter.setDriverId(request.driverId());
        filter.setBehaviorCode(request.behaviorCode());
        filter.setRecordType(request.recordType());
        filter.setStatus(request.status());
        filter.setStartTime(time(request.startTime()));
        filter.setEndTime(time(request.endTime()));
        var rows = alerts.exportRows(filter).stream().map(row -> new Object[]{
                row.getId(), row.getCapturedAt(), row.getFleetId(), row.getFleetName(), row.getVehicleId(), row.getPlateNo(),
                row.getDriverId(), row.getDriverName(), row.getRecordType(), row.getBehaviorCode(), row.getMaxP(), row.getStatus(),
                row.getSmsStatus(), row.getSpeedKmh(), row.getSpeedStatus()
        }).toList();
        return new ReportTable("告警报表", List.of("告警 ID", "采集时间", "车队 ID", "车队", "车辆 ID", "车牌",
                "司机 ID", "司机", "类型", "行为", "置信度", "处理状态", "短信状态", "车速 km/h", "车速状态"), rows);
    }

    private ReportTable recognitionTable(ReportRequest request) {
        var filter = new RecognitionQueryRequest();
        filter.setFleetId(request.fleetId());
        filter.setVehicleId(request.vehicleId());
        filter.setDriverId(request.driverId());
        filter.setCameraId(request.cameraId());
        filter.setBehaviorCode(request.behaviorCode());
        filter.setOod(request.ood());
        filter.setParticipated(request.participated());
        filter.setStartTime(time(request.startTime()));
        filter.setEndTime(time(request.endTime()));
        var rows = recognitions.exportRows(filter).stream().map(row -> new Object[]{
                row.getId(), row.getCapturedAt(), row.getCameraId(), row.getFleetId(), row.getFleetName(), row.getVehicleId(),
                row.getPlateNo(), row.getDriverId(), row.getDriverName(), row.getPred(), row.getBehaviorCode(), row.getMaxP(),
                row.getEnergy(), row.getKnnDist(), row.getOod(), row.getParticipated(), row.getDecisionReason(),
                row.getSpeedKmh(), row.getSpeedStatus()
        }).toList();
        return new ReportTable("识别记录报表", List.of("识别 ID", "采集时间", "摄像头 ID", "车队 ID", "车队", "车辆 ID",
                "车牌", "司机 ID", "司机", "预测类别", "行为", "置信度", "Energy", "KNN 距离", "OOD", "参与判定",
                "判定原因", "车速 km/h", "车速状态"), rows);
    }

    private ReportTable scoreTable(ReportRequest request) {
        var filter = new ScoreQueryRequest();
        filter.setMonth(request.month());
        filter.setFleetId(request.fleetId());
        var rows = scores.exportRows(filter, request.driverId()).stream().map(row -> new Object[]{
                row.getMonth(), row.getFleetId(), row.getDriverId(), row.getName(), row.getEmployeeNo(), row.getScore(),
                row.getLevelLabel(), row.getDeductedPoints(), row.getSmsAlertCount(), row.getBacklogCount(),
                row.getRevokedCount(), row.getRanking()
        }).toList();
        return new ReportTable("司机评分报表", List.of("月份", "车队 ID", "司机 ID", "司机", "工号", "分数", "等级",
                "累计扣分", "短信告警数", "其他告警数", "已撤销数", "排名"), rows);
    }

    private ReportTable fleetTable(ReportRequest request) {
        var range = dashboard.range(request.fleetId(), request.startTime(), request.endTime());
        var counts = range.recognitions();
        var summary = range.alerts();
        var columns = new ArrayList<>(List.of("车队", "开始时间", "结束时间", "车辆数", "司机数", "总帧数", "正常帧",
                "分心帧", "OOD 帧", "未分类帧", "OOD 比例", "有效告警数", "速度未知数", "撤销告警数"));
        var row = new ArrayList<>(Arrays.asList(range.fleetName(), range.start(), range.end(),
                range.fleetSize().vehicleCount(), range.fleetSize().driverCount(), counts.totalFrames(), counts.normalFrames(),
                counts.distractedFrames(), counts.oodFrames(), counts.unclassifiedFrames(), counts.oodRate(),
                summary.activeViolationCount(), summary.speedUnknownCount(), summary.revokedViolationCount()));
        for (var behavior : counts.behaviors()) {
            columns.add(behavior.behaviorCode() + " 分心占比");
            row.add(behavior.share());
        }
        return new ReportTable("车队统计报表", columns, Collections.singletonList(row.toArray()));
    }

    private static String time(LocalDateTime value) { return value == null ? null : value.toString(); }

    private static boolean validDate(LocalDateTime value) {
        return value == null || value.getYear() >= 1000 && value.getYear() <= 9998;
    }
}
