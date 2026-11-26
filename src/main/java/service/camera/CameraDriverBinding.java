package service.camera;

import lombok.RequiredArgsConstructor;
import mapper.UserMapper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CameraDriverBinding {
    private final UserMapper users;

    public enum State { BOUND, UNBOUND, AMBIGUOUS }
    public record Binding(State state, Long driverId) { }

    public Binding current(long vehicleId) {
        var drivers = users.findActiveDriversByVehicle(vehicleId);
        return switch (drivers.size()) {
            case 0 -> new Binding(State.UNBOUND, null);
            case 1 -> new Binding(State.BOUND, drivers.getFirst());
            default -> new Binding(State.AMBIGUOUS, null);
        };
    }
}
