package dto.score;

import jakarta.validation.constraints.*;
import lombok.Data;

/** month 不填时使用北京时间的当前月；分页只用于明细和排名。 */
@Data
public class ScoreQueryRequest {
    @Pattern(regexp = "[0-9]{4}-[0-9]{2}") private String month;
    @Positive private Long fleetId;
    @Min(1) private int page = 1;
    @Min(1) @Max(100) private int pageSize = 10;
}
