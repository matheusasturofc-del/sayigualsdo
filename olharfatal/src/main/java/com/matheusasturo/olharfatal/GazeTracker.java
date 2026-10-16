package com.matheusasturo.olharfatal;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A cada tick: quem do time Kill está sendo olhado por alguém do time View fica congelado,
 * contornado de vermelho e soltando partículas vermelhas. Quando ninguém olha mais, ele volta a andar.
 */
public final class GazeTracker {

    private static final ResourceLocation FREEZE_ID = ResourceLocation.fromNamespaceAndPath(OlharFatalMod.MOD_ID, "congelado");
    private static final DustParticleOptions RED_DUST = new DustParticleOptions(new Vector3f(1.0F, 0.0F, 0.0F), 1.3F);
    /** Partes do corpo testadas (frações da caixa do jogador): rosto primeiro, depois o resto do corpo. */
    private static final double[] BODY_FRACTIONS = {0.5, 0.15, 0.85};

    private final Map<UUID, Frozen> frozen = new HashMap<>();

    /** Onde o jogador foi congelado. Guarda a entidade para perceber quando ele morre e renasce. */
    private record Frozen(ServerPlayer player, ResourceKey<Level> dimension, Vec3 pos) {
    }

    public boolean isFrozen(ServerPlayer player) {
        return frozen.containsKey(player.getUUID());
    }

    public void tick(MinecraftServer server, OlharFatalConfig config) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        Set<UUID> watched = new HashSet<>();
        if (config.enabled) {
            for (ServerPlayer killer : players) {
                if (Teams.role(killer) == Teams.Role.KILL && killer.isAlive() && !killer.isSpectator()
                        && isWatched(killer, players, config)) {
                    watched.add(killer.getUUID());
                    hold(killer, config);
                }
            }
        }
        Iterator<Map.Entry<UUID, Frozen>> it = frozen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Frozen> entry = it.next();
            if (!watched.contains(entry.getKey())) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null) {
                    release(player);
                }
                it.remove();
            }
        }
    }

    public void forget(UUID playerId) {
        frozen.remove(playerId);
    }

    /** Solta todo mundo (usado quando o mod é desligado). */
    public void releaseAll(MinecraftServer server) {
        for (UUID id : frozen.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                release(player);
            }
        }
        frozen.clear();
    }

    // ------------------------------------------------------------------ olhar

    private static boolean isWatched(ServerPlayer killer, List<ServerPlayer> players, OlharFatalConfig config) {
        for (ServerPlayer viewer : players) {
            if (viewer == killer || Teams.role(viewer) != Teams.Role.VIEW || !viewer.isAlive() || viewer.isSpectator()
                    || viewer.level() != killer.level()) {
                continue;
            }
            if (config.blindnessStopsGaze && viewer.hasEffect(MobEffects.BLINDNESS)) {
                continue;
            }
            if (sees(viewer, killer, config)) {
                return true;
            }
        }
        return false;
    }

    /** O jogador {@code viewer} está olhando para alguma parte do corpo (rosto incluído) de {@code target}? */
    static boolean sees(ServerPlayer viewer, ServerPlayer target, OlharFatalConfig config) {
        Vec3 eye = viewer.getEyePosition();
        Vec3 look = viewer.getViewVector(1.0F).normalize();
        double maxDistance = Math.max(1, config.maxDistance);
        AABB box = target.getBoundingBox();
        if (box.getCenter().distanceToSqr(eye) > (maxDistance + 2) * (maxDistance + 2)) {
            return false;
        }

        // 1) A mira está em cima do corpo dele.
        Optional<Vec3> aimed = box.clip(eye, eye.add(look.scale(maxDistance)));
        if (aimed.isPresent() && hasLineOfSight(viewer, eye, aimed.get(), config)) {
            return true;
        }

        // 2) O rosto ou qualquer parte do corpo está perto do meio da tela.
        double minCos = Math.cos(Math.toRadians(Math.max(1, Math.min(89, config.viewAngle))));
        if (inCone(viewer, eye, look, target.getEyePosition(), maxDistance, minCos, config)) {
            return true;
        }
        for (double fy : BODY_FRACTIONS) {
            for (double fx : BODY_FRACTIONS) {
                for (double fz : BODY_FRACTIONS) {
                    Vec3 point = new Vec3(
                            box.minX + (box.maxX - box.minX) * fx,
                            box.minY + (box.maxY - box.minY) * fy,
                            box.minZ + (box.maxZ - box.minZ) * fz);
                    if (inCone(viewer, eye, look, point, maxDistance, minCos, config)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean inCone(ServerPlayer viewer, Vec3 eye, Vec3 look, Vec3 point, double maxDistance,
                                  double minCos, OlharFatalConfig config) {
        Vec3 toPoint = point.subtract(eye);
        double length = toPoint.length();
        if (length < 1.0E-4) {
            return true;
        }
        if (length > maxDistance || toPoint.dot(look) / length < minCos) {
            return false;
        }
        return hasLineOfSight(viewer, eye, point, config);
    }

    /** Nenhum bloco sólido no caminho. Vidro, folhas, grades etc. deixam ver (se configurado). */
    private static boolean hasLineOfSight(ServerPlayer viewer, Vec3 from, Vec3 to, OlharFatalConfig config) {
        Level level = viewer.level();
        Vec3 start = from;
        double total = from.distanceToSqr(to);
        Vec3 dir = to.subtract(from).normalize();
        for (int i = 0; i < 16; i++) {
            BlockHitResult hit = level.clip(new ClipContext(start, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));
            if (hit.getType() == HitResult.Type.MISS) {
                return true;
            }
            BlockPos blockPos = hit.getBlockPos();
            BlockState state = level.getBlockState(blockPos);
            if (!config.seeThroughGlass || state.canOcclude()) {
                return false;
            }
            // Atravessa o bloco transparente e continua do outro lado.
            Vec3 next = hit.getLocation().add(dir.scale(0.001));
            for (int step = 0; step < 40 && BlockPos.containing(next).equals(blockPos); step++) {
                next = next.add(dir.scale(0.05));
            }
            if (from.distanceToSqr(next) >= total) {
                return true;
            }
            start = next;
        }
        return false;
    }

    // ------------------------------------------------------------------ congelar

    private void hold(ServerPlayer killer, OlharFatalConfig config) {
        Frozen state = frozen.get(killer.getUUID());
        if (state == null || state.player() != killer || state.dimension() != killer.level().dimension()) {
            state = new Frozen(killer, killer.level().dimension(), killer.position());
            frozen.put(killer.getUUID(), state);
            killer.stopUsingItem();
            if (killer.isFallFlying()) {
                killer.stopFallFlying();
            }
        }

        // Velocidade, pulo e gravidade zerados: o cliente nem tenta andar.
        addModifier(killer, Attributes.MOVEMENT_SPEED);
        addModifier(killer, Attributes.JUMP_STRENGTH);
        addModifier(killer, Attributes.GRAVITY);

        // Se mesmo assim se mexeu (empurrão, água, pistão...), volta para o lugar.
        Vec3 pos = state.pos();
        if (killer.position().distanceToSqr(pos) > 0.01) {
            killer.connection.teleport(pos.x, pos.y, pos.z, killer.getYRot(), killer.getXRot());
        }
        killer.setDeltaMovement(Vec3.ZERO);
        killer.fallDistance = 0;

        if (config.redOutline) {
            MobEffectInstance glowing = killer.getEffect(MobEffects.GLOWING);
            if (glowing == null || (isOurGlow(glowing) && glowing.getDuration() < 10)) {
                killer.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0, false, false, false));
            }
        }
        if (config.redParticles) {
            spawnParticles(killer, Math.max(1, config.particlesPerTick));
        }
        if (killer.tickCount % 10 == 0) {
            killer.displayClientMessage(Component.literal("👁 Estão olhando para você! Você está congelado.")
                    .withStyle(ChatFormatting.RED), true);
        }
    }

    private static void release(ServerPlayer player) {
        removeModifier(player, Attributes.MOVEMENT_SPEED);
        removeModifier(player, Attributes.JUMP_STRENGTH);
        removeModifier(player, Attributes.GRAVITY);
        MobEffectInstance glowing = player.getEffect(MobEffects.GLOWING);
        if (glowing != null && isOurGlow(glowing)) {
            player.removeEffect(MobEffects.GLOWING);
        }
        if (player.isAlive() && Teams.role(player) == Teams.Role.KILL) {
            player.displayClientMessage(Component.literal("Ninguém está olhando... corra!")
                    .withStyle(ChatFormatting.DARK_RED), true);
        }
    }

    /** O brilho que o mod coloca é invisível no inventário; o de flecha espectral não é mexido. */
    private static boolean isOurGlow(MobEffectInstance effect) {
        return !effect.isVisible() && !effect.showIcon();
    }

    private static void addModifier(ServerPlayer player, Holder<Attribute> attribute) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null && !instance.hasModifier(FREEZE_ID)) {
            instance.addTransientModifier(new AttributeModifier(FREEZE_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    private static void removeModifier(ServerPlayer player, Holder<Attribute> attribute) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(FREEZE_ID);
        }
    }

    private static void spawnParticles(ServerPlayer killer, int count) {
        ServerLevel level = killer.serverLevel();
        AABB box = killer.getBoundingBox();
        double width = box.maxX - box.minX;
        double height = box.maxY - box.minY;
        Vec3 center = box.getCenter();
        for (ServerPlayer watcher : level.players()) {
            // "longDistance" = quem está longe também vê as partículas.
            level.sendParticles(watcher, RED_DUST, true, center.x, center.y, center.z, count,
                    width * 0.4, height * 0.3, width * 0.4, 0.0);
        }
    }
}
