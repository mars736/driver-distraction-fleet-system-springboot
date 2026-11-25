package service.alert.live;

import common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

class AlertStreamTicketsTest {
    private static final Instant NOW = Instant.parse("2026-11-25T00:00:00Z");
    private final AlertStreamTickets tickets = new AlertStreamTickets();
    private final AlertStreamTickets.Identity identity =
            new AlertStreamTickets.Identity(7, "DRIVER", 3, NOW.plusSeconds(60));

    @Test
    void ticketExpiresAtThirtySecondsAndCannotOutliveToken() {
        var ticket = tickets.issue(identity, NOW);
        assertThat(ticket.expiresAt()).isEqualTo(NOW.plusSeconds(30));
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> tickets.consume(ticket.value(), ticket.expiresAt()))
                .satisfies(error -> assertThat(error.getCode()).isEqualTo(401));

        var expiring = new AlertStreamTickets.Identity(7, "DRIVER", 3, NOW.plusSeconds(5));
        var shortTicket = tickets.issue(expiring, NOW);
        assertThat(shortTicket.expiresAt()).isEqualTo(expiring.tokenExpiresAt());
        assertThat(tickets.consume(shortTicket.value(), NOW.plusSeconds(4))).isEqualTo(expiring);
    }

    @Test
    void twoConcurrentRedemptionsAllowExactlyOneConnectionIdentity() throws Exception {
        var ticket = tickets.issue(identity, NOW);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> tryConsume(ticket.value()));
            var second = pool.submit(() -> tryConsume(ticket.value()));
            assertThat(Stream.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void outstandingTicketLimitRecoversWhenOldTicketsExpire() {
        for (int i = 0; i < 4; i++) {
            tickets.issue(identity, NOW);
        }
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> tickets.issue(identity, NOW))
                .satisfies(error -> assertThat(error.getCode()).isEqualTo(429));
        assertThat(tickets.issue(identity, NOW.plusSeconds(30))).isNotNull();
    }

    private boolean tryConsume(String value) {
        try {
            assertThat(tickets.consume(value, NOW)).isEqualTo(identity);
            return true;
        } catch (BusinessException error) {
            assertThat(error.getCode()).isEqualTo(401);
            return false;
        }
    }
}
