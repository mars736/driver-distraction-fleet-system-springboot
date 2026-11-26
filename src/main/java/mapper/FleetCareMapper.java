package mapper;

import dto.operations.FleetCare.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FleetCareMapper {
    Odometer odometer(@Param("vehicleId") long vehicleId);

    void saveOdometer(@Param("id") long id, @Param("input") OdometerRequest input);

    Plan plan(@Param("id") long id);

    List<Plan> plans(@Param("vehicleId") long vehicleId, @Param("offset") long offset, @Param("size") int size);

    void createPlan(@Param("vehicleId") long vehicleId,
            @Param("input") PlanRequest input,
            @Param("nextDate") LocalDate nextDate,
            @Param("nextKm") BigDecimal nextKm);

    int completePlan(@Param("id") long id,
            @Param("input") ServiceCompletion input,
            @Param("nextDate") LocalDate nextDate,
            @Param("nextKm") BigDecimal nextKm);

    Repair repair(@Param("id") long id);

    List<Repair> repairs(@Param("vehicleId") long vehicleId,
            @Param("status") RepairStatus status,
            @Param("offset") long offset,
            @Param("size") int size);

    void reportRepair(@Param("vehicleId") long vehicleId,
            @Param("input") RepairRequest input,
            @Param("actor") long actor,
            @Param("now") LocalDateTime now);

    int transitionRepair(@Param("id") long id,
            @Param("expected") RepairStatus expected,
            @Param("input") RepairTransition input,
            @Param("now") LocalDateTime now,
            @Param("appliedCycle") Integer appliedCycle);

    boolean fuelTimeExists(@Param("vehicle") long vehicle, @Param("at") LocalDateTime at, @Param("exceptId") long exceptId);

    Long fuelVehicle(@Param("id") long id);

    void insertFuel(@Param("vehicleId") long vehicleId,
            @Param("input") FuelRequest input,
            @Param("amount") BigDecimal amount);

    int updateFuel(@Param("id") long id,
            @Param("vehicleId") long vehicleId,
            @Param("input") FuelRequest input,
            @Param("amount") BigDecimal amount);

    boolean openRepairExists(@Param("vehicleId") long vehicleId);

    int reopenRepair(@Param("id") long id, @Param("now") LocalDateTime now);

    void recordRepairEvent(@Param("from") RepairStatus from,
            @Param("snapshot") Repair snapshot,
            @Param("to") RepairStatus to,
            @Param("reason") String reason,
            @Param("actor") long actor,
            @Param("at") LocalDateTime at);

    List<RepairEvent> repairEvents(@Param("id") long id);

    int setPlanEnabled(@Param("id") long id, @Param("enabled") boolean enabled);

    FuelSnapshot fuelSnapshot(@Param("id") long id);

    int voidFuel(@Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") LocalDateTime now);

    void recordFuelRevision(@Param("id") long id,
            @Param("version") long version,
            @Param("action") String action,
            @Param("actor") long actor,
            @Param("reason") String reason,
            @Param("before") String before,
            @Param("after") String after,
            @Param("now") LocalDateTime now);

    List<FuelRevision> fuelRevisions(@Param("id") long id);

    List<Fuel> fuel(@Param("vehicleId") long vehicleId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("offset") long offset,
            @Param("size") int size);

    PeerRate peerRate(@Param("fleetId") long fleetId,
            @Param("model") String model,
            @Param("vehicleId") long vehicleId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);
}
