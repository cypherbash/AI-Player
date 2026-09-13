package net.shasankp000.FunctionCaller;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Owned by one request. Output is reset for each action; only structured values cross steps. */
final class ActionExecutionState {
    final Map<String, Object> values = new ConcurrentHashMap<>();
    volatile String output = "";
    private int actions;
    private int corrections;
    void beginAction() { output = ""; }
    void reset() { values.clear(); output = ""; actions = 0; corrections = 0; }
    void nextAction() {
        if (++actions > 64) throw new IllegalStateException("Action budget exhausted");
    }
    void correction() {
        if (++corrections > 3) throw new IllegalStateException("Recovery budget exhausted");
    }
}
