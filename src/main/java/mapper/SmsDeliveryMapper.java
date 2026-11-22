package mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import service.alert.sms.SmsDeliveryJob;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SmsDeliveryMapper {
    void enqueue(@Param("alertId") long alertId, @Param("outId") String outId);
    int skipRevoked(@Param("id") long id);
    List<SmsDeliveryJob> pending(@Param("limit") int limit);
    List<SmsDeliveryJob> due(@Param("now") LocalDateTime now, @Param("limit") int limit);
    int claim(@Param("id") long id, @Param("recipient") String recipient, @Param("now") LocalDateTime now,
              @Param("nextCheckAt") LocalDateTime nextCheckAt);
    int reserveCheck(@Param("id") long id, @Param("expected") String expected,
                     @Param("now") LocalDateTime now, @Param("nextCheckAt") LocalDateTime nextCheckAt);
    int finish(@Param("id") long id, @Param("expected") String expected, @Param("status") String status,
               @Param("providerMessageId") String providerMessageId, @Param("providerCode") String providerCode,
               @Param("nextCheckAt") LocalDateTime nextCheckAt);
}
