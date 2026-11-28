package service.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import common.security.LoginUser;
import dto.operations.DifyAgent.*;
import dto.operations.TransportOrders.*;
import dto.operations.Workforce.Leave;
import dto.operations.Workforce.LeaveStatus;
import dto.operations.Workforce.LeaveType;
import entity.Fleet;
import mapper.DifyAgentMapper;
import mapper.FleetMapper;
import mapper.OperationsRecordMapper;
import mapper.WorkforceMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import service.alert.archive.ArchiveEvent;
import service.alert.archive.ArchiveEvent.Source;
import service.alert.archive.WebHdfsArchiveClient;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dto.operations.Workforce.ShiftType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DifyAgentDomainTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void clearActor() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void earlyOnlyDriverIsUsedForEarlyShift() {
        var mapper = mock(DifyAgentMapper.class);
        var workforce = mock(WorkforceMapper.class);
        var date = LocalDate.of(2030, 6, 1);
        var next = date.plusDays(1);
        when(mapper.drivers(5L, 0, 501)).thenReturn(List.of(
                new DriverOption(1, 5, "D1", "灵活司机"), new DriverOption(2, 5, "D2", "早班司机")));
        when(workforce.approved(2, date.atStartOfDay(), next.atStartOfDay())).thenReturn(List.of(
                new Leave(1, 2, 5, LeaveType.PERSONAL, date.atTime(16, 0), next.atStartOfDay(),
                        "测试", LeaveStatus.APPROVED, date.minusDays(1).atStartOfDay(), 1L, null, null)));
        var plan = new DifyShiftPlanner(mapper, workforce).plan(new ScheduleRequest(5L, date, next, 1, 1),
                date.minusDays(1).atStartOfDay());
        assertThat(plan.proposed()).extracting(ProposedShift::driverId).containsExactly(2L, 1L);
        assertThat(plan.proposed()).extracting(ProposedShift::type).containsExactly(EARLY, LATE);
        assertThat(plan.shortages()).isEmpty();
    }

    @Test
    void confirmedOrderDraftReplaysReceiptWithoutCreatingAnotherOrder() throws Exception {
        admin();
        var mapper = mock(DifyAgentMapper.class);
        var fleets = mock(FleetMapper.class);
        var orders = mock(TransportOrderService.class);
        var fleet = new Fleet();
        fleet.setEnabled(true);
        when(fleets.lockById(5L)).thenReturn(fleet);
        var end = OperationsAccess.now().plusDays(2);
        var draft = new Draft(5L, "运输", "测试", "A", "B", end.minusHours(2), end, Requirement.NORMAL);
        var id = "44c51ca9-6ed1-4c1e-89a7-d210cb1e1b0f";
        var receipt = Map.of("orderId", 9, "orderNo", "TO-9", "status", "WAITING_DISPATCH", "version", 1);
        when(mapper.lockDraft(id, 1)).thenReturn(
                new DraftRow(id, 1, Action.ORDER, json.writeValueAsString(draft), end, null),
                new DraftRow(id, 1, Action.ORDER, null, end, json.writeValueAsString(receipt)));
        when(orders.create(draft)).thenReturn(new OrderRow(9, "TO-9", 5, "运输", "测试", "A", "B",
                draft.startsAt(), draft.endsAt(), Requirement.NORMAL, 1, null, null, null,
                Status.WAITING_DISPATCH, end, end));
        when(mapper.completeDraft(eq(id), anyString())).thenReturn(1);
        var service = new DifyDraftService(mapper, fleets, mock(OperationsRecordMapper.class),
                mock(DifyShiftPlanner.class), mock(WorkforceService.class), orders, json);
        assertThat(json.valueToTree(service.confirm(id).result()).get("orderId").asLong()).isEqualTo(9);
        assertThat(json.valueToTree(service.confirm(id).result()).get("orderId").asLong()).isEqualTo(9);
        verify(orders, times(1)).create(draft);
    }

    @Test
    void latestArchiveRevisionCancelsEarlierCountAndMonthsIncludeZero() throws Exception {
        admin();
        var mapper = mock(DifyAgentMapper.class);
        var client = mock(WebHdfsArchiveClient.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<WebHdfsArchiveClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        var date = LocalDate.of(2026, 1, 1);
        var end = date.plusMonths(2);
        var capturedAt = date.atTime(9, 0);
        var sync = end.atStartOfDay();
        var file = new ArchiveFile("6fb25de8-fc80-4b4e-95ed-2f34ef42d449", end, sync);
        when(mapper.archiveCoverage(date.atStartOfDay(), end.atStartOfDay(), 5L, null))
                .thenReturn(new ArchiveCoverage(sync, 0, true));
        when(mapper.archiveFiles(date.atStartOfDay(), end.atStartOfDay(), 5L, null, 2001)).thenReturn(List.of(file));
        var initial = new ArchiveEvent(1, 1, 11, 3L, 5, 5L, null, capturedAt, "SMS_ALERT", "c1", "CONFIRMED",
                Source.DIRECT_REVIEW, capturedAt);
        var revoked = new ArchiveEvent(1, 2, 11, 3L, 5, 5L, null, capturedAt, "SMS_ALERT", "c1", "REVOKED",
                Source.APPEAL, capturedAt.plusDays(2));
        var confirmed = new ArchiveEvent(2, 1, 12, 7L, 6, 5L, null, capturedAt, "BACKLOG", null, "CONFIRMED",
                Source.DIRECT_REVIEW, capturedAt);
        var lines = json.writeValueAsString(initial) + "\n" + json.writeValueAsString(revoked) + "\n"
                + json.writeValueAsString(confirmed) + "\n";
        when(client.readAlertBatch(file.id(), end)).thenReturn(lines.getBytes(StandardCharsets.UTF_8));
        when(mapper.driverLabels(Set.of(7L))).thenReturn(List.of(new DriverLabel(7, "测试司机")));
        var summary = new DifyViolationService(mapper, provider, json).summary(date, end, 5L, null, 10, 1, 20);
        assertThat(summary.total()).isEqualTo(1);
        assertThat(summary.byMonth()).containsExactly(new Bucket("2026-01", 1), new Bucket("2026-02", 0));
        assertThat(summary.byBehavior()).containsExactly(new Bucket("UNCLASSIFIED", 1));
        assertThat(summary.records()).extracting(ViolationRow::alertId).containsExactly(2L);
    }

    private static void admin() {
        var principal = new LoginUser(1L, "PLATFORM_ADMIN", null);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }
}
