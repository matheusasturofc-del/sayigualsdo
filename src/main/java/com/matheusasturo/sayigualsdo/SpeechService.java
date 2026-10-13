package com.matheusasturo.sayigualsdo;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import net.fabricmc.loader.api.FabricLoader;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Recebe o áudio do Simple Voice Chat, decodifica o Opus e passa pelo Vosk (reconhecimento de voz offline)
 * em português e em inglês ao mesmo tempo.
 */
public final class SpeechService {

    private static final float SAMPLE_RATE = 16000f;

    /** Todo o trabalho de áudio roda nesta única thread (decoders e recognizers não são thread-safe). */
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SayIgual-Speech");
        t.setDaemon(true);
        return t;
    });

    /** Idioma -> modelo carregado. */
    private final Map<String, Model> models = new ConcurrentHashMap<>();
    /** Só acessado na thread do executor. */
    private final Map<UUID, Session> sessions = new HashMap<>();

    private volatile VoicechatApi voicechat;
    private volatile SayIgualConfig config;
    private volatile NameDictionary dictionary;
    private volatile String status = "carregando modelos de voz...";
    private final BiConsumer<UUID, String> onText;

    public SpeechService(SayIgualConfig config, BiConsumer<UUID, String> onText) {
        this.config = config;
        this.onText = onText;
        executor.scheduleWithFixedDelay(this::checkTimeouts, 100, 100, TimeUnit.MILLISECONDS);
    }

    public void setVoicechat(VoicechatApi api) {
        this.voicechat = api;
    }

    public String status() {
        return status;
    }

    /** Chamado sempre que config ou dicionário mudam: os reconhecedores são recriados. */
    public void update(SayIgualConfig config, NameDictionary dictionary) {
        this.config = config;
        this.dictionary = dictionary;
        executor.execute(() -> {
            sessions.values().forEach(Session::close);
            sessions.clear();
        });
    }

    /** Baixa (se precisar) e carrega os modelos em segundo plano. */
    public void loadModelsAsync() {
        Thread thread = new Thread(this::loadModels, "SayIgual-ModelLoader");
        thread.setDaemon(true);
        thread.start();
    }

    private void loadModels() {
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
        } catch (Throwable t) {
            status = "ERRO: a biblioteca do Vosk não carregou neste sistema (" + t + ")";
            SayIgualMod.LOGGER.error("Não foi possível carregar a biblioteca nativa do Vosk", t);
            return;
        }
        Path modelsDir = FabricLoader.getInstance().getGameDir().resolve(SayIgualMod.MOD_ID).resolve("models");
        List<String> loaded = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Map.Entry<String, String> entry : new LinkedHashMap<>(config.modelUrls).entrySet()) {
            String lang = entry.getKey();
            if (models.containsKey(lang)) {
                loaded.add(lang);
                continue;
            }
            try {
                Path dir = ensureModel(modelsDir, entry.getValue());
                SayIgualMod.LOGGER.info("Carregando modelo de voz '{}' de {}", lang, dir);
                models.put(lang, new Model(dir.toString()));
                loaded.add(lang);
            } catch (Throwable t) {
                failed.add(lang);
                SayIgualMod.LOGGER.error("Não foi possível carregar o modelo de voz '{}'", lang, t);
            }
        }
        status = loaded.isEmpty()
                ? "ERRO: nenhum modelo de voz carregado (veja o log; coloque os modelos em " + modelsDir + ")"
                : "ouvindo em " + String.join(", ", loaded) + (failed.isEmpty() ? "" : " (falhou: " + String.join(", ", failed) + ")");
        SayIgualMod.LOGGER.info("SayIgual: {}", status);
        // Recria as sessões para incluir os modelos novos.
        executor.execute(() -> {
            sessions.values().forEach(Session::close);
            sessions.clear();
        });
    }

    private Path ensureModel(Path modelsDir, String url) throws IOException, InterruptedException {
        String fileName = url.substring(url.lastIndexOf('/') + 1);
        String name = fileName.endsWith(".zip") ? fileName.substring(0, fileName.length() - 4) : fileName;
        Path dir = modelsDir.resolve(name);
        if (Files.isDirectory(dir)) {
            return dir;
        }
        if (!config.autoDownloadModels) {
            throw new IOException("Modelo não encontrado em " + dir + " e o download automático está desligado");
        }
        Files.createDirectories(modelsDir);
        status = "baixando modelo de voz " + name + "...";
        SayIgualMod.LOGGER.info("Baixando modelo de voz {} (só na primeira vez)", url);
        Path zip = modelsDir.resolve(fileName + ".part");
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<Path> response = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofFile(zip));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(zip);
            throw new IOException("HTTP " + response.statusCode() + " ao baixar " + url);
        }
        Path tmp = modelsDir.resolve(name + ".extracting");
        deleteRecursively(tmp);
        unzip(zip, tmp);
        Files.deleteIfExists(zip);
        // O zip normalmente tem uma pasta com o nome do modelo dentro.
        Path inner = tmp.resolve(name);
        Files.move(Files.isDirectory(inner) ? inner : tmp, dir, StandardCopyOption.REPLACE_EXISTING);
        deleteRecursively(tmp);
        return dir;
    }

    private static void unzip(Path zip, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        try (InputStream in = Files.newInputStream(zip); ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                Path out = root.resolve(entry.getName()).normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("Entrada inválida no zip: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /** Chamado pela thread do Simple Voice Chat a cada pacote de microfone (~20 ms de áudio). */
    public void onMicrophonePacket(UUID player, byte[] opus) {
        executor.execute(() -> {
            try {
                handlePacket(player, opus);
            } catch (Throwable t) {
                SayIgualMod.LOGGER.error("Erro no reconhecimento de voz", t);
            }
        });
    }

    public void onPlayerLeft(UUID player) {
        executor.execute(() -> {
            Session session = sessions.remove(player);
            if (session != null) {
                session.close();
            }
        });
    }

    private void handlePacket(UUID player, byte[] opus) {
        if (models.isEmpty() || voicechat == null || !config.enabled) {
            return;
        }
        Session session = sessions.get(player);
        if (session == null) {
            session = new Session(voicechat.createDecoder());
            sessions.put(player, session);
        }
        if (opus.length == 0) {
            // Pacote vazio = parou de falar.
            finish(player, session);
            return;
        }
        short[] pcm = session.decoder.decode(opus);
        short[] samples = downsample48To16(pcm);
        session.lastAudio = System.currentTimeMillis();
        session.hasAudio = true;
        for (Recognizer recognizer : session.recognizers()) {
            if (recognizer.acceptWaveForm(samples, samples.length)) {
                emit(player, recognizer.getResult());
            }
        }
    }

    private void checkTimeouts() {
        try {
            long now = System.currentTimeMillis();
            for (Iterator<Map.Entry<UUID, Session>> it = sessions.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, Session> entry = it.next();
                Session session = entry.getValue();
                if (session.hasAudio && now - session.lastAudio > config.utteranceTimeoutMs) {
                    finish(entry.getKey(), session);
                }
            }
        } catch (Throwable t) {
            SayIgualMod.LOGGER.error("Erro no reconhecimento de voz", t);
        }
    }

    private void finish(UUID player, Session session) {
        if (!session.hasAudio) {
            return;
        }
        session.hasAudio = false;
        for (Recognizer recognizer : session.recognizers()) {
            emit(player, recognizer.getFinalResult());
        }
        session.decoder.resetState();
    }

    private void emit(UUID player, String json) {
        try {
            String text = JsonParser.parseString(json).getAsJsonObject().get("text").getAsString().trim();
            if (!text.isEmpty() && !text.equals("[unk]")) {
                onText.accept(player, text);
            }
        } catch (Exception e) {
            SayIgualMod.LOGGER.debug("Resultado do Vosk inesperado: {}", json, e);
        }
    }

    /** O Simple Voice Chat usa 48 kHz; os modelos do Vosk usam 16 kHz. Média de 3 amostras = filtro simples. */
    private static short[] downsample48To16(short[] in) {
        short[] out = new short[in.length / 3];
        for (int i = 0; i < out.length; i++) {
            int j = i * 3;
            out[i] = (short) ((in[j] + in[j + 1] + in[j + 2]) / 3);
        }
        return out;
    }

    public void shutdown() {
        executor.execute(() -> {
            sessions.values().forEach(Session::close);
            sessions.clear();
        });
    }

    /** Estado de um jogador falando: decoder Opus + um reconhecedor por idioma. */
    private final class Session {
        final OpusDecoder decoder;
        final Map<String, Recognizer> recognizers = new LinkedHashMap<>();
        long lastAudio;
        boolean hasAudio;

        Session(OpusDecoder decoder) {
            this.decoder = decoder;
            models.forEach((lang, model) -> {
                try {
                    recognizers.put(lang, createRecognizer(lang, model));
                } catch (IOException e) {
                    SayIgualMod.LOGGER.error("Não foi possível criar o reconhecedor '{}'", lang, e);
                }
            });
        }

        Iterable<Recognizer> recognizers() {
            return recognizers.values();
        }

        void close() {
            recognizers.values().forEach(Recognizer::close);
            recognizers.clear();
            if (!decoder.isClosed()) {
                decoder.close();
            }
        }
    }

    private Recognizer createRecognizer(String lang, Model model) throws IOException {
        NameDictionary dict = dictionary;
        if (config.useGrammar && dict != null) {
            List<String> phrases = new ArrayList<>(dict.phrases(lang));
            if (!phrases.isEmpty()) {
                phrases.add("[unk]");
                return new Recognizer(model, SAMPLE_RATE, new Gson().toJson(phrases));
            }
        }
        return new Recognizer(model, SAMPLE_RATE);
    }
}
