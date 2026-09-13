package net.shasankp000.PathFinding;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Epochs invalidate queued work as well as active sessions; versions are never reused. */
final class NavigationCancellation {
    private final ConcurrentHashMap<UUID, AtomicLong> versions = new ConcurrentHashMap<>();
    long version(UUID bot) { return versions.computeIfAbsent(bot, ignored -> new AtomicLong()).get(); }
    void cancel(UUID bot) { versions.computeIfAbsent(bot, ignored -> new AtomicLong()).incrementAndGet(); }
    void cancelAll() { versions.values().forEach(AtomicLong::incrementAndGet); }
    boolean isCurrent(UUID bot, long version) { return version(bot) == version; }
}
