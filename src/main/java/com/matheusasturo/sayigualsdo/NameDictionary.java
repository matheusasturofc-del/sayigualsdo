package com.matheusasturo.sayigualsdo;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Todos os nomes (português e inglês) de blocos e mobs, normalizados para comparação com o que foi falado.
 */
public final class NameDictionary {

    public enum Kind { BLOCK, ENTITY }

    public record Target(Kind kind, ResourceLocation id, String translationKey) {}

    public record Match(Target target, String phrase) {}

    /** Frase normalizada -> alvo. */
    private final Map<String, Target> names = new HashMap<>();
    /** Nomes "bonitos" (com acentos) por idioma, usados na gramática do reconhecedor. */
    private final Map<String, Set<String>> rawNames = new HashMap<>();
    private final int minNameLength;

    private NameDictionary(int minNameLength) {
        this.minNameLength = minNameLength;
    }

    public static NameDictionary build(SayIgualConfig config) {
        NameDictionary dict = new NameDictionary(config.minNameLength);
        Map<String, String> pt = loadNames("pt_br");
        Map<String, String> en = loadNames("en_us");
        Language current = Language.getInstance();
        Set<String> blockBlacklist = new HashSet<>(config.blockBlacklist);
        Set<String> entityBlacklist = new HashSet<>(config.entityBlacklist);

        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (blockBlacklist.contains(id.toString()) || block.defaultBlockState().isAir()) {
                continue;
            }
            String key = block.getDescriptionId();
            dict.addAll(new Target(Kind.BLOCK, id, key), pt.get(key), en.get(key), current, id);
        }

        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            // Só mobs "de verdade": os que têm ovo de spawn.
            if (SpawnEggItem.byId(type) == null) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (entityBlacklist.contains(id.toString())) {
                continue;
            }
            String key = type.getDescriptionId();
            dict.addAll(new Target(Kind.ENTITY, id, key), pt.get(key), en.get(key), current, id);
        }

        config.aliases.forEach((phrase, idString) -> {
            ResourceLocation id = ResourceLocation.tryParse(idString);
            if (id == null) {
                SayIgualMod.LOGGER.warn("Apelido '{}' aponta para um id inválido: {}", phrase, idString);
                return;
            }
            Target target;
            if (BuiltInRegistries.BLOCK.containsKey(id) && !blockBlacklist.contains(id.toString())) {
                target = new Target(Kind.BLOCK, id, BuiltInRegistries.BLOCK.get(id).getDescriptionId());
            } else if (BuiltInRegistries.ENTITY_TYPE.containsKey(id) && !entityBlacklist.contains(id.toString())) {
                target = new Target(Kind.ENTITY, id, BuiltInRegistries.ENTITY_TYPE.get(id).getDescriptionId());
            } else {
                SayIgualMod.LOGGER.warn("Apelido '{}' aponta para algo que não existe: {}", phrase, idString);
                return;
            }
            // Apelidos do usuário têm prioridade.
            String normalized = normalize(phrase);
            if (!normalized.isEmpty()) {
                dict.names.put(normalized, target);
                dict.raw("pt", phrase);
                dict.raw("en", phrase);
            }
        });

        SayIgualMod.LOGGER.info("SayIgual: {} nomes de blocos/mobs carregados", dict.names.size());
        return dict;
    }

    private void addAll(Target target, String ptName, String enName, Language current, ResourceLocation id) {
        add(target, ptName, "pt");
        add(target, enName, "en");
        // Nome do registro: "diamond_block" -> "diamond block"
        add(target, id.getPath().replace('_', ' '), "en");
        // Blocos de outros mods: usa o idioma carregado no momento.
        String translated = current.getOrDefault(target.translationKey());
        if (!translated.equals(target.translationKey())) {
            add(target, translated, null);
        }
        // "Block of Diamond" -> "diamond block"
        if (enName != null && enName.startsWith("Block of ")) {
            add(target, enName.substring("Block of ".length()) + " block", "en");
        }
    }

    private void add(Target target, String name, String lang) {
        if (name == null) {
            return;
        }
        String normalized = normalize(name);
        if (normalized.replace(" ", "").length() < minNameLength) {
            return;
        }
        // O primeiro registrado ganha (ex.: "torch" antes de "wall_torch").
        names.putIfAbsent(normalized, target);
        if (lang != null) {
            raw(lang, name);
        }
    }

    private void raw(String lang, String name) {
        rawNames.computeIfAbsent(lang, l -> new LinkedHashSet<>()).add(name.toLowerCase(Locale.ROOT));
    }

    /** Procura o nome de bloco/mob mais longo dentro do texto falado. */
    public Optional<Match> find(String spoken) {
        String text = " " + normalize(spoken) + " ";
        if (text.isBlank()) {
            return Optional.empty();
        }
        String best = null;
        for (String name : names.keySet()) {
            if ((best == null || name.length() > best.length()) && text.contains(" " + name + " ")) {
                best = name;
            }
        }
        return best == null ? Optional.empty() : Optional.of(new Match(names.get(best), best));
    }

    /** Frases para a gramática do Vosk ("pt" ou "en"). */
    public Collection<String> phrases(String lang) {
        return rawNames.getOrDefault(lang, Set.of());
    }

    /**
     * Minúsculas, sem acentos, sem pontuação e sem o "s" do plural
     * ("Blocos de Diamante" == "bloco de diamante", "zumbis" == "zumbi").
     */
    public static String normalize(String input) {
        String s = Normalizer.normalize(input.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        if (s.isEmpty()) {
            return s;
        }
        StringBuilder out = new StringBuilder();
        for (String word : s.split(" ")) {
            if (word.length() > 3 && word.endsWith("s")) {
                word = word.substring(0, word.length() - 1);
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(word);
        }
        return out.toString();
    }

    private static Map<String, String> loadNames(String lang) {
        Map<String, String> map = new HashMap<>();
        String path = "/assets/" + SayIgualMod.MOD_ID + "/names/" + lang + ".json";
        try (InputStream in = NameDictionary.class.getResourceAsStream(path)) {
            if (in == null) {
                SayIgualMod.LOGGER.warn("Arquivo de nomes não encontrado: {}", path);
                return map;
            }
            JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            json.entrySet().forEach(e -> map.put(e.getKey(), e.getValue().getAsString()));
        } catch (Exception e) {
            SayIgualMod.LOGGER.error("Erro ao ler {}", path, e);
        }
        return map;
    }
}
