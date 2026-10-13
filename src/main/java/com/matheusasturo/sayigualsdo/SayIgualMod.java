package com.matheusasturo.sayigualsdo;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SayIgualMod implements ModInitializer {

    public static final String MOD_ID = "sayigualsdo";
    public static final Logger LOGGER = LoggerFactory.getLogger("SayIgual");

    private static SayIgualConfig config;
    private static SpeechService speech;
    private static NameDictionary dictionary;
    private static MinecraftServer server;
    private static final WorldEffects EFFECTS = new WorldEffects();
    /** Último efeito de cada jogador (só usado na thread do servidor). */
    private static final Map<UUID, Long> LAST_TRIGGER = new HashMap<>();

    @Override
    public void onInitialize() {
        speech();

        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            reload();
            speech().loadModelsAsync();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            EFFECTS.clear();
            LAST_TRIGGER.clear();
            speech().shutdown();
            server = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> EFFECTS.tick(config().blocksPerTick));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> speech().onPlayerLeft(handler.getPlayer().getUUID()));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
    }

    /** Pode ser chamado pelo Simple Voice Chat antes do onInitialize, por isso é preguiçoso. */
    public static synchronized SpeechService speech() {
        if (speech == null) {
            speech = new SpeechService(config(), SayIgualMod::onSpeech);
        }
        return speech;
    }

    public static synchronized SayIgualConfig config() {
        if (config == null) {
            config = SayIgualConfig.load();
        }
        return config;
    }

    private static synchronized void reload() {
        config = SayIgualConfig.load();
        dictionary = NameDictionary.build(config);
        speech().update(config, dictionary);
    }

    /** Texto reconhecido (vem da thread de áudio). */
    private static void onSpeech(UUID playerId, String text) {
        MinecraftServer s = server;
        if (s == null) {
            return;
        }
        s.execute(() -> {
            ServerPlayer player = s.getPlayerList().getPlayer(playerId);
            if (player != null) {
                handleSpeech(player, text, false);
            }
        });
    }

    /** Procura um bloco/mob no texto e aplica o efeito. Retorna true se algo aconteceu. */
    private static boolean handleSpeech(ServerPlayer player, String text, boolean ignoreCooldown) {
        SayIgualConfig cfg = config();
        if (!cfg.enabled || dictionary == null) {
            return false;
        }
        if (cfg.showTranscripts) {
            player.displayClientMessage(Component.literal("🎤 " + text).withStyle(ChatFormatting.GRAY), true);
        }
        var match = dictionary.find(text);
        if (match.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_TRIGGER.get(player.getUUID());
        if (!ignoreCooldown && last != null && now - last < cfg.cooldownSeconds * 1000) {
            return false;
        }
        LAST_TRIGGER.put(player.getUUID(), now);

        NameDictionary.Target target = match.get().target();
        Component name = Component.translatable(target.translationKey()).withStyle(ChatFormatting.AQUA);
        Component message;
        if (target.kind() == NameDictionary.Kind.BLOCK) {
            EFFECTS.replaceBlocks(player, BuiltInRegistries.BLOCK.get(target.id()), cfg);
            message = Component.literal("").append(player.getDisplayName())
                    .append(Component.literal(" falou ").withStyle(ChatFormatting.YELLOW))
                    .append(name)
                    .append(Component.literal("! " + cfg.chunkCount + " chunks viraram isso.").withStyle(ChatFormatting.YELLOW));
        } else {
            int spawned = EFFECTS.spawnMobs(player, BuiltInRegistries.ENTITY_TYPE.get(target.id()), cfg);
            message = Component.literal("").append(player.getDisplayName())
                    .append(Component.literal(" falou ").withStyle(ChatFormatting.YELLOW))
                    .append(name)
                    .append(Component.literal("! Nasceram " + spawned + ".").withStyle(ChatFormatting.YELLOW));
        }
        LOGGER.info("{} disse '{}' -> {}", player.getGameProfile().getName(), text, target.id());
        if (cfg.announceToEveryone && server != null) {
            server.getPlayerList().broadcastSystemMessage(message, false);
        } else {
            player.sendSystemMessage(message);
        }
        return true;
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sayigual")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("falar")
                        .then(Commands.argument("texto", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String text = StringArgumentType.getString(ctx, "texto");
                                    if (!handleSpeech(player, text, true)) {
                                        ctx.getSource().sendFailure(Component.literal("Nenhum bloco ou mob encontrado em: " + text));
                                        return 0;
                                    }
                                    return 1;
                                })))
                .then(Commands.literal("ligar").executes(ctx -> setEnabled(ctx.getSource(), true)))
                .then(Commands.literal("desligar").executes(ctx -> setEnabled(ctx.getSource(), false)))
                .then(Commands.literal("recarregar").executes(ctx -> {
                    reload();
                    ctx.getSource().sendSuccess(() -> Component.literal("SayIgual: configuração recarregada"), true);
                    return 1;
                }))
                .then(Commands.literal("status").executes(ctx -> {
                    SayIgualConfig cfg = config();
                    ctx.getSource().sendSuccess(() -> Component.literal("SayIgual: " + (cfg.enabled ? "ligado" : "desligado")
                            + " | voz: " + speech().status()
                            + " | trocas pendentes: " + EFFECTS.pendingJobs()), false);
                    return 1;
                })));
    }

    private static int setEnabled(CommandSourceStack source, boolean enabled) {
        SayIgualConfig cfg = config();
        cfg.enabled = enabled;
        cfg.save();
        source.sendSuccess(() -> Component.literal("SayIgual " + (enabled ? "ligado" : "desligado")), true);
        return 1;
    }
}
