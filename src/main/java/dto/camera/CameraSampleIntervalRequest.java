package dto.camera;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record CameraSampleIntervalRequest(@NotNull Double sampleIntervalSeconds,
                                          @NotNull @PositiveOrZero Long configVersion) { }
