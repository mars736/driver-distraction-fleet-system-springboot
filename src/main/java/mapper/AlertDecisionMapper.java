package mapper;

import entity.AlertRecord;
import entity.RecognitionRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AlertDecisionMapper {
    void ensureState(@Param("vehicleId") Long vehicleId, @Param("json") String json);
    String lockState(Long vehicleId);
    void updateState(@Param("vehicleId") Long vehicleId, @Param("json") String json);
    RecognitionRecord findRecognition(String sourceKey);
    void insertRecognition(RecognitionRecord record);
    void insertAlert(AlertRecord record);
    List<AlertRecord> pendingSms(Long recognitionId);
    int claimSms(Long id);
    void updateSmsStatus(@Param("id") Long id, @Param("status") String status);
}
