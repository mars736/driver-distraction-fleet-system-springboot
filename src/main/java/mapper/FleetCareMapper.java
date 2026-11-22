package mapper;

import dto.operations.FleetCare.*;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FleetCareMapper {
    @Select("SELECT vehicle_id,odometer_km,measured_at FROM vehicle_odometer WHERE vehicle_id=#{vehicleId}")
    Odometer odometer(long vehicleId);

    @Insert("""
            INSERT INTO vehicle_odometer(vehicle_id,odometer_km,measured_at)
            VALUES(#{id},#{input.odometerKm},#{input.measuredAt})
            ON DUPLICATE KEY UPDATE odometer_km=#{input.odometerKm},measured_at=#{input.measuredAt}
            """)
    void saveOdometer(long id, OdometerRequest input);

    String PLAN = """
            SELECT id,vehicle_id,name,interval_days,interval_km,next_date,next_odometer_km,cycle,
                last_service_date,last_service_odometer_km FROM vehicle_maintenance_plan
            """;

    @Select(PLAN + " WHERE id=#{id}")
    Plan plan(long id);

    @Select(PLAN + " WHERE vehicle_id=#{vehicleId} ORDER BY id DESC LIMIT #{size} OFFSET #{offset}")
    List<Plan> plans(long vehicleId, long offset, int size);

    @Insert("""
            INSERT INTO vehicle_maintenance_plan(vehicle_id,name,interval_days,interval_km,next_date,next_odometer_km,
                last_service_date,last_service_odometer_km)
            VALUES(#{vehicleId},#{input.name},#{input.intervalDays},#{input.intervalKm},#{nextDate},#{nextKm},
                #{input.lastServiceDate},#{input.lastServiceOdometerKm})
            """)
    void createPlan(long vehicleId, PlanRequest input, LocalDate nextDate, BigDecimal nextKm);

    @Update("""
            UPDATE vehicle_maintenance_plan SET next_date=#{nextDate},next_odometer_km=#{nextKm},cycle=cycle+1,
                last_service_date=#{input.date},last_service_odometer_km=#{input.odometerKm}
            WHERE id=#{id} AND cycle=#{input.cycle}
            """)
    int completePlan(long id, ServiceCompletion input, LocalDate nextDate, BigDecimal nextKm);

    String REPAIR = """
            SELECT id,vehicle_id,description,status,assignee,repair_shop,cost,created_by,created_at,updated_at FROM vehicle_repair
            """;

    @Select(REPAIR + " WHERE id=#{id}")
    Repair repair(long id);

    @Select(REPAIR + """
            WHERE vehicle_id=#{vehicleId} AND (#{status} IS NULL OR status=#{status})
            ORDER BY id DESC LIMIT #{size} OFFSET #{offset}
            """)
    List<Repair> repairs(long vehicleId, RepairStatus status, long offset, int size);

    @Insert("""
            INSERT INTO vehicle_repair(vehicle_id,description,status,created_by,created_at,updated_at)
            VALUES(#{vehicleId},#{description},'REPORTED',#{actor},#{now},#{now})
            """)
    void reportRepair(long vehicleId, String description, long actor, LocalDateTime now);

    @Update("""
            UPDATE vehicle_repair SET status=#{input.status},assignee=COALESCE(#{input.assignee},assignee),
                repair_shop=COALESCE(#{input.shop},repair_shop),cost=COALESCE(#{input.cost},cost),updated_at=#{now}
            WHERE id=#{id} AND status=#{expected}
            """)
    int transitionRepair(long id, RepairStatus expected, RepairTransition input, LocalDateTime now);

    @Select("""
            SELECT EXISTS(SELECT 1 FROM fuel_record WHERE vehicle_id=#{vehicle} AND fueled_at=#{at} AND id<>#{exceptId})
            """)
    boolean fuelTimeExists(long vehicle, LocalDateTime at, long exceptId);

    @Select("SELECT vehicle_id FROM fuel_record WHERE id=#{id}")
    Long fuelVehicle(long id);

    @Insert("""
            INSERT INTO fuel_record(vehicle_id,fueled_at,liters,unit_price,amount,odometer_km)
            VALUES(#{vehicleId},#{input.fueledAt},#{input.liters},#{input.unitPrice},#{amount},#{input.odometerKm})
            """)
    void insertFuel(long vehicleId, FuelRequest input, BigDecimal amount);

    @Update("""
            UPDATE fuel_record SET fueled_at=#{input.fueledAt},liters=#{input.liters},unit_price=#{input.unitPrice},
                amount=#{amount},odometer_km=#{input.odometerKm} WHERE id=#{id} AND vehicle_id=#{vehicleId}
            """)
    void updateFuel(long id, long vehicleId, FuelRequest input, BigDecimal amount);

    String RATE = """
            CASE WHEN odometer_km > previous_odometer_km
                THEN ROUND(liters * 100 / NULLIF(odometer_km - previous_odometer_km, 0), 4) ELSE NULL END
            """;

    @Select("""
            WITH intervals AS (
                SELECT f.*, LAG(odometer_km) OVER (ORDER BY fueled_at,id) AS previous_odometer_km
                FROM fuel_record f WHERE vehicle_id=#{vehicleId} AND fueled_at<#{end}
            )
            SELECT id,vehicle_id,fueled_at,liters,unit_price,amount,odometer_km,previous_odometer_km,
            """ + RATE + """
              AS liters_per_100_km FROM intervals WHERE fueled_at>=#{start}
              ORDER BY fueled_at DESC,id DESC LIMIT #{size} OFFSET #{offset}
            """)
    List<Fuel> fuel(long vehicleId, LocalDateTime start, LocalDateTime end, long offset, int size);

    @Select("""
            WITH intervals AS (
                SELECT f.*, LAG(odometer_km) OVER (PARTITION BY f.vehicle_id ORDER BY fueled_at,f.id) AS previous_odometer_km
                FROM fuel_record f JOIN vehicle v ON v.id=f.vehicle_id
                WHERE v.fleet_id=#{fleetId} AND v.vehicle_type=#{model} AND v.id<>#{vehicleId} AND f.fueled_at<#{end}
            ), rates AS (SELECT
            """ + RATE + """
              AS value_per_100 FROM intervals WHERE fueled_at>=#{start}
            )
            SELECT COUNT(value_per_100) AS samples, AVG(value_per_100) AS average FROM rates
            """)
    PeerRate peerRate(long fleetId, String model, long vehicleId, LocalDateTime start, LocalDateTime end);
}
