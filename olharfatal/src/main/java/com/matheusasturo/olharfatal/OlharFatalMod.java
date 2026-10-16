package com.matheusasturo.olharfatal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;

public class OlharFatalMod implements ModInitializer {

    public static final String MOD_ID = "olharfatal";
    public static final Logger LOGGER = LoggerFactory.getLogger("OlharFatal");

    private static final DustParticleOptions BLOOD = new DustParticleOptions(new Vector3f(0.7F, 0.0F, 0.0F), 2.0F);

    private static OlharFatalConfig config;
    private static final GazeTracker TRACKER = new GazeTracker();

    @Override
    public void onInitialize() {
        config = OlharFatalConfig.load();

        ServerLifecycleEvents.SERVER_STARTED.register(Teams::ensure);
        ServerLifecycleEvents.SERVER_STOPPING.register(TRACKER::releaseAll);
        ServerTickEvents.END_SERVER_TICK.register(server -> TRACKER.tick(server, config));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> TRACKER.forget(handler.getPlayer().getUUID()));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer attacker)) {
                return InteractionResult.PASS;
            }
            if (isBlocked(attacker)) {
                return InteractionResult.FAIL;
            }
            if (config.enabled && config.oneHitKill && entity instanceof ServerPlayer victim
                    && Teams.role(attacker) == Teams.Role.KILL && Teams.role(victim) == Teams.Role.VIEW
                    && victim.isAlive() && !victim.isCreative() && !victim.isSpectator()) {
                oneHitKill(attacker, victim);
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) ->
                isBlocked(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) ->
                isBlocked(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
                isBlocked(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseItemCallback.EVENT.register((player, world, hand) ->
                isBlocked(player) ? InteractionResultHolder.fail(player.getItemInHand(hand)) : InteractionResultHolder.pass(player.getItemInHand(hand)));
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> !isBlocked(player));
    }

    public static OlharFatalConfig config() {
        return config;
    }

    /** Jogador do time Kill congelado não pode fazer nada. */
    private static boolean isBlocked(Player player) {
        return config.enabled && config.blockActionsWhileFrozen
                && player instanceof ServerPlayer serverPlayer && TRACKER.isFrozen(serverPlayer);
    }

    /** O time Kill mata o time View com um único hit (passa por armadura, escudo e totem). */
    private static void oneHitKill(ServerPlayer attacker, ServerPlayer victim) {
        ServerLevel level = victim.serverLevel();
        double x = victim.getX();
        double y = victim.getY() + victim.getBbHeight() / 2;
        double z = victim.getZ();
        attacker.resetAttackStrengthTicker();
        victim.hurt(attacker.damageSources().playerAttack(attacker), Float.MAX_VALUE);
        if (victim.isAlive()) {
            // Escudo, totem ou resistência seguraram o golpe: mata igual ao /kill.
            victim.hurt(victim.damageSources().genericKill(), Float.MAX_VALUE);
        }
        level.sendParticles(BLOOD, x, y, z, 40, 0.3, 0.5, 0.3, 0.0);
        level.playSound(null, x, y, z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0F, 0.6F);
    }

    // ------------------------------------------------------------------ comandos

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("equipe")
                .then(teamCommand("view", Teams.Role.VIEW))
                .then(teamCommand("kill", Teams.Role.KILL))
                .then(teamCommand("sair", Teams.Role.NONE))
                .then(Commands.literal("lista").executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("angulo")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("graus", IntegerArgumentType.integer(1, 89))
                                .executes(ctx -> {
                                    config.viewAngle = IntegerArgumentType.getInteger(ctx, "graus");
                                    config.save();
                                    ctx.getSource().sendSuccess(() -> Component.literal("Ângulo do olhar: " + config.viewAngle + "°"), true);
                                    return 1;
                                })))
                .then(Commands.literal("distancia")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("blocos", IntegerArgumentType.integer(2, 512))
                                .executes(ctx -> {
                                    config.maxDistance = IntegerArgumentType.getInteger(ctx, "blocos");
                                    config.save();
                                    ctx.getSource().sendSuccess(() -> Component.literal("Distância do olhar: " + config.maxDistance + " blocos"), true);
                                    return 1;
                                })))
                .then(Commands.literal("ligar")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> setEnabled(ctx.getSource(), true)))
                .then(Commands.literal("desligar")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> setEnabled(ctx.getSource(), false)))
                .then(Commands.literal("recarregar")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> {
                            config = OlharFatalConfig.load();
                            ctx.getSource().sendSuccess(() -> Component.literal("Olhar Fatal: configuração recarregada"), true);
                            return 1;
                        })));
    }

    /**
     * {@code /equipe <time>} coloca você mesmo no time (qualquer um pode).
     * {@code /equipe <time> <jogadores>} coloca outros jogadores (precisa ser OP).
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> teamCommand(String literal, Teams.Role role) {
        return Commands.literal(literal)
                .executes(ctx -> join(ctx.getSource(), List.of(ctx.getSource().getPlayerOrException()), role))
                .then(Commands.argument("jogadores", EntityArgument.players())
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> join(ctx.getSource(), players(ctx), role)));
    }

    private static Collection<ServerPlayer> players(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return EntityArgument.getPlayers(ctx, "jogadores");
    }

    private static int join(CommandSourceStack source, Collection<ServerPlayer> players, Teams.Role role) {
        MinecraftServer server = source.getServer();
        for (ServerPlayer player : players) {
            Teams.set(server, player, role);
            MutableComponent message = Component.literal("").append(player.getDisplayName());
            if (role == Teams.Role.NONE) {
                message.append(Component.literal(" saiu dos times").withStyle(ChatFormatting.GRAY));
            } else {
                message.append(Component.literal(" entrou no time ").withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal(role.teamName).withStyle(role.color, ChatFormatting.BOLD));
            }
            server.getPlayerList().broadcastSystemMessage(message, false);
            if (role == Teams.Role.KILL) {
                player.sendSystemMessage(Component.literal("Você mata o time View com um hit, mas congela quando alguém do View olha para você.")
                        .withStyle(ChatFormatting.RED));
            } else if (role == Teams.Role.VIEW) {
                player.sendSystemMessage(Component.literal("Não pare de olhar para o time Kill! Se ele te bater, você morre com um hit.")
                        .withStyle(ChatFormatting.AQUA));
            }
        }
        return players.size();
    }

    private static int list(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        for (Teams.Role role : new Teams.Role[]{Teams.Role.VIEW, Teams.Role.KILL}) {
            PlayerTeam team = Teams.team(server, role);
            String members = team.getPlayers().isEmpty() ? "(ninguém)" : String.join(", ", team.getPlayers());
            source.sendSuccess(() -> Component.literal("Time " + role.teamName + ": ").withStyle(role.color, ChatFormatting.BOLD)
                    .append(Component.literal(members).withStyle(ChatFormatting.WHITE)), false);
        }
        return 1;
    }

    private static int setEnabled(CommandSourceStack source, boolean enabled) {
        config.enabled = enabled;
        config.save();
        if (!enabled) {
            TRACKER.releaseAll(source.getServer());
        }
        source.sendSuccess(() -> Component.literal("Olhar Fatal " + (enabled ? "ligado" : "desligado")), true);
        return 1;
    }
}
