package net.shasankp000.Tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Efficient block searching tool with incremental radius expansion.
 * Searches for blocks in expanding spherical shells to avoid lag.
 */
public class SearchBlocks {
    private static final Logger LOGGER = LoggerFactory.getLogger("search-blocks");

    private static final int MAX_BLOCKS_PER_ITERATION = 5000;

    /**
     * Search for a specific block type in an expanding radius.
     *
     * @param bot The bot player
     * @param blockType Block identifier (e.g., "minecraft:oak_log")
     * @param initialRadius Starting search radius
     * @param maxRadius Maximum search radius
     * @param radiusIncrement How much to expand radius each iteration
     * @return BlockPos of nearest matching block, or null if not found
     */
    public static BlockPos searchBlock(
            ServerPlayer bot,
            String blockType,
            int initialRadius,
            int maxRadius,
            int radiusIncrement
    ) {
        if (bot == null || blockType == null || blockType.isEmpty()) {
            LOGGER.error("Invalid search parameters");
            return null;
        }

        if (initialRadius <= 0 || maxRadius < initialRadius || maxRadius > 128 || radiusIncrement <= 0)
            throw new IllegalArgumentException("Invalid bounded search radii");

        ServerLevel world = bot.level();
        BlockPos botPos = bot.blockPosition();
        UUID botId = bot.getUUID();

        // Initialize search cache for this bot if needed
        Set<BlockPos> searched = new HashSet<>();

        // Normalize block type
        String normalizedBlockType = normalizeBlockType(blockType);
        Block targetBlock = getBlockFromIdentifier(normalizedBlockType);

        if (targetBlock == null) {
            LOGGER.error("Unknown block type: {}", normalizedBlockType);
            return null;
        }

        LOGGER.info("Searching for {} within radius {}-{} blocks from {}",
            normalizedBlockType, initialRadius, maxRadius, botPos);

        // Search in expanding shells
        int currentRadius = initialRadius;

        int previousRadius = -1;
        while (true) {
            int finalRadius = currentRadius;
            int prevRadius = previousRadius;

            LOGGER.debug("Searching shell: inner={}, outer={}", prevRadius, finalRadius);

            // Use parallel search for this shell
            BlockPos result = searchShell(
                world, botPos, targetBlock, prevRadius, finalRadius, searched
            );

            if (result != null) {
                LOGGER.info("✓ Found {} at {} (distance: {} blocks)",
                    normalizedBlockType, result, botPos.distManhattan(result));
                return result;
            }

            if (currentRadius == maxRadius) break;
            previousRadius = currentRadius;
            currentRadius = Math.min(maxRadius, currentRadius + radiusIncrement);
        }

        LOGGER.warn("No {} found within {} blocks", normalizedBlockType, maxRadius);
        return null;
    }

    /**
     * Search a spherical shell (between inner and outer radius).
     * Uses parallel processing and respects max blocks per iteration.
     */
    private static BlockPos searchShell(
            ServerLevel world,
            BlockPos center,
            Block targetBlock,
            int innerRadius,
            int outerRadius,
            Set<BlockPos> alreadySearched
    ) {
        // Generate candidate positions in this shell
        List<BlockPos> candidates = generateShellPositions(center, innerRadius, outerRadius);

        // Filter out already searched positions
        candidates.removeIf(alreadySearched::contains);

        // Read live blocks only on the server thread, in bounded batches. No detached search workers.
        for (int offset = 0; offset < candidates.size(); offset += MAX_BLOCKS_PER_ITERATION) {
            List<BlockPos> batch = List.copyOf(candidates.subList(offset,
                    Math.min(candidates.size(), offset + MAX_BLOCKS_PER_ITERATION)));
            CompletableFuture<BlockPos> result = new CompletableFuture<>();
            Runnable scan = () -> {
                if (result.isDone()) return;
                try {
                    BlockPos found = null;
                    for (BlockPos pos : batch) {
                        alreadySearched.add(pos);
                        if (world.isLoaded(pos) && world.getBlockState(pos).getBlock() == targetBlock) {
                            found = pos;
                            break;
                        }
                    }
                    result.complete(found);
                } catch (Throwable error) { result.completeExceptionally(error); }
            };
            if (world.getServer().isSameThread()) scan.run(); else world.getServer().execute(scan);
            try {
                BlockPos found = result.get(5, TimeUnit.SECONDS);
                if (found != null) return found;
            } catch (InterruptedException error) {
                result.cancel(false);
                Thread.currentThread().interrupt();
                throw new CancellationException("Search interrupted");
            } catch (ExecutionException | TimeoutException error) {
                result.cancel(false);
                throw new CompletionException(error);
            }
        }

        return null;
    }

    /**
     * Generate positions in a spherical shell (between inner and outer radius).
     * Uses efficient iteration to minimize overhead.
     */
    private static List<BlockPos> generateShellPositions(BlockPos center, int innerRadius, int outerRadius) {
        List<BlockPos> positions = new ArrayList<>();

        int innerRadiusSq = innerRadius < 0 ? -1 : innerRadius * innerRadius;
        int outerRadiusSq = outerRadius * outerRadius;

        // Iterate cube, filter to shell
        for (int x = -outerRadius; x <= outerRadius; x++) {
            for (int y = -outerRadius; y <= outerRadius; y++) {
                for (int z = -outerRadius; z <= outerRadius; z++) {
                    int distSq = x*x + y*y + z*z;

                    if (distSq > innerRadiusSq && distSq <= outerRadiusSq) {
                        positions.add(center.offset(x, y, z));
                    }
                }
            }
        }

        // Sort by distance for more efficient searching (closer first)
        positions.sort(Comparator.comparingInt(pos -> pos.distManhattan(center)));

        return positions;
    }

    /**
     * Normalize block type string to proper identifier format.
     */
    private static String normalizeBlockType(String input) {
        input = input.toLowerCase().trim();

        if (!input.contains(":")) {
            input = "minecraft:" + input;
        }

        return input;
    }

    /**
     * Get Block instance from identifier string.
     */
    private static Block getBlockFromIdentifier(String blockId) {
        try {
            Identifier id = Identifier.parse(blockId);
            return BuiltInRegistries.BLOCK.getValue(id);
        } catch (Exception e) {
            LOGGER.error("Failed to parse block identifier: {}", blockId, e);
            return null;
        }
    }

    /**
     * Clear search cache for a specific bot.
     */
    public static void clearCache(UUID botId) { /* Searches are request-local. */ }

    /**
     * Clear all search caches.
     */
    public static void clearAllCaches() { /* Searches are request-local. */ }

    public static void shutdown() { /* No background search workers. */ }
}
