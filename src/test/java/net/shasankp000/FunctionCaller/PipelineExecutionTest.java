package net.shasankp000.FunctionCaller;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PipelineExecutionTest {
    private JsonObject action(String name, String... pairs) {
        JsonObject action = new JsonObject();
        action.addProperty("functionName", name);
        JsonArray params = new JsonArray();
        for (int i = 0; i < pairs.length; i += 2) {
            JsonObject param = new JsonObject();
            param.addProperty("parameterName", pairs[i]);
            param.addProperty("parameterValue", pairs[i + 1]);
            params.add(param);
        }
        action.add("parameters", params);
        return action;
    }
    private JsonObject goTo() {
        return action("goTo", "x", "$lastDetectedBlock.x", "y", "$lastDetectedBlock.y", "z", "$lastDetectedBlock.z");
    }

    @Test void searchCoordinatesReachBothNavigationAndMining() {
        Map<String, Object> state = new HashMap<>();
        SearchResultState.publish(state, 7, 70, -132, "oak_log");
        assertEquals(Map.of("x", "7", "y", "70", "z", "-132"), PipelineSchema.resolve(goTo(), state));
        JsonObject mining = action("mineBlock", "targetX", "$lastDetectedBlock.x", "targetY", "$lastDetectedBlock.y", "targetZ", "$lastDetectedBlock.z");
        assertEquals(Map.of("targetX", "7", "targetY", "70", "targetZ", "-132"), PipelineSchema.resolve(mining, state));
        assertEquals(7, state.get("found_block_x"));
        assertEquals(-132, state.get("foundBlock.z"));
    }

    @Test void failedSearchRemovesAllPriorCoordinatesAndPreventsDispatch() {
        Map<String, Object> state = new HashMap<>();
        SearchResultState.publish(state, 7, 70, -132, "oak_log");
        SearchResultState.clear(state);
        assertThrows(MissingDependencyException.class, () -> PipelineSchema.resolve(goTo(), state));
        assertFalse((Boolean) state.get("search_success"));
        assertFalse(state.containsKey("found_block_x"));
        assertFalse(state.containsKey("foundBlock.x"));
    }

    @Test void missingValuesFailButZeroCoordinatesArePreserved() {
        assertThrows(MissingDependencyException.class, () -> PipelineSchema.resolveValue(null, Map.of()));
        assertThrows(MissingDependencyException.class, () -> PipelineSchema.resolveValue("$missing", Map.of()));
        assertEquals("0", PipelineSchema.resolveValue("0", Map.of()));
        assertEquals("0", PipelineSchema.resolveValue("$x", Map.of("x", 0)));
        Map<String, Object> state = new HashMap<>();
        SearchResultState.publish(state, 0, 0, 0, "oak_log");
        assertEquals(Map.of("x", "0", "y", "0", "z", "0"), PipelineSchema.resolve(goTo(), state));
    }

    @Test void standaloneInitialAndRecoveryActionsUseIdenticalValidatedPipeline() {
        JsonObject action = action("goTo", "x", "7", "y", "70", "z", "-132");
        JsonObject normalized = PipelineSchema.normalize(action);
        assertEquals(action, normalized.getAsJsonArray("pipeline").get(0));
        assertEquals(normalized, PipelineSchema.normalize(normalized));
        assertFalse(normalized.has("functionName"));
    }

    @Test void entireRecoveryIsRejectedBeforeAnyPrefixCanExecute() {
        JsonArray pipeline = new JsonArray();
        pipeline.add(action("goTo", "x", "7", "y", "70", "z", "-132"));
        pipeline.add(action("mineBlock", "targetX", "7", "targetY", "70"));
        JsonObject response = new JsonObject();
        response.add("pipeline", pipeline);
        assertThrows(IllegalArgumentException.class, () -> PipelineSchema.normalize(response));
        assertThrows(IllegalArgumentException.class, () -> PipelineSchema.normalize(action("unknown")));
        assertThrows(IllegalArgumentException.class, () -> PipelineSchema.normalize(action("goTo", "x", "0", "x", "1", "y", "0", "z", "0")));
        assertThrows(IllegalArgumentException.class, () -> PipelineSchema.normalize(action("goTo", "x", "NaN", "y", "70", "z", "0")));
        response.addProperty("clarification", "Where?");
        assertThrows(IllegalArgumentException.class, () -> PipelineSchema.normalize(response));
    }

    @Test void actionOutputsAndPreviousRequestsCannotContaminateEachOther() throws Exception {
        ActionExecutionState first = new ActionExecutionState();
        ActionExecutionState second = new ActionExecutionState();
        first.output = "Bot moved to position";
        CompletableFuture.runAsync(() -> {
            SearchResultState.publish(second.values, 7, 70, -132, "oak_log");
            second.output = "Found oak_log";
        }).get();
        assertEquals("Bot moved to position", first.output);
        assertTrue(first.values.isEmpty());
        first.beginAction();
        assertEquals("", first.output);
        assertEquals("Found oak_log", second.output);
        second.reset();
        assertEquals("", second.output);
        assertThrows(MissingDependencyException.class, () -> PipelineSchema.resolve(goTo(), second.values));
    }

    @Test void concurrentRequestsForSameBotAreRejectedButOtherBotsCanRun() {
        UUID bot = UUID.randomUUID();
        try (var first = ExecutionLease.acquire(bot); var other = ExecutionLease.acquire(UUID.randomUUID())) {
            assertThrows(IllegalStateException.class, () -> ExecutionLease.acquire(bot));
        }
        try (var next = ExecutionLease.acquire(bot)) { assertNotNull(next); }
    }

    @Test void recoveryAndActionBudgetsCannotLoopIndefinitely() {
        ActionExecutionState state = new ActionExecutionState();
        for (int i = 0; i < 3; i++) state.correction();
        assertThrows(IllegalStateException.class, state::correction);
        for (int i = 0; i < 64; i++) state.nextAction();
        assertThrows(IllegalStateException.class, state::nextAction);
    }

    @Test void scheduledOrFailedNavigationCannotPassVerification() {
        var verifier = ToolVerifiers.VERIFIER_REGISTRY.get("goTo");
        assertFalse(verifier.verify(Map.of(), Map.of("botPosition.x", 0, "botPosition.y", 0, "botPosition.z", 0), null).success);
        assertFalse(verifier.verify(Map.of(), Map.of("navigation.reached", false), null).success);
        assertTrue(verifier.verify(Map.of(), Map.of("navigation.reached", true), null).success);
    }

    @Test void registrySearchUpdaterPublishesCanonicalCoordinatesAndClearsFailure() {
        for (String name : List.of("searchBlocks", "detectBlocks")) {
            Tool tool = ToolRegistry.TOOLS.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
            Map<String, Object> state = new HashMap<>();
            tool.stateUpdater().update(state, Map.of("blockType", "oak_log"), new net.minecraft.core.BlockPos(7, 70, -132));
            assertEquals("7", PipelineSchema.resolve(goTo(), state).get("x"));
            tool.stateUpdater().update(state, Map.of("blockType", "oak_log"), null);
            assertThrows(MissingDependencyException.class, () -> PipelineSchema.resolve(goTo(), state));
        }
    }

    @Test void invalidLiteralSearchRangeIsRejectedBeforeDispatch() {
        assertThrows(IllegalArgumentException.class, () -> PipelineSchema.normalize(action("searchBlocks",
                "blockType", "oak_log", "initialRadius", "40", "maxRadius", "10", "radiusIncrement", "10")));
        assertThrows(IllegalArgumentException.class, () -> PipelineSchema.normalize(action("searchBlocks",
                "blockType", "oak_log", "initialRadius", "10", "maxRadius", "40", "radiusIncrement", "0")));
    }
}
