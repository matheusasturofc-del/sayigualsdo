package com.matheusasturo.sayigualsdo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuração salva em config/sayigualsdo.json.
 */
public class SayIgualConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Liga/desliga o mod inteiro. */
    public boolean enabled = true;

    /** Quantas chunks (as mais próximas de quem falou) têm os blocos trocados. */
    public int chunkCount = 64;

    /** Quantos mobs nascem quando alguém fala o nome de um mob. */
    public int mobCount = 20;

    /** Raio (em blocos) em volta do jogador onde os mobs nascem. */
    public int mobSpawnRadius = 8;

    /** Máximo de blocos trocados por tick (evita travar o servidor). */
    public int blocksPerTick = 40000;

    /** Tempo mínimo entre dois efeitos do mesmo jogador. */
    public double cooldownSeconds = 3.0;

    /** Se true, também troca o ar (o volume inteiro vira o bloco). */
    public boolean replaceAir = false;

    /** Mostra na action bar o que o reconhecimento de voz entendeu. */
    public boolean showTranscripts = true;

    /** Avisa no chat de todos quem falou o quê. */
    public boolean announceToEveryone = true;

    /** Toca o som de XP quando termina de trocar os blocos ou de fazer os mobs nascerem. */
    public boolean playXpSound = true;

    /** Silêncio (ms) após o qual a frase é considerada terminada. */
    public int utteranceTimeoutMs = 600;

    /** Nomes com menos letras que isso são ignorados (evita falsos positivos). */
    public int minNameLength = 3;

    /**
     * Restringe o reconhecimento só aos nomes de blocos/mobs. Fica mais preciso para os nomes,
     * mas qualquer fala tende a virar algum bloco.
     */
    public boolean useGrammar = false;

    /** Baixa automaticamente os modelos de voz na primeira vez. */
    public boolean autoDownloadModels = true;

    /** Modelos Vosk por idioma (podem ser trocados por modelos maiores). */
    public Map<String, String> modelUrls = new LinkedHashMap<>(Map.of(
            "pt", "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip",
            "en", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    ));

    /** Blocos que nunca são usados. */
    public List<String> blockBlacklist = new ArrayList<>(List.of(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
            "minecraft:moving_piston", "minecraft:piston_head",
            "minecraft:light", "minecraft:structure_void"
    ));

    /** Mobs que nunca são usados (ex.: "minecraft:ender_dragon"). */
    public List<String> entityBlacklist = new ArrayList<>();

    /** Apelidos extras: "frase falada" -> id do bloco ou mob. */
    public Map<String, String> aliases = new LinkedHashMap<>(Map.of(
            "diamante", "minecraft:diamond_block",
            "ouro", "minecraft:gold_block",
            "esmeralda", "minecraft:emerald_block",
            "grama", "minecraft:grass_block",
            "agua", "minecraft:water",
            "lava", "minecraft:lava"
    ));

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(SayIgualMod.MOD_ID + ".json");
    }

    public static SayIgualConfig load() {
        Path path = path();
        SayIgualConfig config = null;
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                config = GSON.fromJson(reader, SayIgualConfig.class);
            } catch (Exception e) {
                SayIgualMod.LOGGER.error("Não foi possível ler {}, usando o padrão", path, e);
            }
        }
        if (config == null) {
            config = new SayIgualConfig();
        }
        config.save();
        return config;
    }

    public void save() {
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            SayIgualMod.LOGGER.error("Não foi possível salvar {}", path, e);
        }
    }
}
