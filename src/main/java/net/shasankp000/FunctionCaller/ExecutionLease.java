package net.shasankp000.FunctionCaller;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** A bot cannot execute two request pipelines at once. Different bots remain independent. */
final class ExecutionLease implements AutoCloseable {
    private static final Set<UUID> ACTIVE = ConcurrentHashMap.newKeySet();
    private final UUID bot;
    private ExecutionLease(UUID bot) { this.bot = bot; }
    static ExecutionLease acquire(UUID bot) {
        if (!ACTIVE.add(bot)) throw new IllegalStateException("Bot already has an active action request");
        return new ExecutionLease(bot);
    }
    static boolean isActive(UUID bot) { return ACTIVE.contains(bot); }
    @Override public void close() { ACTIVE.remove(bot); }
}
