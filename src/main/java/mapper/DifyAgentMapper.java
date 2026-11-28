package mapper;

import dto.operations.DifyAgent.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Mapper
public interface DifyAgentMapper {
    List<FleetOption> fleets(@Param("offset") int offset, @Param("limit") int limit);
    List<DriverOption> drivers(@Param("fleetId") Long fleetId, @Param("offset") int offset, @Param("limit") int limit);
    List<DriverLabel> driverLabels(@Param("ids") Set<Long> ids);
    int saveDraft(DraftRow draft);
    DraftRow lockDraft(@Param("id") String id, @Param("actorId") long actorId);
    int completeDraft(@Param("id") String id, @Param("result") String result);
    void purgeOldDrafts(@Param("before") LocalDateTime before);

    List<ArchiveFile> archiveFiles(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end,
                                  @Param("fleetId") Long fleetId, @Param("driverId") Long driverId,
                                  @Param("limit") int limit);
    ArchiveCoverage archiveCoverage(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end,
                                    @Param("fleetId") Long fleetId, @Param("driverId") Long driverId);
}
