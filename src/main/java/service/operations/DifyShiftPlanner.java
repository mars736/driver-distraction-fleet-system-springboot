package service.operations;

import dto.operations.DifyAgent.*;
import dto.operations.Workforce.Leave;
import dto.operations.Workforce.Shift;
import dto.operations.Workforce.ShiftType;
import lombok.RequiredArgsConstructor;
import mapper.DifyAgentMapper;
import mapper.WorkforceMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dto.operations.Workforce.ShiftType.*;
import static service.operations.OperationsAccess.*;

@Service
@RequiredArgsConstructor
public class DifyShiftPlanner {
    private final DifyAgentMapper agent;
    private final WorkforceMapper workforce;

    public SchedulePlan plan(ScheduleRequest request, LocalDateTime now) {
        dateRange(request.startDate(), request.endDate());
        check(!request.startDate().isBefore(now.toLocalDate()), 400, "自动排班起始日期不能是历史日期");
        check(!request.endDate().isAfter(request.startDate().plusDays(14)), 400, "单次自动排班最多 14 天");
        check(request.earlyPerDay() + request.latePerDay() > 0, 400, "请提供至少一种班次的需求人数");

        var drivers = agent.drivers(request.fleetId(), 0, 501);
        check(drivers.size() <= 500, 422, "该车队超过单次自动排班的 500 人上限");
        var calendars = drivers.stream().map(driver -> new Calendar(driver,
                workforce.shifts(driver.id(), request.startDate().minusDays(1), request.endDate().plusDays(1)),
                workforce.approved(driver.id(), request.startDate().atStartOfDay(), request.endDate().atStartOfDay())))
                .toList();
        var counts = new HashMap<Long, Integer>();
        for (var calendar : calendars) {
            int assigned = (int) calendar.shifts().stream().filter(shift -> shift.shiftType() != REST
                    && !shift.shiftDate().isBefore(request.startDate()) && shift.shiftDate().isBefore(request.endDate()))
                    .count();
            counts.put(calendar.driver().id(), assigned);
        }

        var proposed = new ArrayList<ProposedShift>();
        var shortages = new ArrayList<ShiftShortage>();
        int existingSlots = 0;
        for (var date = request.startDate(); date.isBefore(request.endDate()); date = date.plusDays(1)) {
            var early = new Slot(date, EARLY, date.atTime(8, 0), date.atTime(16, 0));
            var late = new Slot(date, LATE, date.atTime(16, 0), date.plusDays(1).atStartOfDay());
            var earlyEligible = eligible(calendars, early, now);
            var lateEligible = eligible(calendars, late, now);
            int earlyExisting = Math.min(request.earlyPerDay(), existing(calendars, early, request.fleetId()));
            int lateExisting = Math.min(request.latePerDay(), existing(calendars, late, request.fleetId()));
            existingSlots += earlyExisting + lateExisting;
            var used = new HashSet<Long>();
            fill(early, request.earlyPerDay() - earlyExisting, earlyEligible, lateEligible.keySet(),
                    counts, used, proposed, shortages);
            fill(late, request.latePerDay() - lateExisting, lateEligible, Set.of(),
                    counts, used, proposed, shortages);
        }
        return new SchedulePlan(request.fleetId(), existingSlots, List.copyOf(proposed), List.copyOf(shortages));
    }

    private static int existing(List<Calendar> calendars, Slot slot, long fleetId) {
        return (int) calendars.stream().filter(calendar -> calendar.shifts().stream().anyMatch(shift ->
                shift.fleetId() == fleetId && shift.shiftDate().equals(slot.date()) && shift.shiftType() == slot.type())).count();
    }

    private static Map<Long, DriverOption> eligible(List<Calendar> calendars, Slot slot, LocalDateTime now) {
        var eligible = new HashMap<Long, DriverOption>();
        if (slot.start().isBefore(now)) { return eligible; }
        for (var calendar : calendars) {
            if (calendar.shifts().stream().anyMatch(shift -> slot.conflicts(shift))
                    || calendar.leaves().stream().anyMatch(leave -> slot.overlaps(leave.startsAt(), leave.endsAt()))) {
                continue;
            }
            eligible.put(calendar.driver().id(), calendar.driver());
        }
        return eligible;
    }

    private static void fill(Slot slot, int required, Map<Long, DriverOption> eligible, Set<Long> alternative,
                             Map<Long, Integer> counts, Set<Long> used, List<ProposedShift> proposed,
                             List<ShiftShortage> shortages) {
        if (required == 0) { return; }
        // 先安排只能上这一班的人，再按本周期工作班数量少者优先。
        var selected = eligible.values().stream().filter(driver -> !used.contains(driver.id()))
                .sorted(Comparator.comparing((DriverOption driver) -> alternative.contains(driver.id()))
                        .thenComparingInt(driver -> counts.get(driver.id())).thenComparingLong(DriverOption::id))
                .limit(required).toList();
        for (var driver : selected) {
            proposed.add(new ProposedShift(driver.id(), driver.name(), slot.date(), slot.type(), slot.start(), slot.end()));
            used.add(driver.id());
            counts.merge(driver.id(), 1, Integer::sum);
        }
        if (selected.size() < required) {
            shortages.add(new ShiftShortage(slot.date(), slot.type(), required - selected.size()));
        }
    }

    private record Calendar(DriverOption driver, List<Shift> shifts, List<Leave> leaves) { }

    private record Slot(LocalDate date, ShiftType type, LocalDateTime start, LocalDateTime end) {
        boolean conflicts(Shift shift) {
            if (shift.shiftDate().equals(date)) { return true; }
            return shift.shiftType() != REST && overlaps(shift.startsAt(), shift.endsAt());
        }

        boolean overlaps(LocalDateTime otherStart, LocalDateTime otherEnd) {
            return otherStart.isBefore(end) && otherEnd.isAfter(start);
        }
    }
}
