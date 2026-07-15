package dev.modichamiya.eclipse.content;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.modichamiya.eclipse.api.EclipseApi;
import dev.modichamiya.eclipse.core.CoreRuntime;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public final class ContentEngineModule implements CoreRuntime.EngineModule {
    @Override
    public String id() {
        return "content";
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("core", "config", "registry", "assets", "animation", "gui", "world", "ai", "admin", "gameplay");
    }

    @Override
    public void onEnable(CoreRuntime.ModuleContext context) {
        EclipseApi.ConfigService configService = context.services().require(EclipseApi.ConfigService.class);
        EclipseApi.RegistryService registryService = context.services().require(EclipseApi.RegistryService.class);
        ContentServiceImpl service = new ContentServiceImpl(context, configService, registryService);
        context.services().register(EclipseApi.ContentService.class, service);
        service.ensureDefaultFiles();
        EclipseApi.ContentReloadResult result = service.reloadContent().join();
        if (!result.success()) {
            throw new IllegalStateException("Initial content load failed: " + result.errors());
        }
        context.logger(id()).info("Content backbone online with registries " + result.registryCounts());
    }

    @Override
    public void onReload(CoreRuntime.ModuleContext context) {
        EclipseApi.ContentReloadResult result = context.services().require(EclipseApi.ContentService.class).reloadContent().join();
        if (!result.success()) {
            throw new IllegalStateException("Content reload failed: " + result.errors());
        }
        context.eventBus().publish(new EclipseApi.ContentReloadedEvent(result.loadedAt()));
    }
}

final class ContentServiceImpl implements EclipseApi.ContentService {
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private final CoreRuntime.ModuleContext context;
    private final EclipseApi.ConfigService configService;
    private final EclipseApi.RegistryService registryService;
    private final Map<String, ContentSchema> schemas;
    private final Set<String> registryNames;
    private final Map<String, String> defaultContentFiles;
    private final Yaml yaml = new Yaml();
    private final Gson gson = new Gson();

    private volatile EclipseApi.ContentReloadResult currentSnapshot = EclipseApi.ContentReloadResult.empty();

    ContentServiceImpl(CoreRuntime.ModuleContext context, EclipseApi.ConfigService configService, EclipseApi.RegistryService registryService) {
        this.context = context;
        this.configService = configService;
        this.registryService = registryService;
        this.schemas = BuiltInSchemas.create();
        this.registryNames = schemas.values().stream().map(ContentSchema::registryName).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        this.defaultContentFiles = BuiltInSchemas.sampleFiles();
    }

    @Override
    public CompletableFuture<EclipseApi.ContentReloadResult> reloadContent() {
        return CompletableFuture.supplyAsync(this::reloadSynchronously, context.sharedExecutor());
    }

    @Override
    public EclipseApi.ContentReloadResult currentSnapshot() {
        return currentSnapshot;
    }

    void ensureDefaultFiles() {
        EclipseApi.ConfigSection contentConfig = configService.config("content");
        Path baseDirectory = rootDirectory(contentConfig.string("content-directory", "content"));
        try {
            Files.createDirectories(baseDirectory);
            Files.createDirectories(rootDirectory(contentConfig.string("overrides-directory", "content-overrides")));
            for (Map.Entry<String, String> entry : defaultContentFiles.entrySet()) {
                Path target = baseDirectory.resolve(entry.getKey());
                Files.createDirectories(target.getParent());
                if (Files.notExists(target)) {
                    Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scaffold default content files", exception);
        }
    }

    private EclipseApi.ContentReloadResult reloadSynchronously() {
        EclipseApi.ConfigSection contentConfig = configService.config("content");
        String requiredNamespace = contentConfig.string("required-namespace", "eclipse");
        Path baseDirectory = rootDirectory(contentConfig.string("content-directory", "content"));
        Path overrideDirectory = rootDirectory(contentConfig.string("overrides-directory", "content-overrides"));

        List<EclipseApi.ContentValidationError> errors = new ArrayList<>();
        Map<String, StagedDefinition> staged = new LinkedHashMap<>();

        loadDirectory(baseDirectory, 0, requiredNamespace, staged, errors);
        loadDirectory(overrideDirectory, 1, requiredNamespace, staged, errors);

        Map<String, Map<String, EclipseApi.GenericDefinition>> stagedByRegistry = new LinkedHashMap<>();
        for (StagedDefinition definition : staged.values()) {
            stagedByRegistry.computeIfAbsent(definition.definition.registryName(), ignored -> new LinkedHashMap<>())
                    .put(definition.definition.key(), definition.definition);
        }

        for (StagedDefinition definition : staged.values()) {
            definition.schema.validateReferences(definition.definition, stagedByRegistry, errors);
        }

        if (!errors.isEmpty()) {
            return new EclipseApi.ContentReloadResult(false, Instant.now(), currentSnapshot.registryCounts(), currentSnapshot.loadedKeys(), errors);
        }

        registryService.unfreezeAll();
        for (String registryName : registryNames) {
            Collection<EclipseApi.GenericDefinition> definitions = stagedByRegistry.getOrDefault(registryName, Map.of()).values();
            registryService.registry(registryName, EclipseApi.GenericDefinition.class).replaceAll(definitions);
        }
        registryService.freezeAll();

        Map<String, Integer> registryCounts = new LinkedHashMap<>();
        Map<String, List<String>> loadedKeys = new LinkedHashMap<>();
        registryNames.forEach(registryName -> {
            Map<String, EclipseApi.GenericDefinition> definitions = stagedByRegistry.getOrDefault(registryName, Map.of());
            registryCounts.put(registryName, definitions.size());
            loadedKeys.put(registryName, definitions.values().stream().map(EclipseApi.GenericDefinition::key).sorted().toList());
        });

        EclipseApi.ContentReloadResult result = new EclipseApi.ContentReloadResult(true, Instant.now(), registryCounts, loadedKeys, List.of());
        this.currentSnapshot = result;
        return result;
    }

    private void loadDirectory(Path root, int priority, String requiredNamespace, Map<String, StagedDefinition> staged, List<EclipseApi.ContentValidationError> errors) {
        if (Files.notExists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".json");
                    })
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> loadFile(root, path, priority, requiredNamespace, staged, errors));
        } catch (IOException exception) {
            errors.add(new EclipseApi.ContentValidationError("io_error", "Failed to walk content directory: " + exception.getMessage(), root.toString(), ""));
        }
    }

    private void loadFile(Path root, Path path, int priority, String requiredNamespace, Map<String, StagedDefinition> staged, List<EclipseApi.ContentValidationError> errors) {
        try {
            Map<String, Object> raw = parseRaw(path);
            if (raw.isEmpty()) {
                errors.add(new EclipseApi.ContentValidationError("empty_definition", "Definition file is empty", path.toString(), ""));
                return;
            }

            ContentSchema schema = resolveSchema(root, path, raw);
            if (schema == null) {
                errors.add(new EclipseApi.ContentValidationError("unknown_schema", "Could not resolve schema from file location or type field", path.toString(), ""));
                return;
            }

            EclipseApi.DefinitionSource source = new EclipseApi.DefinitionSource(path.toString(), priority);
            EclipseApi.GenericDefinition definition = schema.parse(raw, source, requiredNamespace, errors);
            if (definition == null) {
                return;
            }

            String stagedKey = definition.registryName() + "|" + definition.key();
            staged.put(stagedKey, new StagedDefinition(schema, definition));
        } catch (Exception exception) {
            errors.add(new EclipseApi.ContentValidationError("parse_failure", exception.getMessage(), path.toString(), ""));
        }
    }

    private ContentSchema resolveSchema(Path root, Path path, Map<String, Object> raw) {
        Object explicitType = raw.get("type");
        if (explicitType != null) {
            ContentSchema schema = schemas.get(String.valueOf(explicitType).trim().toLowerCase(Locale.ROOT));
            if (schema != null) {
                return schema;
            }
        }
        Path relative = root.relativize(path);
        if (relative.getNameCount() > 1) {
            String directoryName = relative.getName(0).toString().trim().toLowerCase(Locale.ROOT);
            return schemas.get(directoryName);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseRaw(Path path) throws IOException {
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (filename.endsWith(".json")) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                Map<String, Object> raw = gson.fromJson(reader, MAP_TYPE);
                return raw == null ? Map.of() : raw;
            }
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Object loaded = yaml.load(reader);
            if (loaded instanceof Map<?, ?> map) {
                Map<String, Object> converted = new LinkedHashMap<>();
                map.forEach((key, value) -> converted.put(String.valueOf(key), value));
                return converted;
            }
            return Map.of();
        }
    }

    private Path rootDirectory(String configuredPath) {
        return context.plugin().getDataFolder().toPath().resolve(configuredPath);
    }
}

record StagedDefinition(ContentSchema schema, EclipseApi.GenericDefinition definition) {
}

interface ContentSchema {
    String registryName();

    String directoryName();

    EclipseApi.GenericDefinition parse(Map<String, Object> raw, EclipseApi.DefinitionSource source, String requiredNamespace, List<EclipseApi.ContentValidationError> errors);

    void validateReferences(EclipseApi.GenericDefinition definition, Map<String, Map<String, EclipseApi.GenericDefinition>> stagedByRegistry, List<EclipseApi.ContentValidationError> errors);
}

abstract class AbstractSchema implements ContentSchema {
    protected String string(Map<String, Object> raw, String field, boolean required, EclipseApi.DefinitionSource source, List<EclipseApi.ContentValidationError> errors) {
        Object value = raw.get(field);
        if (value == null || String.valueOf(value).isBlank()) {
            if (required) {
                errors.add(new EclipseApi.ContentValidationError("missing_field", "Missing required field '" + field + "'", source.path(), ""));
            }
            return null;
        }
        return String.valueOf(value);
    }

    protected List<String> stringList(Map<String, Object> raw, String field) {
        Object value = raw.get(field);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    protected EclipseApi.NamespacedKey key(String raw, String requiredNamespace, EclipseApi.DefinitionSource source, List<EclipseApi.ContentValidationError> errors) {
        try {
            EclipseApi.NamespacedKey parsed = EclipseApi.NamespacedKey.parse(raw);
            if (!parsed.namespace().equalsIgnoreCase(requiredNamespace)) {
                errors.add(new EclipseApi.ContentValidationError("namespace_mismatch", "Expected namespace '" + requiredNamespace + "' but got '" + parsed.namespace() + "'", source.path(), raw));
            }
            return parsed;
        } catch (Exception exception) {
            errors.add(new EclipseApi.ContentValidationError("invalid_key", exception.getMessage(), source.path(), raw));
            return null;
        }
    }

    protected Map<String, Object> copyValues(Map<String, Object> raw) {
        return new LinkedHashMap<>(raw);
    }

    protected Set<String> tags(Map<String, Object> raw) {
        return new LinkedHashSet<>(stringList(raw, "tags"));
    }

    protected void requireReference(String field, EclipseApi.GenericDefinition definition, Map<String, Map<String, EclipseApi.GenericDefinition>> stagedByRegistry, String targetRegistry, List<EclipseApi.ContentValidationError> errors) {
        for (EclipseApi.NamespacedKey reference : definition.references().getOrDefault(field, List.of())) {
            if (!stagedByRegistry.getOrDefault(targetRegistry, Map.of()).containsKey(reference.asString())) {
                errors.add(new EclipseApi.ContentValidationError("dangling_reference", "Missing " + targetRegistry + " definition '" + reference + "' referenced by field '" + field + "'", definition.source().path(), definition.key()));
            }
        }
    }
}

final class BuiltInSchemas {
    private BuiltInSchemas() {
    }

    static Map<String, ContentSchema> create() {
        Map<String, ContentSchema> schemas = new LinkedHashMap<>();
        register(schemas, new AbilitySchema());
        register(schemas, new ItemSchema());
        register(schemas, new MobSchema());
        register(schemas, new LootTableSchema());
        register(schemas, new RankSchema());
        return Map.copyOf(schemas);
    }

    static Map<String, String> sampleFiles() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("abilities/eclipse_arcane_bolt.yml", "type: ability\nkey: eclipse:arcane_bolt\nname: Arcane Bolt\ntimeline: eclipse:cast_arcane_bolt\nmana_cost: 15\ncooldown_seconds: 3.5\ntags:\n  - spell\n  - mage\n");
        files.put("abilities/eclipse_chain_lightning.yml", "type: ability\nkey: eclipse:chain_lightning\nname: Chain Lightning\ntimeline: eclipse:cast_chain_lightning\nmana_cost: 40\ncooldown_seconds: 10\ntags:\n  - spell\n  - storm\n  - mastery:staff\n");
        files.put("items/eclipse_apprentice_staff.yml", "type: item\nkey: eclipse:apprentice_staff\nname: Apprentice Staff\nrarity: uncommon\nmodel: eclipse:item/apprentice_staff\nicon: eclipse:icon/apprentice_staff\nabilities:\n  - eclipse:arcane_bolt\n  - eclipse:chain_lightning\ntags:\n  - weapon\n  - staff\n  - starter\n");
        files.put("items/eclipse_stormbound_talisman.yml", "type: item\nkey: eclipse:stormbound_talisman\nname: Stormbound Talisman\nrarity: rare\nicon: eclipse:icon/stormbound_talisman\nabilities:\n  - eclipse:chain_lightning\ntags:\n  - accessory\n  - mage\n");
        files.put("loot_tables/eclipse_arcane_wisp.yml", "type: loot_table\nkey: eclipse:arcane_wisp_loot\nname: Arcane Wisp Loot\ndrops:\n  - key: eclipse:apprentice_staff\n    weight: 5\n    chance: 0.05\n  - key: eclipse:stormbound_talisman\n    weight: 15\n    chance: 0.2\n");
        files.put("mobs/eclipse_arcane_wisp.yml", "type: mob\nkey: eclipse:arcane_wisp\nname: Arcane Wisp\nlevel: 6\nbehavior: eclipse:basic_ranged_caster\nloot_table: eclipse:arcane_wisp_loot\nabilities:\n  - eclipse:arcane_bolt\ntags:\n  - starter_region\n  - magic\n");
        files.put("mobs/eclipse_tempest_acolyte.yml", "type: mob\nkey: eclipse:tempest_acolyte\nname: Tempest Acolyte\nlevel: 12\nbehavior: eclipse:elite_storm_mage\nloot_table: eclipse:arcane_wisp_loot\nabilities:\n  - eclipse:arcane_bolt\n  - eclipse:chain_lightning\ntags:\n  - elite\n  - storm\n");
        files.put("ranks/eclipse_helper.yml", "type: rank\nkey: eclipse:helper\nname: Helper\ncosmetic: false\npermissions:\n  - eclipse.admin\n  - eclipse.editor.view\ntags:\n  - staff\n");
        files.put("ranks/eclipse_wayfarer.yml", "type: rank\nkey: eclipse:wayfarer\nname: Wayfarer\ncosmetic: true\npermissions:\n  - eclipse.chat.cosmetic.wayfarer\ntags:\n  - cosmetic\n");
        return files;
    }

    private static void register(Map<String, ContentSchema> schemas, ContentSchema schema) {
        schemas.put(schema.registryName(), schema);
        schemas.put(schema.directoryName(), schema);
    }
}

final class AbilitySchema extends AbstractSchema {
    @Override
    public String registryName() {
        return "ability";
    }

    @Override
    public String directoryName() {
        return "abilities";
    }

    @Override
    public EclipseApi.GenericDefinition parse(Map<String, Object> raw, EclipseApi.DefinitionSource source, String requiredNamespace, List<EclipseApi.ContentValidationError> errors) {
        String rawKey = string(raw, "key", true, source, errors);
        String name = Optional.ofNullable(string(raw, "name", true, source, errors)).orElse(rawKey);
        String timeline = string(raw, "timeline", true, source, errors);
        if (rawKey == null || timeline == null) {
            return null;
        }
        EclipseApi.NamespacedKey key = key(rawKey, requiredNamespace, source, errors);
        if (key == null) {
            return null;
        }
        Map<String, Object> values = copyValues(raw);
        values.putIfAbsent("mana_cost", 0);
        values.putIfAbsent("cooldown_seconds", 0.0D);
        return new EclipseApi.GenericDefinition(key, registryName(), name, tags(raw), values, Map.of(), source);
    }

    @Override
    public void validateReferences(EclipseApi.GenericDefinition definition, Map<String, Map<String, EclipseApi.GenericDefinition>> stagedByRegistry, List<EclipseApi.ContentValidationError> errors) {
    }
}

final class ItemSchema extends AbstractSchema {
    @Override
    public String registryName() {
        return "item";
    }

    @Override
    public String directoryName() {
        return "items";
    }

    @Override
    public EclipseApi.GenericDefinition parse(Map<String, Object> raw, EclipseApi.DefinitionSource source, String requiredNamespace, List<EclipseApi.ContentValidationError> errors) {
        String rawKey = string(raw, "key", true, source, errors);
        String name = Optional.ofNullable(string(raw, "name", true, source, errors)).orElse(rawKey);
        if (rawKey == null) {
            return null;
        }
        EclipseApi.NamespacedKey key = key(rawKey, requiredNamespace, source, errors);
        if (key == null) {
            return null;
        }
        List<EclipseApi.NamespacedKey> abilityRefs = stringList(raw, "abilities").stream()
                .map(reference -> key(reference, requiredNamespace, source, errors))
                .filter(Objects::nonNull)
                .toList();
        return new EclipseApi.GenericDefinition(key, registryName(), name, tags(raw), copyValues(raw), Map.of("abilities", abilityRefs), source);
    }

    @Override
    public void validateReferences(EclipseApi.GenericDefinition definition, Map<String, Map<String, EclipseApi.GenericDefinition>> stagedByRegistry, List<EclipseApi.ContentValidationError> errors) {
        requireReference("abilities", definition, stagedByRegistry, "ability", errors);
    }
}

final class MobSchema extends AbstractSchema {
    @Override
    public String registryName() {
        return "mob";
    }

    @Override
    public String directoryName() {
        return "mobs";
    }

    @Override
    public EclipseApi.GenericDefinition parse(Map<String, Object> raw, EclipseApi.DefinitionSource source, String requiredNamespace, List<EclipseApi.ContentValidationError> errors) {
        String rawKey = string(raw, "key", true, source, errors);
        String name = Optional.ofNullable(string(raw, "name", true, source, errors)).orElse(rawKey);
        String lootTable = string(raw, "loot_table", true, source, errors);
        if (rawKey == null || lootTable == null) {
            return null;
        }
        EclipseApi.NamespacedKey key = key(rawKey, requiredNamespace, source, errors);
        EclipseApi.NamespacedKey lootTableKey = key(lootTable, requiredNamespace, source, errors);
        if (key == null || lootTableKey == null) {
            return null;
        }
        List<EclipseApi.NamespacedKey> abilityRefs = stringList(raw, "abilities").stream()
                .map(reference -> key(reference, requiredNamespace, source, errors))
                .filter(Objects::nonNull)
                .toList();
        Map<String, List<EclipseApi.NamespacedKey>> references = new LinkedHashMap<>();
        references.put("abilities", abilityRefs);
        references.put("loot_table", List.of(lootTableKey));
        return new EclipseApi.GenericDefinition(key, registryName(), name, tags(raw), copyValues(raw), references, source);
    }

    @Override
    public void validateReferences(EclipseApi.GenericDefinition definition, Map<String, Map<String, EclipseApi.GenericDefinition>> stagedByRegistry, List<EclipseApi.ContentValidationError> errors) {
        requireReference("abilities", definition, stagedByRegistry, "ability", errors);
        requireReference("loot_table", definition, stagedByRegistry, "loot_table", errors);
    }
}

final class LootTableSchema extends AbstractSchema {
    @Override
    public String registryName() {
        return "loot_table";
    }

    @Override
    public String directoryName() {
        return "loot_tables";
    }

    @Override
    public EclipseApi.GenericDefinition parse(Map<String, Object> raw, EclipseApi.DefinitionSource source, String requiredNamespace, List<EclipseApi.ContentValidationError> errors) {
        String rawKey = string(raw, "key", true, source, errors);
        String name = Optional.ofNullable(string(raw, "name", true, source, errors)).orElse(rawKey);
        if (rawKey == null) {
            return null;
        }
        EclipseApi.NamespacedKey key = key(rawKey, requiredNamespace, source, errors);
        if (key == null) {
            return null;
        }

        Object dropsObject = raw.get("drops");
        if (!(dropsObject instanceof List<?> drops) || drops.isEmpty()) {
            errors.add(new EclipseApi.ContentValidationError("missing_field", "Loot tables require a non-empty 'drops' list", source.path(), rawKey));
            return null;
        }

        List<EclipseApi.NamespacedKey> references = new ArrayList<>();
        for (Object drop : drops) {
            if (!(drop instanceof Map<?, ?> map)) {
                errors.add(new EclipseApi.ContentValidationError("invalid_drop", "Each loot-table drop must be an object", source.path(), rawKey));
                continue;
            }
            Object ref = map.get("key");
            if (ref == null) {
                errors.add(new EclipseApi.ContentValidationError("invalid_drop", "Loot-table drop is missing 'key'", source.path(), rawKey));
                continue;
            }
            EclipseApi.NamespacedKey parsed = key(String.valueOf(ref), requiredNamespace, source, errors);
            if (parsed != null) {
                references.add(parsed);
            }
        }

        return new EclipseApi.GenericDefinition(key, registryName(), name, tags(raw), copyValues(raw), Map.of("drops", references), source);
    }

    @Override
    public void validateReferences(EclipseApi.GenericDefinition definition, Map<String, Map<String, EclipseApi.GenericDefinition>> stagedByRegistry, List<EclipseApi.ContentValidationError> errors) {
        requireReference("drops", definition, stagedByRegistry, "item", errors);
    }
}

final class RankSchema extends AbstractSchema {
    @Override
    public String registryName() {
        return "rank";
    }

    @Override
    public String directoryName() {
        return "ranks";
    }

    @Override
    public EclipseApi.GenericDefinition parse(Map<String, Object> raw, EclipseApi.DefinitionSource source, String requiredNamespace, List<EclipseApi.ContentValidationError> errors) {
        String rawKey = string(raw, "key", true, source, errors);
        String name = Optional.ofNullable(string(raw, "name", true, source, errors)).orElse(rawKey);
        if (rawKey == null) {
            return null;
        }
        EclipseApi.NamespacedKey key = key(rawKey, requiredNamespace, source, errors);
        if (key == null) {
            return null;
        }
        Map<String, Object> values = copyValues(raw);
        values.putIfAbsent("cosmetic", true);
        values.putIfAbsent("permissions", List.of());
        return new EclipseApi.GenericDefinition(key, registryName(), name, tags(raw), values, Map.of(), source);
    }

    @Override
    public void validateReferences(EclipseApi.GenericDefinition definition, Map<String, Map<String, EclipseApi.GenericDefinition>> stagedByRegistry, List<EclipseApi.ContentValidationError> errors) {
    }
}
