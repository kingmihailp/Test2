package com.example.randomblockmod.events;

import com.example.randomblockmod.RandomBlockMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * Handles chunk replacement when a player moves into a new chunk.
 * Uses direct LevelChunkSection manipulation for performance,
 * then sends a full chunk packet to clients.
 */
@Mod.EventBusSubscriber(modid = RandomBlockMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ChunkReplaceHandler {

    private static final Logger LOGGER = LogManager.getLogger();

    /** Tracks each player's last known chunk (packed long key). */
    private static final Map<UUID, Long> playerLastChunk = new HashMap<>();

    /** Lazily-built list of all usable blocks. */
    private static volatile List<Block> blockList = null;

    // -----------------------------------------------------------------------
    // Block pool
    // -----------------------------------------------------------------------

    private static List<Block> getBlockList() {
        if (blockList == null) {
            List<Block> list = new ArrayList<>();
            for (Block block : ForgeRegistries.BLOCKS) {
                // Exclude air variants and moving piston (causes TileEntity issues)
                if (block != Blocks.AIR
                        && block != Blocks.CAVE_AIR
                        && block != Blocks.VOID_AIR
                        && block != Blocks.MOVING_PISTON) {
                    list.add(block);
                }
            }
            blockList = Collections.unmodifiableList(list);
            LOGGER.info("[RandomBlockMod] Block pool ready — {} blocks available", blockList.size());
        }
        return blockList;
    }

    // -----------------------------------------------------------------------
    // Events
    // -----------------------------------------------------------------------

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        int chunkX = player.chunkPosition().x;
        int chunkZ = player.chunkPosition().z;
        long currentKey = packChunkKey(chunkX, chunkZ);

        UUID playerId = player.getUUID();
        Long lastKey = playerLastChunk.get(playerId);

        if (lastKey == null || lastKey != currentKey) {
            playerLastChunk.put(playerId, currentKey);
            // Skip replacement on first registration (spawn); only replace on actual chunk transitions.
            if (lastKey != null) {
                replaceChunk(player.serverLevel(), chunkX, chunkZ);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        playerLastChunk.remove(event.getEntity().getUUID());
    }

    // -----------------------------------------------------------------------
    // Chunk replacement logic
    // -----------------------------------------------------------------------

    private static void replaceChunk(ServerLevel level, int chunkX, int chunkZ) {
        List<Block> blocks = getBlockList();
        if (blocks.isEmpty()) return;

        // Pick a random block
        Block chosen = blocks.get(level.random.nextInt(blocks.size()));
        BlockState state = chosen.defaultBlockState();

        LOGGER.info("[RandomBlockMod] Replacing chunk ({}, {}) with '{}'",
                chunkX, chunkZ, ForgeRegistries.BLOCKS.getKey(chosen));

        LevelChunk chunk = level.getChunk(chunkX, chunkZ);

        // Remove any existing block entities to avoid stale TileEntity data
        new ArrayList<>(chunk.getBlockEntities().keySet())
                .forEach(chunk::removeBlockEntity);

        // Fill every chunk section (16×16×16 sub-volume) with the chosen block.
        // Direct section access is orders of magnitude faster than level.setBlock()
        // for bulk operations since it bypasses per-block lighting/neighbor updates.
        LevelChunkSection[] sections = chunk.getSections();
        for (LevelChunkSection section : sections) {
            for (int lx = 0; lx < 16; lx++) {
                for (int ly = 0; ly < 16; ly++) {
                    for (int lz = 0; lz < 16; lz++) {
                        section.setBlockState(lx, ly, lz, state, false);
                    }
                }
            }
            // Recalculate non-empty / ticking block counts for this section
            section.recalcBlockCounts();
        }

        chunk.setUnsaved(true);

        // Trigger lighting recalculation at the four corners of each section row.
        // The light engine will propagate updates from these sample points.
        int baseX = chunkX * 16;
        int baseZ = chunkZ * 16;
        BlockPos.MutableBlockPos lp = new BlockPos.MutableBlockPos();
        for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y += 16) {
            level.getLightEngine().checkBlock(lp.set(baseX,      y, baseZ));
            level.getLightEngine().checkBlock(lp.set(baseX + 15, y, baseZ));
            level.getLightEngine().checkBlock(lp.set(baseX,      y, baseZ + 15));
            level.getLightEngine().checkBlock(lp.set(baseX + 15, y, baseZ + 15));
        }

        // Send the full chunk packet to every player currently tracking this chunk.
        // One packet per chunk replaces thousands of individual block-change packets.
        int viewDistance = level.getServer().getPlayerList().getViewDistance();
        ClientboundLevelChunkWithLightPacket packet =
                new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);

        for (ServerPlayer sp : level.getServer().getPlayerList().getPlayers()) {
            if (sp.level() == level
                    && Math.abs(sp.chunkPosition().x - chunkX) <= viewDistance
                    && Math.abs(sp.chunkPosition().z - chunkZ) <= viewDistance) {
                sp.connection.send(packet);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Packs two chunk coordinates into a single long for efficient map keying. */
    private static long packChunkKey(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }
}
