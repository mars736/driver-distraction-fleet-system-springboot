package mapper;

import dto.operations.Workforce.*;
import org.apache.ibatis.annotations.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface WorkforceMapper {
    @Select("""
            SELECT id, driver_id, fleet_id, shift_date, shift_type, starts_at, ends_at
            FROM driver_shift WHERE driver_id=#{driverId} AND shift_date=#{date}
            """)
    Shift shift(long driverId, LocalDate date);

    @Select("""
            SELECT id, driver_id, fleet_id, shift_date, shift_type, starts_at, ends_at
            FROM driver_shift WHERE driver_id=#{driverId} AND shift_date>=#{start} AND shift_date<#{end}
            ORDER BY shift_date
            """)
    List<Shift> shifts(long driverId, LocalDate start, LocalDate end);

    @Select("""
            SELECT EXISTS(SELECT 1 FROM driver_shift WHERE driver_id=#{driverId}
                AND shift_date<>#{date} AND starts_at<#{end} AND ends_at>#{start})
            """)
    boolean shiftOverlap(long driverId, LocalDate date, LocalDateTime start, LocalDateTime end);

    @Insert("""
            INSERT INTO driver_shift(driver_id,fleet_id,shift_date,shift_type,starts_at,ends_at)
            VALUES(#{driverId},#{fleetId},#{date},#{type},#{start},#{end})
            ON DUPLICATE KEY UPDATE fleet_id=#{fleetId},shift_type=#{type},starts_at=#{start},ends_at=#{end}
            """)
    void saveShift(long driverId, long fleetId, LocalDate date, ShiftType type, LocalDateTime start, LocalDateTime end);

    @Select("""
            SELECT shift_id,clock_in_at,clock_in_lat,clock_in_lon,clock_out_at,clock_out_lat,clock_out_lon
            FROM driver_attendance WHERE shift_id=#{shiftId}
            """)
    Attendance attendance(long shiftId);

    @Select("""
            SELECT a.shift_id,a.clock_in_at,a.clock_in_lat,a.clock_in_lon,a.clock_out_at,a.clock_out_lat,a.clock_out_lon
            FROM driver_attendance a JOIN driver_shift s ON s.id=a.shift_id
            WHERE s.driver_id=#{driverId} AND s.shift_date>=#{start} AND s.shift_date<#{end}
            ORDER BY s.shift_date
            """)
    List<Attendance> attendanceRange(long driverId, LocalDate start, LocalDate end);

    @Insert("""
            INSERT INTO driver_attendance(shift_id,clock_in_at,clock_in_lat,clock_in_lon)
            VALUES(#{shiftId},#{now},#{position.latitude},#{position.longitude})
            """)
    void clockIn(long shiftId, LocalDateTime now, Position position);

    @Update("""
            UPDATE driver_attendance SET clock_out_at=#{now},clock_out_lat=#{position.latitude},
                clock_out_lon=#{position.longitude} WHERE shift_id=#{shiftId} AND clock_out_at IS NULL
            """)
    int clockOut(long shiftId, LocalDateTime now, Position position);

    @Select("""
            SELECT EXISTS(SELECT 1 FROM driver_shift s JOIN driver_attendance a ON a.shift_id=s.id
                WHERE s.driver_id=#{id} AND s.starts_at<=#{now} AND s.ends_at>#{now} AND a.clock_out_at IS NULL)
            """)
    boolean punchedOnDuty(long id, LocalDateTime now);

    @Select("""
            SELECT u.id,u.fleet_id,u.name,u.vehicle_id FROM sys_user u
            JOIN vehicle v ON v.id=u.vehicle_id AND v.fleet_id=u.fleet_id
            WHERE u.role='DRIVER' AND u.enabled=1 AND (#{fleetId} IS NULL OR u.fleet_id=#{fleetId})
              AND EXISTS(SELECT 1 FROM driver_shift s JOIN driver_attendance a ON a.shift_id=s.id
                         WHERE s.driver_id=u.id AND s.starts_at<=#{now} AND s.ends_at>#{now} AND a.clock_out_at IS NULL)
              AND NOT EXISTS(SELECT 1 FROM driver_leave l WHERE l.driver_id=u.id AND l.status='APPROVED'
                             AND l.starts_at<=#{now} AND l.ends_at>#{now})
              AND NOT EXISTS(SELECT 1 FROM transport_task t WHERE t.status='RUNNING'
                             AND (t.driver_id=u.id OR t.vehicle_id=u.vehicle_id))
            ORDER BY u.id LIMIT #{size} OFFSET #{offset}
            """)
    List<DutyDriver> onDuty(Long fleetId, LocalDateTime now, long offset, int size);

    String LEAVE = """
            SELECT id,driver_id,fleet_id,leave_type,starts_at,ends_at,reason,status,created_at,decided_by,decided_at,decision_note
            FROM driver_leave
            """;

    @Select(LEAVE + " WHERE id=#{id}")
    Leave leave(long id);

    @Select(LEAVE + """
            WHERE (#{driverId} IS NULL OR driver_id=#{driverId}) AND (#{fleetId} IS NULL OR fleet_id=#{fleetId})
              AND (#{status} IS NULL OR status=#{status})
            ORDER BY id DESC LIMIT #{size} OFFSET #{offset}
            """)
    List<Leave> leaves(Long driverId, Long fleetId, LeaveStatus status, long offset, int size);

    @Select(LEAVE + """
            WHERE driver_id=#{driverId} AND status='APPROVED' AND starts_at<#{end} AND ends_at>#{start}
            ORDER BY starts_at,id
            """)
    List<Leave> approved(long driverId, LocalDateTime start, LocalDateTime end);

    @Select("""
            SELECT EXISTS(SELECT 1 FROM driver_leave WHERE driver_id=#{driverId} AND id<>#{exceptId}
                AND status='APPROVED' AND starts_at<#{end} AND ends_at>#{start})
            """)
    boolean leaveOverlap(long driverId, long exceptId, LocalDateTime start, LocalDateTime end);

    @Select("""
            SELECT EXISTS(SELECT 1 FROM driver_leave WHERE driver_id=#{driverId} AND status='APPROVED'
                AND starts_at<=#{now} AND ends_at>#{now})
            """)
    boolean onLeave(long driverId, LocalDateTime now);

    @Insert("""
            INSERT INTO driver_leave(driver_id,fleet_id,leave_type,starts_at,ends_at,reason,status,created_at)
            VALUES(#{driverId},#{fleetId},#{input.type},#{input.startsAt},#{input.endsAt},#{input.reason},'PENDING',#{now})
            """)
    void requestLeave(long driverId, long fleetId, LeaveRequest input, LocalDateTime now);

    @Update("""
            UPDATE driver_leave SET status=#{status},decided_by=#{admin},decided_at=#{now},decision_note=#{note}
            WHERE id=#{id} AND status='PENDING'
            """)
    int decideLeave(long id, LeaveStatus status, long admin, LocalDateTime now, String note);

    String TASK = """
            SELECT id,driver_id,vehicle_id,fleet_id,task_type,description,starts_at,ends_at,status,created_by,created_at,
                departed_at,depart_lat,depart_lon,ended_at,end_lat,end_lon
            FROM transport_task
            """;

    @Select("SELECT EXISTS(SELECT 1 FROM transport_task WHERE driver_id=#{id} AND status='RUNNING')")
    boolean runningDriver(long id);

    @Select(TASK + " WHERE id=#{id}")
    Task task(long id);

    @Select(TASK + """
            WHERE (#{driverId} IS NULL OR driver_id=#{driverId}) AND (#{fleetId} IS NULL OR fleet_id=#{fleetId})
              AND (#{status} IS NULL OR status=#{status})
            ORDER BY id DESC LIMIT #{size} OFFSET #{offset}
            """)
    List<Task> tasks(Long driverId, Long fleetId, TaskStatus status, long offset, int size);

    @Select(TASK + """
            WHERE driver_id=#{driverId} AND departed_at<#{end} AND COALESCE(ended_at,#{now})>#{start}
            ORDER BY departed_at,id
            """)
    List<Task> actualTasks(long driverId, LocalDateTime start, LocalDateTime end, LocalDateTime now);

    @Select("""
            SELECT EXISTS(SELECT 1 FROM transport_task WHERE status IN ('PENDING','RUNNING')
                AND (driver_id=#{driverId} OR vehicle_id=#{vehicleId})
                AND starts_at<#{end} AND ends_at>#{start})
            """)
    boolean taskConflict(long driverId, Long vehicleId, LocalDateTime start, LocalDateTime end);

    @Select("""
            SELECT EXISTS(SELECT 1 FROM transport_task WHERE status='RUNNING' AND id<>#{exceptId}
                AND (driver_id=#{driverId} OR vehicle_id=#{vehicleId}))
            """)
    boolean running(long driverId, long vehicleId, long exceptId);

    @Insert("""
            INSERT INTO transport_task(driver_id,vehicle_id,fleet_id,task_type,description,starts_at,ends_at,status,created_by,created_at)
            VALUES(#{input.driverId},#{input.vehicleId},#{fleetId},#{input.type},#{input.description},#{input.startsAt},
                #{input.endsAt},'PENDING',#{actor},#{now})
            """)
    void createTask(TaskRequest input, long fleetId, long actor, LocalDateTime now);

    @Update("""
            UPDATE transport_task SET status='RUNNING',departed_at=#{now},depart_lat=#{position.latitude},
                depart_lon=#{position.longitude} WHERE id=#{id} AND status='PENDING'
            """)
    int depart(long id, LocalDateTime now, Position position);

    @Update("""
            UPDATE transport_task SET status='COMPLETED',ended_at=#{now},end_lat=#{position.latitude},
                end_lon=#{position.longitude} WHERE id=#{id} AND status='RUNNING'
            """)
    int finishTask(long id, LocalDateTime now, Position position);

    @Update("""
            UPDATE transport_task SET status='CANCELED',ended_at=#{now}
            WHERE id=#{id} AND status IN ('PENDING','RUNNING')
            """)
    int cancelTask(long id, LocalDateTime now);
}
