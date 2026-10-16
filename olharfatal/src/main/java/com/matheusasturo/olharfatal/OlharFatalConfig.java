package com.matheusasturo.olharfatal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuração salva em config/olharfatal.json.
 */
public class OlharFatalConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Liga/desliga o mod inteiro. */
    public boolean enabled = true;
    /**
     * Quão "no meio da tela" o jogador do time Kill precisa estar (em graus, a partir da mira).
     * Basta uma parte do corpo (rosto, pernas, braços...) estar dentro desse ângulo.
     */
    public int viewAngle = 35;
    /** Distância máxima (em blocos) em que o olhar ainda congela. */
    public int maxDistance = 64;
    /** Se true, dá para congelar olhando através de vidro, folhas, grades etc. */
    public boolean seeThroughGlass = true;
    /** Contorno vermelho (efeito brilhante) no jogador congelado. */
    public boolean redOutline = true;
    /** Partículas vermelhas saindo do jogador congelado. */
    public boolean redParticles = true;
    /** Quantas partículas por tick. */
    public int particlesPerTick = 3;
    /** Enquanto congelado, o time Kill também não pode bater, quebrar ou usar itens. */
    public boolean blockActionsWhileFrozen = true;
    /** O time Kill mata o time View com um hit. */
    public boolean oneHitKill = true;
    /** Jogadores cegos (efeito cegueira) não congelam ninguém. */
    public boolean blindnessStopsGaze = true;

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(OlharFatalMod.MOD_ID + ".json");
    }

    public static OlharFatalConfig load() {
        Path path = path();
        OlharFatalConfig config = null;
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                config = GSON.fromJson(reader, OlharFatalConfig.class);
            } catch (Exception e) {
                OlharFatalMod.LOGGER.error("Não foi possível ler {}, usando o padrão", path, e);
            }
        }
        if (config == null) {
            config = new OlharFatalConfig();
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
            OlharFatalMod.LOGGER.error("Não foi possível salvar {}", path, e);
        }
    }
}
