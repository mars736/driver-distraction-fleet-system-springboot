package mapper;

import dto.operations.TransportOrders.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import service.rating.DriverSafetyRating.Level;
import service.rating.DriverSafetyRating.SafetyRatingView;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TransportOrderMapper {
    OrderRow detail(@Param("id") long id);

    Long lock(@Param("id") long id);

    void create(@Param("draft") Draft draft, @Param("orderNo") String orderNo,
                @Param("actor") long actor, @Param("at") LocalDateTime at);

    int update(@Param("id") long id, @Param("draft") Draft draft, @Param("version") long version,
               @Param("at") LocalDateTime at);

    int cancel(@Param("id") long id, @Param("version") long version, @Param("at") LocalDateTime at);

    int assign(@Param("id") long id, @Param("taskId") long taskId, @Param("version") long version,
               @Param("at") LocalDateTime at);

    List<OrderRow> list(@Param("fleetId") Long fleetId, @Param("status") Status status,
                        @Param("requirement") Requirement requirement,
                        @Param("offset") long offset, @Param("size") int size);

    long count(@Param("fleetId") Long fleetId, @Param("status") Status status,
               @Param("requirement") Requirement requirement);

    Replay replay(@Param("orderId") long orderId, @Param("requestKey") String requestKey);

    List<CandidateRow> candidates(@Param("order") OrderRow order, @Param("at") LocalDateTime at,
                                  @Param("offset") long offset, @Param("size") int size,
                                  @Param("acceptedLevels") List<Level> acceptedLevels);

    int saveDispatchRating(@Param("taskId") long taskId, @Param("rating") SafetyRatingView rating);

    List<DispatchRatingRow> dispatchRatings(@Param("orderId") long orderId);

    long countCandidates(@Param("fleetId") long fleetId);
}
