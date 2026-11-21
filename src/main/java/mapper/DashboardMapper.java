package mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vo.dashboard.DashboardView.*;

import java.util.List;

@Mapper
public interface DashboardMapper {
    FleetSize fleetSize(@Param("fleetId") Long fleetId);
    List<Bucket> recognitionBuckets(Scope scope);
    Alerts alertCounts(Scope scope);
    List<RecentAlert> recentAlerts(long driverId);
    String vehicleAlertState(long vehicleId);
}
