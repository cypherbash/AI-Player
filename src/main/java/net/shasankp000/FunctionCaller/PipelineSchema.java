package net.shasankp000.FunctionCaller;

import com.google.gson.*;
import java.util.*;

/** Validates the complete response before dispatch, identically for initial plans and recovery. */
final class PipelineSchema {
    static JsonObject normalize(JsonElement response) {
        if (!response.isJsonObject()) throw new IllegalArgumentException("Expected an action, pipeline or clarification object");
        JsonObject object = response.getAsJsonObject();
        int kinds = (object.has("pipeline") ? 1 : 0) + (object.has("functionName") ? 1 : 0)
                + (object.has("clarification") ? 1 : 0);
        if (kinds != 1) throw new IllegalArgumentException("Expected exactly one response kind");
        if (object.has("clarification")) {
            if (!object.get("clarification").isJsonPrimitive() || object.get("clarification").getAsString().isBlank())
                throw new IllegalArgumentException("Empty clarification");
            if (!object.keySet().equals(Set.of("clarification"))) throw new IllegalArgumentException("Unexpected clarification fields");
            return object.deepCopy();
        }
        JsonArray pipeline = new JsonArray();
        if (object.has("functionName")) pipeline.add(object.deepCopy());
        else {
            if (!object.keySet().equals(Set.of("pipeline"))) throw new IllegalArgumentException("Unexpected pipeline fields");
            pipeline = object.getAsJsonArray("pipeline").deepCopy();
        }
        if (pipeline.isEmpty() || pipeline.size() > 32) throw new IllegalArgumentException("Pipeline must contain 1 to 32 actions");
        for (JsonElement element : pipeline) validate(element.getAsJsonObject());
        JsonObject normalized = new JsonObject();
        normalized.add("pipeline", pipeline);
        return normalized;
    }

    private static void validate(JsonObject step) {
        if (!step.keySet().equals(Set.of("functionName", "parameters"))) throw new IllegalArgumentException("Unexpected action fields");
        String name = step.get("functionName").getAsString();
        Tool tool = ToolRegistry.TOOLS.stream().filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown action: " + name));
        Set<String> expected = new HashSet<>();
        tool.parameters().forEach(p -> expected.add(p.name()));
        Set<String> supplied = new HashSet<>();
        Map<String, String> literals = new HashMap<>();
        for (JsonElement element : step.getAsJsonArray("parameters")) {
            JsonObject param = element.getAsJsonObject();
            String key = param.get("parameterName").getAsString();
            if (!expected.contains(key) || !supplied.add(key)) throw new IllegalArgumentException("Unknown or duplicate parameter: " + key);
            if (!param.get("parameterValue").isJsonPrimitive()) throw new IllegalArgumentException("Expected scalar parameter: " + key);
            String value = param.get("parameterValue").getAsString();
            if (value.isBlank()) throw new IllegalArgumentException("Empty parameter: " + key);
            if (!value.startsWith("$")) {
                validateValue(key, value);
                literals.put(key, value);
            }
        }
        if (name.equals("searchBlocks") && literals.containsKey("initialRadius") && literals.containsKey("maxRadius")
                && Integer.parseInt(literals.get("initialRadius")) > Integer.parseInt(literals.get("maxRadius")))
            throw new IllegalArgumentException("Search initialRadius exceeds maxRadius");
        expected.remove("sprint"); // Optional, defaults to false.
        if (!supplied.containsAll(expected)) throw new IllegalArgumentException("Missing required parameters for " + name + ": " + expected);
    }

    static String resolveValue(String value, Map<String, Object> state) {
        if (value == null) throw new MissingDependencyException("Missing required parameter");
        if (!value.startsWith("$")) return value;
        Object resolved = state.get(value.substring(1));
        if (resolved == null) throw new MissingDependencyException("Unresolved placeholder " + value + "; search must succeed first");
        return resolved.toString();
    }

    static Map<String, String> resolve(JsonObject step, Map<String, Object> state) {
        Map<String, String> params = new HashMap<>();
        for (JsonElement element : step.getAsJsonArray("parameters")) {
            JsonObject param = element.getAsJsonObject();
            String key = param.get("parameterName").getAsString();
            String value = resolveValue(param.get("parameterValue").getAsString(), state);
            validateValue(key, value);
            params.put(key, value);
        }
        if (step.get("functionName").getAsString().equals("searchBlocks")
                && Integer.parseInt(params.get("initialRadius")) > Integer.parseInt(params.get("maxRadius")))
            throw new IllegalArgumentException("Search initialRadius exceeds maxRadius");
        return params;
    }

    private static void validateValue(String key, String value) {
        if (Set.of("x", "y", "z", "targetX", "targetY", "targetZ", "initialRadius", "maxRadius", "radiusIncrement").contains(key)) {
            int number = Integer.parseInt(value);
            if (key.toLowerCase().contains("radius") && (number <= 0 || number > 128))
                throw new IllegalArgumentException("Search radii must be between 1 and 128");
        }
        if (key.equals("sprint") && !value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
            throw new IllegalArgumentException("sprint must be true or false");
    }
}
