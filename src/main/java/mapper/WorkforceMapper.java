package mapper;

import dto.operations.Workforce.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface WorkforceMapper {
    Shift shift(@Param("driverId") long driverId, @Param("date") LocalDate date);

    List<Shift> shifts(@Param("driverId") long driverId, @Param("start") LocalDate start, @Param("end") LocalDate end);

    boolean shiftOverlap(@Param("driverId") long driverId,
            @Param("date") LocalDate date,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    void insertShift(@Param("driverId") long driverId,
            @Param("fleetId") long fleetId,
            @Param("date") LocalDate date,
            @Param("type") ShiftType type,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    int updateShift(@Param("id") long id, @Param("version") long version, @Param("fleetId") long fleetId,
            @Param("type") ShiftType type, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    boolean restOverlapExcept(@Param("driverId") long driverId, @Param("date") LocalDate date,
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    Shift shiftById(@Param("id") long id);

    void recordShiftRevision(@Param("before") Shift before, @Param("after") Shift after,
            @Param("actor") long actor, @Param("at") LocalDateTime at);

    List<ShiftRevision> shiftRevisions(@Param("id") long id);

    Attendance attendance(@Param("shiftId") long shiftId);

    List<Attendance> attendanceRange(@Param("driverId") long driverId, @Param("start") LocalDate start, @Param("end") LocalDate end);

    void clockIn(@Param("shiftId") long shiftId,
            @Param("now") LocalDateTime now,
            @Param("position") Position position);

    int clockOut(@Param("shiftId") long shiftId,
            @Param("now") LocalDateTime now,
            @Param("position") Position position);

    boolean punchedOnDuty(@Param("id") long id, @Param("now") LocalDateTime now);

    List<DutyDriver> onDuty(@Param("fleetId") Long fleetId,
            @Param("now") LocalDateTime now,
            @Param("offset") long offset,
            @Param("size") int size);

    Leave leave(@Param("id") long id);

    List<Leave> leaves(@Param("driverId") Long driverId,
            @Param("fleetId") Long fleetId,
            @Param("status") LeaveStatus status,
            @Param("beforeId") Long beforeId,
            @Param("offset") long offset,
            @Param("size") int size);

    List<Leave> approved(@Param("driverId") long driverId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    boolean leaveOverlap(@Param("driverId") long driverId,
            @Param("exceptId") long exceptId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    boolean onLeave(@Param("driverId") long driverId, @Param("now") LocalDateTime now);

    void requestLeave(@Param("driverId") long driverId,
            @Param("fleetId") long fleetId,
            @Param("input") LeaveRequest input,
            @Param("now") LocalDateTime now);

    int decideLeave(@Param("id") long id,
            @Param("status") LeaveStatus status,
            @Param("admin") long admin,
            @Param("now") LocalDateTime now,
            @Param("note") String note);

    boolean runningDriver(@Param("id") long id);

    Task task(@Param("id") long id);

    List<Task> tasks(@Param("driverId") Long driverId,
            @Param("fleetId") Long fleetId,
            @Param("status") TaskStatus status,
            @Param("beforeId") Long beforeId,
            @Param("offset") long offset,
            @Param("size") int size);

    List<Task> actualTasks(@Param("driverId") long driverId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("now") LocalDateTime now);

    boolean taskConflict(@Param("driverId") long driverId,
            @Param("vehicleId") Long vehicleId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("now") LocalDateTime now);

    boolean running(@Param("driverId") long driverId,
            @Param("vehicleId") long vehicleId,
            @Param("exceptId") long exceptId);

    void createTask(@Param("input") TaskRequest input,
            @Param("fleetId") long fleetId,
            @Param("actor") long actor,
            @Param("now") LocalDateTime now,
            @Param("requestKey") String requestKey);

    int depart(@Param("id") long id, @Param("now") LocalDateTime now, @Param("position") Position position);

    int finishTask(@Param("id") long id, @Param("now") LocalDateTime now, @Param("position") Position position);

    int cancelTask(@Param("id") long id, @Param("now") LocalDateTime now);

    boolean restOverlap(@Param("driverId") long driverId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    boolean workShiftOverlap(@Param("driverId") long driverId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    Task taskByRequest(@Param("actor") long actor, @Param("requestKey") String requestKey);
}
