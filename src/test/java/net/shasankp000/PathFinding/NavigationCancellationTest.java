package net.shasankp000.PathFinding;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NavigationCancellationTest {
    @Test void cancellationInvalidatesActiveAndQueuedWorkWithoutAffectingAnotherBot() {
        NavigationCancellation cancellation = new NavigationCancellation();
        UUID bot = UUID.randomUUID(), other = UUID.randomUUID();
        long active = cancellation.version(bot), queued = cancellation.version(bot), independent = cancellation.version(other);
        cancellation.cancel(bot);
        assertFalse(cancellation.isCurrent(bot, active));
        assertFalse(cancellation.isCurrent(bot, queued));
        assertTrue(cancellation.isCurrent(other, independent));
        long replacement = cancellation.version(bot);
        assertTrue(cancellation.isCurrent(bot, replacement));
        assertFalse(cancellation.isCurrent(bot, active));
    }

    @Test void cancelAllIncludesRequestsWhichHaveNotStartedYet() {
        NavigationCancellation cancellation = new NavigationCancellation();
        UUID bot = UUID.randomUUID();
        long queued = cancellation.version(bot);
        cancellation.cancelAll();
        assertFalse(cancellation.isCurrent(bot, queued));
    }

    @Test void completionRequiresActualProximityAndANonNullDestination() {
        assertFalse(NavigationService.destinationReached(new Vec3(7, 70, -132), null));
        assertFalse(NavigationService.destinationReached(new Vec3(0, 0, 0), new Vec3(7, 70, -132)));
        assertTrue(NavigationService.destinationReached(new Vec3(7.5, 70, -131.5), new Vec3(7.5, 70, -131.5)));
    }
}
