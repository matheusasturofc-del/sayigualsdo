package com.matheusasturo.sayigualsdo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * Os efeitos no mundo: trocar os blocos das chunks mais próximas e fazer mobs nascerem.
 */
public final class WorldEffects {

    /** Atualiza os clientes, mas não avisa os vizinhos (muito mais rápido, igual ao /fill). */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private final Deque<ReplaceJob> jobs = new ArrayDeque<>();

    /** Troca todos os blocos das {@code chunkCount} chunks mais perto do jogador. */
    public void replaceBlocks(ServerPlayer player, Block block, SayIgualConfig config) {
        List<ChunkPos> chunks = nearestChunks(player, Math.max(1, config.chunkCount));
        jobs.add(new ReplaceJob(player.serverLevel(), block.defaultBlockState(), chunks, config.replaceAir));
    }

    /** Faz {@code mobCount} mobs nascerem em volta do jogador. */
    public int spawnMobs(ServerPlayer player, EntityType<?> type, SayIgualConfig config) {
        ServerLevel level = player.serverLevel();
        RandomSource random = level.getRandom();
        int radius = Math.max(2, config.mobSpawnRadius);
        int spawned = 0;
        for (int i = 0; i < config.mobCount; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = 2 + random.nextDouble() * (radius - 2);
            int x = Mth.floor(player.getX() + Math.cos(angle) * distance);
            int z = Mth.floor(player.getZ() + Math.sin(angle) * distance);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            // Em cavernas o "topo" fica longe: nasce na altura do jogador.
            if (Math.abs(y - player.getBlockY()) > 6) {
                y = player.getBlockY();
            }
            if (type.spawn(level, new BlockPos(x, y, z), MobSpawnType.COMMAND) != null) {
                spawned++;
            }
        }
        return spawned;
    }

    /** Chamado no fim de cada tick do servidor. */
    public void tick(int blocksPerTick) {
        int budget = Math.max(1, blocksPerTick);
        while (budget > 0 && !jobs.isEmpty()) {
            ReplaceJob job = jobs.peek();
            budget = job.run(budget);
            if (job.isDone()) {
                jobs.poll();
            }
        }
    }

    public int pendingJobs() {
        return jobs.size();
    }

    public void clear() {
        jobs.clear();
    }

    static List<ChunkPos> nearestChunks(ServerPlayer player, int count) {
        ChunkPos center = player.chunkPosition();
        int r = (int) Math.ceil(Math.sqrt(count)) + 1;
        double px = player.getX();
        double pz = player.getZ();
        List<ChunkPos> all = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                all.add(new ChunkPos(center.x + dx, center.z + dz));
            }
        }
        all.sort(Comparator.comparingDouble(c -> {
            double cx = c.getMinBlockX() + 8 - px;
            double cz = c.getMinBlockZ() + 8 - pz;
            return cx * cx + cz * cz;
        }));
        return new ArrayList<>(all.subList(0, Math.min(count, all.size())));
    }

    /** Troca os blocos aos poucos, alguns milhares por tick, para não travar o servidor. */
    private static final class ReplaceJob {
        private final ServerLevel level;
        private final BlockState target;
        private final List<ChunkPos> chunks;
        private final boolean replaceAir;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        private int chunkIndex;
        private int sectionIndex;
        private int localIndex;

        ReplaceJob(ServerLevel level, BlockState target, List<ChunkPos> chunks, boolean replaceAir) {
            this.level = level;
            this.target = target;
            this.chunks = chunks;
            this.replaceAir = replaceAir;
        }

        boolean isDone() {
            return chunkIndex >= chunks.size();
        }

        /** Retorna quanto sobrou do orçamento. */
        int run(int budget) {
            while (chunkIndex < chunks.size()) {
                ChunkPos chunkPos = chunks.get(chunkIndex);
                LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
                LevelChunkSection[] sections = chunk.getSections();
                while (sectionIndex < sections.length) {
                    LevelChunkSection section = sections[sectionIndex];
                    if (localIndex == 0 && !replaceAir && section.hasOnlyAir()) {
                        sectionIndex++;
                        continue;
                    }
                    int baseY = SectionPos.sectionToBlockCoord(level.getSectionYFromSectionIndex(sectionIndex));
                    while (localIndex < 4096) {
                        int x = localIndex & 15;
                        int z = (localIndex >> 4) & 15;
                        int y = localIndex >> 8;
                        localIndex++;
                        BlockState current = section.getBlockState(x, y, z);
                        if (current == target || (!replaceAir && current.isAir())) {
                            continue;
                        }
                        pos.set(chunkPos.getMinBlockX() + x, baseY + y, chunkPos.getMinBlockZ() + z);
                        if (current.hasBlockEntity()) {
                            // Igual ao /fill: esvazia baús etc. para não espalhar itens pelo chão.
                            Clearable.tryClear(level.getBlockEntity(pos));
                        }
                        level.setBlock(pos, target, FLAGS);
                        if (--budget <= 0) {
                            return 0;
                        }
                    }
                    localIndex = 0;
                    sectionIndex++;
                }
                sectionIndex = 0;
                chunkIndex++;
            }
            return budget;
        }
    }
}
