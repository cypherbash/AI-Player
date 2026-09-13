package net.shasankp000.FunctionCaller;

import java.util.Map;

/** Canonical coordinates plus compatibility aliases for planner consumers. */
final class SearchResultState {
    static void clear(Map<String, Object> state) {
        for (String axis : new String[]{"x", "y", "z"}) {
            state.remove("lastDetectedBlock." + axis);
            state.remove("foundBlock." + axis);
            state.remove("found_block_" + axis);
        }
        state.remove("foundBlock.type");
        state.remove("found_block_type");
        state.put("search_success", false);
    }
    static void publish(Map<String, Object> state, int x, int y, int z, String type) {
        clear(state);
        int[] coordinates = {x, y, z};
        String[] axes = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) {
            state.put("lastDetectedBlock." + axes[i], coordinates[i]);
            state.put("foundBlock." + axes[i], coordinates[i]);
            state.put("found_block_" + axes[i], coordinates[i]);
        }
        state.put("foundBlock.type", type);
        state.put("found_block_type", type);
        state.put("search_success", true);
    }
}
