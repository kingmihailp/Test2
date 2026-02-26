package com.example.randomblockmod.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;

/**
 * Persists the set of chunk keys that have already been replaced.
 * Stored per-dimension in world/data/randomblockmod_replaced_chunks.dat,
 * so the data survives server restarts.
 */
public class ReplacedChunksSavedData extends SavedData {

    private static final String DATA_NAME = "randomblockmod_replaced_chunks";
    private static final String TAG_CHUNKS  = "replaced_chunks";

    private final Set<Long> replacedChunks = new HashSet<>();

    // -----------------------------------------------------------------------
    // Factory / serialization
    // -----------------------------------------------------------------------

    public static ReplacedChunksSavedData load(CompoundTag tag) {
        ReplacedChunksSavedData data = new ReplacedChunksSavedData();
        for (long key : tag.getLongArray(TAG_CHUNKS)) {
            data.replacedChunks.add(key);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        long[] arr = new long[replacedChunks.size()];
        int i = 0;
        for (long key : replacedChunks) {
            arr[i++] = key;
        }
        tag.put(TAG_CHUNKS, new LongArrayTag(arr));
        return tag;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Returns true if this chunk has already been replaced in a previous call. */
    public boolean isReplaced(long chunkKey) {
        return replacedChunks.contains(chunkKey);
    }

    /** Marks a chunk as replaced and schedules a save. */
    public void markReplaced(long chunkKey) {
        replacedChunks.add(chunkKey);
        setDirty();
    }

    /**
     * Retrieves (or creates) the saved-data instance for the given server level.
     * Data is stored per-dimension, so Overworld / Nether / End are tracked separately.
     */
    /**
     * In Forge 47.x the signature is:
     *   computeIfAbsent(Function<CompoundTag, T> deserializer, Supplier<T> constructor, String name)
     */
    public static ReplacedChunksSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                ReplacedChunksSavedData::load,
                ReplacedChunksSavedData::new,
                DATA_NAME
        );
    }
}
