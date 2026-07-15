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
    @Override public String id() { return "content"; }
    @Override public Set<String> dependencies() { return Set.of("core", "config", "registry", "assets", "animation", "gui", "world", "ai", "admin", "gameplay"); }

    @Override
    public void onEnable(CoreRuntime.ModuleContext context) {
        EclipseApi.ConfigService configService = context.services().require(EclipseApi.ConfigService.class);
        EclipseApi.RegistryService registryService = context.services().require(EclipseApi.RegistryService.class);
        ContentServiceImpl service = new ContentServiceImpl(context, configService, registryService);
        context.services().register(EclipseApi.ContentService.class, service);
        service.ensureDefaultFiles();
        EclipseApi.ContentReloadResult result = service.reloadContent().join();
        if (!result.success()) throw new IllegalStateException("Initial content load failed: " + result.errors());
        context.eventBus().publish(new EclipseApi.ContentReloadedEvent(result.loadedAt()));
        context.logger(id()).info("Content backbone online with registries " + result.registryCounts());
    }

    @Override
    public void onReload(CoreRuntime.ModuleContext context) {
        EclipseApi.ContentReloadResult result = context.services().require(EclipseApi.ContentService.class).reloadContent().join();
        if (!result.success()) throw new IllegalStateException("Content reload failed: " + result.errors());
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

    @Override public CompletableFuture<EclipseApi.ContentReloadResult> reloadContent() { return CompletableFuture.supplyAsync(this::reloadSynchronously, context.sharedExecutor()); }
    @Override public EclipseApi.ContentReloadResult currentSnapshot() { return currentSnapshot; }

    void ensureDefaultFiles() {
        EclipseApi.ConfigSection contentConfig = configService.config("content");
        Path baseDirectory = rootDirectory(contentConfig.string("content-directory", "content"));
        try {
            Files.createDirectories(baseDirectory);
            Files.createDirectories(rootDirectory(contentConfig.string("overrides-directory", "content-overrides")));
            for (Map.Entry<String, String> entry : defaultContentFiles.entrySet()) {
                Path target = baseDirectory.resolve(entry.getKey());
                Files.createDirectories(target.getParent());
                if (Files.notExists(target)) Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
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
            stagedByRegistry.computeIfAbsent(definition.definition.registryName(), ignored -> new LinkedHashMap<>()).put(definition.definition.key(), definition.definition);
        }
        for (StagedDefinition definition : staged.values()) definition.schema.validateReferences(definition.definition, stagedByRegistry, errors);
        if (!errors.isEmpty()) return new EclipseApi.ContentReloadResult(false, Instant.now(), currentSnapshot.registryCounts(), currentSnapshot.loadedKeys(), errors);

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
        if (Files.notExists(root)) return;
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
            if (definition == null) return;
            staged.put(definition.registryName() + "|" + definition.key(), new StagedDefinition(schema, definition));
        } catch (Exception exception) {
            errors.add(new EclipseApi.ContentValidationError("parse_failure", exception.getMessage(), path.toString(), ""));
        }
    }

    private ContentSchema resolveSchema(Path root, Path path, Map<String, Object> raw) {
        Object explicitType = raw.get("type");
        if (explicitType != null) {
            ContentSchema schema = schemas.get(String.valueOf(explicitType).trim().toLowerCase(Locale.ROOT));
            if (schema != null) return schema;
        }
        Path relative = root.relativize(path);
        if (relative.getNameCount() > 1) return schemas.get(relative.getName(0).toString().trim().toLowerCase(Locale.ROOT));
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

    private Path rootDirectory(String configuredPath) { return context.plugin().getDataFolder().toPath().resolve(configuredPath); }
}

record StagedDefinition(ContentSchema schema, EclipseApi.GenericDefinition definition) {}

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
            if (required) errors.add(new EclipseApi.ContentValidationError("missing_field", "Missing required field '" + field + "'", source.path(), ""));
            return null;
        }
        return String.valueOf(value);
    }
    protected List<String> stringList(Map<String, Object> raw, String field) {
        Object value = raw.get(field);
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        return List.of();
    }
    protected EclipseApi.NamespacedKey key(String raw, String requiredNamespace, EclipseApi.DefinitionSource source, List<EclipseApi.ContentValidationError> errors) {
        try {
            EclipseApi.NamespacedKey parsed = EclipseApi.NamespacedKey.parse(raw);
            if (!parsed.namespace().equalsIgnoreCase(requiredNamespace)) errors.add(new EclipseApi.ContentValidationError("namespace_mismatch", "Expected namespace '" + requiredNamespace + "' but got '" + parsed.namespace() + "'", source.path(), raw));
            return parsed;
        } catch (Exception exception) {
            errors.add(new EclipseApi.ContentValidationError("invalid_key", exception.getMessage(), source.path(), raw));
            return null;
        }
    }
    protected Map<String, Object> copyValues(Map<String, Object> raw) { return new LinkedHashMap<>(raw); }
    protected Set<String> tags(Map<String, Object> raw) { return new LinkedHashSet<>(stringList(raw, "tags")); }
    protected void requireReference(String field, EclipseApi.GenericDefinition definition, Map<String, Map<String, EclipseApi.GenericDefinition>> stagedByRegistry, String targetRegistry, List<EclipseApi.ContentValidationError> errors) {
        for (EclipseApi.NamespacedKey reference : definition.references().getOrDefault(field, List.of())) {
            if (!stagedByRegistry.getOrDefault(targetRegistry, Map.of()).containsKey(reference.asString())) {
                errors.add(new EclipseApi.ContentValidationError("dangling_reference", "Missing " + targetRegistry + " definition '" + reference + "' referenced by field '" + field + "'", definition.source().path(), definition.key()));
            }
        }
    }
}

final class BuiltInSchemas {
    private BuiltInSchemas() {}

    static Map<String, ContentSchema> create() {
        Map<String, ContentSchema> schemas = new LinkedHashMap<>();
        register(schemas, new AttributeSchema());
        register(schemas, new SkillSchema());
        register(schemas, new CollectionSchema());
        register(schemas, new AssetSchema());
        register(schemas, new TimelineSchema());
        register(schemas, new GuiScreenSchema());
        register(schemas, new RegionSchema());
        register(schemas, new WarpSchema());
        register(schemas, new DimensionSchema());
        register(schemas, new AbilitySchema());
        register(schemas, new ItemSchema());
        register(schemas, new LootTableSchema());
        register(schemas, new MobSchema());
        register(schemas, new RankSchema());
        return Map.copyOf(schemas);
    }

    static Map<String, String> sampleFiles() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("attributes/eclipse_strength.yml", "type: attribute\nkey: eclipse:strength\nname: Strength\nbase_value: 10\nminimum: 0\nmaximum: 9999\ncategory: combat\ntags:\n  - stat\n  - combat\n");
        files.put("attributes/eclipse_intelligence.yml", "type: attribute\nkey: eclipse:intelligence\nname: Intelligence\nbase_value: 25\nminimum: 0\nmaximum: 9999\ncategory: magic\ntags:\n  - stat\n  - magic\n");
        files.put("skills/eclipse_combat.yml", "type: skill\nkey: eclipse:combat\nname: Combat\nexperience_curve: eclipse:curve_standard\nattribute_bonuses_per_level:\n  eclipse:strength: 1.25\ntags:\n  - progression\n  - combat\n");
        files.put("skills/eclipse_mining.yml", "type: skill\nkey: eclipse:mining\nname: Mining\nexperience_curve: eclipse:curve_standard\nattribute_bonuses_per_level:\n  eclipse:intelligence: 0.5\ntags:\n  - progression\n  - profession\n");
        files.put("collections/eclipse_cobblestone.yml", "type: collection\nkey: eclipse:cobblestone\nname: Cobblestone Collection\ntracked_metric: block_break.cobblestone\nreward_thresholds:\n  - 50\n  - 250\n  - 1000\ntags:\n  - collection\n  - starter\n");
        files.put("assets/eclipse_model_apprentice_staff.yml", "type: asset\nkey: eclipse:model/apprentice_staff\nname: Apprentice Staff Model\nasset_kind: model\nsource: models/items/apprentice_staff.model.json\nlogical_path: assets/eclipse/models/items/apprentice_staff.json\ntags:\n  - model\n  - weapon\n");
        files.put("assets/eclipse_icon_apprentice_staff.yml", "type: asset\nkey: eclipse:icon/apprentice_staff\nname: Apprentice Staff Icon\nasset_kind: icon\nsource: textures/gui/apprentice_staff_icon.txt\nlogical_path: assets/eclipse/textures/gui/apprentice_staff_icon.txt\ntags:\n  - icon\n  - ui\n");
        files.put("assets/eclipse_icon_stormbound_talisman.yml", "type: asset\nkey: eclipse:icon/stormbound_talisman\nname: Stormbound Talisman Icon\nasset_kind: icon\nsource: textures/gui/stormbound_talisman_icon.txt\nlogical_path: assets/eclipse/textures/gui/stormbound_talisman_icon.txt\ntags:\n  - icon\n  - ui\n");
        files.put("assets/eclipse_gui_character_sheet_bg.yml", "type: asset\nkey: eclipse:ui/character_sheet_bg\nname: Character Sheet Background\nasset_kind: gui_background\nsource: textures/gui/character_sheet_bg.txt\nlogical_path: assets/eclipse/textures/gui/character_sheet_bg.txt\ntags:\n  - gui\n  - background\n");
        files.put("assets/eclipse_gui_region_title_overlay.yml", "type: asset\nkey: eclipse:ui/region_title_overlay\nname: Region Title Overlay\nasset_kind: overlay\nsource: textures/gui/region_title_overlay.txt\nlogical_path: assets/eclipse/textures/gui/region_title_overlay.txt\ntags:\n  - gui\n  - overlay\n");
        files.put("assets/eclipse_music_starter_kingdom.yml", "type: asset\nkey: eclipse:music/starter_kingdom\nname: Starter Kingdom Music\nasset_kind: music\nsource: sounds/music/starter_kingdom.ogg.placeholder.txt\nlogical_path: assets/eclipse/sounds/music/starter_kingdom.ogg.placeholder.txt\ntags:\n  - music\n  - region\n");
        files.put("assets/eclipse_particle_arcane_bolt_charge.yml", "type: asset\nkey: eclipse:particle/arcane_bolt_charge\nname: Arcane Bolt Charge Particle\nasset_kind: particle\nsource: particles/arcane_bolt_charge.json\nlogical_path: assets/eclipse/particles/arcane_bolt_charge.json\ntags:\n  - particle\n  - spell\n");
        files.put("assets/eclipse_particle_chain_lightning_arc.yml", "type: asset\nkey: eclipse:particle/chain_lightning_arc\nname: Chain Lightning Arc Particle\nasset_kind: particle\nsource: particles/chain_lightning_arc.json\nlogical_path: assets/eclipse/particles/chain_lightning_arc.json\ntags:\n  - particle\n  - lightning\n");
        files.put("assets/eclipse_sound_arcane_cast.yml", "type: asset\nkey: eclipse:sound/arcane_cast\nname: Arcane Cast Sound\nasset_kind: sound\nsource: sounds/arcane_cast.ogg.placeholder.txt\nlogical_path: assets/eclipse/sounds/arcane_cast.ogg.placeholder.txt\ntags:\n  - sound\n  - spell\n");
        files.put("assets/eclipse_sound_chain_lightning.yml", "type: asset\nkey: eclipse:sound/chain_lightning\nname: Chain Lightning Sound\nasset_kind: sound\nsource: sounds/chain_lightning.ogg.placeholder.txt\nlogical_path: assets/eclipse/sounds/chain_lightning.ogg.placeholder.txt\ntags:\n  - sound\n  - lightning\n");
        files.put("timelines/eclipse_cast_arcane_bolt.yml", "type: timeline\nkey: eclipse:cast_arcane_bolt\nname: Cast Arcane Bolt\nduration_ticks: 16\ntracks:\n  - type: sound\n    tick: 0\n    asset: eclipse:sound/arcane_cast\n    label: cast_open\n  - type: particle\n    tick: 4\n    asset: eclipse:particle/arcane_bolt_charge\n    label: charge_ring\n  - type: callback\n    tick: 8\n    name: impact\n  - type: callback\n    tick: 15\n    name: cleanup\n");
        files.put("timelines/eclipse_cast_chain_lightning.yml", "type: timeline\nkey: eclipse:cast_chain_lightning\nname: Cast Chain Lightning\nduration_ticks: 24\ntracks:\n  - type: sound\n    tick: 0\n    asset: eclipse:sound/chain_lightning\n    label: cast_open\n  - type: particle\n    tick: 6\n    asset: eclipse:particle/chain_lightning_arc\n    label: arc_trace\n  - type: callback\n    tick: 12\n    name: impact\n  - type: callback\n    tick: 23\n    name: cleanup\n");
        files.put("timelines/eclipse_gui_character_sheet_open.yml", "type: timeline\nkey: eclipse:gui_character_sheet_open\nname: Character Sheet Open\nduration_ticks: 10\ntracks:\n  - type: callback\n    tick: 0\n    name: pre_open\n  - type: callback\n    tick: 5\n    name: populate\n  - type: callback\n    tick: 9\n    name: settle\n");
        files.put("timelines/eclipse_gui_character_sheet_close.yml", "type: timeline\nkey: eclipse:gui_character_sheet_close\nname: Character Sheet Close\nduration_ticks: 6\ntracks:\n  - type: callback\n    tick: 0\n    name: fade\n  - type: callback\n    tick: 5\n    name: cleanup\n");
        files.put("gui_screens/eclipse_character_sheet.yml", "type: gui_screen\nkey: eclipse:character_sheet\nname: Character Sheet\nscreen_type: menu\nwidth: 9\nheight: 6\nbackground_asset: eclipse:ui/character_sheet_bg\nopen_timeline: eclipse:gui_character_sheet_open\nclose_timeline: eclipse:gui_character_sheet_close\nbindings:\n  - component_id: level_label\n    binding_key: player.level\n    binding_type: text\n    default: '1'\n    formatter: raw\n  - component_id: strength_label\n    binding_key: stat.eclipse:strength\n    binding_type: text\n    default: '10'\n    formatter: raw\n  - component_id: intelligence_label\n    binding_key: stat.eclipse:intelligence\n    binding_type: text\n    default: '25'\n    formatter: raw\ntags:\n  - ui\n  - character\n");
        files.put("gui_screens/eclipse_region_title_overlay.yml", "type: gui_screen\nkey: eclipse:region_title_overlay\nname: Region Title Overlay\nscreen_type: overlay\nwidth: 9\nheight: 1\nbackground_asset: eclipse:ui/region_title_overlay\nbindings:\n  - component_id: region_name\n    binding_key: region.name\n    binding_type: text\n    default: Starter Kingdom\n    formatter: title\ntags:\n  - ui\n  - overlay\n");
        files.put("regions/eclipse_starter_kingdom.yml", "type: region\nkey: eclipse:starter_kingdom\nname: Starter Kingdom\nworld: world\nmin_x: -200\nmax_x: 200\nmin_y: 0\nmax_y: 255\nmin_z: -200\nmax_z: 200\nmusic_asset: eclipse:music/starter_kingdom\noverlay_screen: eclipse:region_title_overlay\nzones:\n  - id: town_square\n    name: Town Square\n    priority: 10\n    min_x: -40\n    max_x: 40\n    min_y: 60\n    max_y: 120\n    min_z: -40\n    max_z: 40\n    pvp_enabled: false\n    tags:\n      - safe_zone\n  - id: practice_field\n    name: Practice Field\n    priority: 5\n    min_x: 50\n    max_x: 120\n    min_y: 60\n    max_y: 120\n    min_z: 20\n    max_z: 90\n    pvp_enabled: false\n    tags:\n      - combat_intro\ntags:\n  - starter\n  - kingdom\n");
        files.put("warps/eclipse_starter_spawn.yml", "type: warp\nkey: eclipse:starter_spawn\nname: Starter Spawn\nworld: world\nx: 0\ny: 80\nz: 0\nyaw: 0\npitch: 0\ntarget_region: eclipse:starter_kingdom\ntags:\n  - starter\n  - safe\n");
        files.put("dimensions/eclipse_frozen_crypt.yml", "type: dimension\nkey: eclipse:frozen_crypt\nname: Frozen Crypt\ntemplate_name: frozen_crypt_template\nenvironment_type: NORMAL\nmax_instances: 4\nentry_warp: eclipse:starter_spawn\ntags:\n  - dungeon\n  - early_game\n");
        files.put("abilities/eclipse_arcane_bolt.yml", "type: ability\nkey: eclipse:arcane_bolt\nname: Arcane Bolt\ntimeline: eclipse:cast_arcane_bolt\nmana_cost: 15\ncooldown_seconds: 3.5\ntags:\n  - spell\n  - mage\n");
        files.put("abilities/eclipse_chain_lightning.yml", "type: ability\nkey: eclipse:chain_lightning\nname: Chain Lightning\ntimeline: eclipse:cast_chain_lightning\nmana_cost: 40\ncooldown_seconds: 10\ntags:\n  - spell\n  - storm\n  - mastery:staff\n");
        files.put("items/eclipse_apprentice_staff.yml", "type: item\nkey: eclipse:apprentice_staff\nname: Apprentice Staff\nrarity: uncommon\nmodel: eclipse:model/apprentice_staff\nicon: eclipse:icon/apprentice_staff\nabilities:\n  - eclipse:arcane_bolt\n  - eclipse:chain_lightning\ntags:\n  - weapon\n  - staff\n  - starter\n");
        files.put("items/eclipse_stormbound_talisman.yml", "type: item\nkey: eclipse:stormbound_talisman\nname: Stormbound Talisman\nrarity: rare\nicon: eclipse:icon/stormbound_talisman\nabilities:\n  - eclipse:chain_lightning\ntags:\n  - accessory\n  - mage\n");
        files.put("loot_tables/eclipse_arcane_wisp.yml", "type: loot_table\nkey: eclipse:arcane_wisp_loot\nname: Arcane Wisp Loot\ndrops:\n  - key: eclipse:apprentice_staff\n    weight: 5\n    chance: 0.05\n  - key: eclipse:stormbound_talisman\n    weight: 15\n    chance: 0.2\n");
        files.put("mobs/eclipse_arcane_wisp.yml", "type: mob\nkey: eclipse:arcane_wisp\nname: Arcane Wisp\nlevel: 6\nbehavior: eclipse:basic_ranged_caster\nloot_table: eclipse:arcane_wisp_loot\nabilities:\n  - eclipse:arcane_bolt\ntags:\n  - starter_region\n  - magic\n");
        files.put("mobs/eclipse_tempest_acolyte.yml", "type: mob\nkey: eclipse:tempest_acolyte\nname: Tempest Acolyte\nlevel: 12\nbehavior: eclipse:elite_storm_mage\nloot_table: eclipse:arcane_wisp_loot\nabilities:\n  - eclipse:arcane_bolt\n  - eclipse:chain_lightning\ntags:\n  - elite\n  - storm\n");
        files.put("ranks/eclipse_helper.yml", "type: rank\nkey: eclipse:helper\nname: Helper\ncosmetic: false\npermissions:\n  - eclipse.admin\n  - eclipse.editor.view\ntags:\n  - staff\n");
        files.put("ranks/eclipse_wayfarer.yml", "type: rank\nkey: eclipse:wayfarer\nname: Wayfarer\ncosmetic: true\npermissions:\n  - eclipse.chat.cosmetic.wayfarer\ntags:\n  - cosmetic\n");
        return files;
    }

    private static void register(Map<String, ContentSchema> schemas, ContentSchema schema) { schemas.put(schema.registryName(), schema); schemas.put(schema.directoryName(), schema); }
}

final class AttributeSchema extends AbstractSchema {
    @Override public String registryName() { return "attribute"; }
    @Override public String directoryName() { return "attributes"; }
    @Override public EclipseApi.GenericDefinition parse(Map<String, Object> raw, EclipseApi.DefinitionSource source, String requiredNamespace, List<EclipseApi.ContentValidationError> errors) {
        String rawKey = string(raw, "key", true, source, errors); String name = Optional.ofNullable(string(raw, "name", true, source, errors)).orElse(rawKey); if (rawKey == null) return null;
        EclipseApi.NamespacedKey key = key(rawKey, requiredNamespace, source, errors); if (key == null) return null;
        if (raw.get("base_value") == null) errors.add(new EclipseApi.ContentValidationError("missing_field", "Attribute requires 'base_value'", source.path(), rawKey));
        return new EclipseApi.GenericDefinition(key, registryName(), name, tags(raw), copyValues(raw), Map.of(), source);
    }
    @Override public void validateReferences(EclipseApi.GenericDefinition definition, Map<String, Map<String, EclipseApi.GenericDefinition>> stagedByRegistry, List<EclipseApi.ContentValidationError> errors) { }
}

final class SkillSchema extends AbstractSchema {
    @Override public String registryName() { return "skill"; }
    @Override public String directoryName() { return "skills"; }
    @Override public EclipseApi.GenericDefinition parse(Map<String, Object> raw, EclipseApi.DefinitionSource source, String requiredNamespace, List<EclipseApi.ContentValidationError> errors) {
        String rawKey = string(raw, "key", true, source, errors); String name = Optional.ofNullable(string(raw, "name", true, source, errors)).orElse(rawKey); if (rawKey == null) return null;
        EclipseApi.NamespacedKey key = key(rawKey, requiredNamespace, source, errors); if (key == null) return null;
        List<EclipseApi.NamespacedKey> attributes = new ArrayList<>();
        Object bonuses = raw.get("attribute_bonuses_per_level");
        if (bonuses instanceof Map<?, ?> map) {
            for (Object attributeKey : map.keySet()) {
                EclipseApi.NamespacedKey parsed = key(String.valueOf(attributeKey), requiredNamespace, source, errors);
                if (parsed != null) attributes.add(parsed);
            }
        }
        return new EclipseApi.GenericDefinition(key, registryName(), name, tags(raw), copyValues(raw), Map.of("attributes", attributes), source);
    }
    @Override public void validateReferences(EclipseApi.GenericDefinition definition, Map<String, Map<String, EclipseApi.GenericDefinition>> stagedByRegistry, List<EclipseApi.ContentValidationError> errors) { requireReference("attributes", definition, stagedByRegistry, "attribute", errors); }
}

final class CollectionSchema extends AbstractSchema {
    @Override public String registryName() { return "collection"; }
    @Override public String directoryName() { return "collections"; }
    @Override public EclipseApi.GenericDefinition parse(Map<String, Object> raw, EclipseApi.DefinitionSource source, String requiredNamespace, List<EclipseApi.ContentValidationError> errors) {
        String rawKey = string(raw, "key", true, source, errors); String name = Optional.ofNullable(string(raw, "name", true, source, errors)).orElse(rawKey); if (rawKey == null) return null;
        EclipseApi.NamespacedKey key = key(rawKey, requiredNamespace, source, errors); if (key == null) return null;
        return new EclipseApi.GenericDefinition(key, registryName(), name, tags(raw), copyValues(raw), Map.of(), source);
    }
    @Override public void validateReferences(EclipseApi.GenericDefinition definition, Map<String, Map<String, EclipseApi.GenericDefinition>> stagedByRegistry, List<EclipseApi.ContentValidationError> errors) { }
}

final class AssetSchema extends AbstractSchema { @Override public String registryName() { return "asset"; } @Override public String directoryName() { return "assets"; } @Override public EclipseApi.GenericDefinition parse(Map<String, Object> raw, EclipseApi.DefinitionSource source, String requiredNamespace, List<EclipseApi.ContentValidationError> errors) { String rawKey = string(raw, "key", true, source, errors); String name = Optional.ofNullable(string(raw, "name", true, source, errors)).orElse(rawKey); String assetKind = string(raw, "asset_kind", true, source, errors); String assetSource = string(raw, "source", true, source, errors); String logicalPath = string(raw, "logical_path", true, source, errors); if (rawKey == null || assetKind == null || assetSource == null || logicalPath == null) return null; EclipseApi.NamespacedKey key = key(rawKey, requiredNamespace, source, errors); if (key == null) return null; return new EclipseApi.GenericDefinition(key, registryName(), name, tags(raw), copyValues(raw), Map.of(), source); } @Override public void validateReferences(EclipseApi.GenericDefinition definition, Map<String, Map<String, EclipseApi.GenericDefinition>> stagedByRegistry, List<EclipseApi.ContentValidationError> errors) { } }

final class TimelineSchema extends AbstractSchema { @Override public String registryName() { return "timeline"; } @Override public String directoryName() { return "timelines"; } @Override public EclipseApi.GenericDefinition parse(Map<String, Object> raw, EclipseApi.DefinitionSource source, String requiredNamespace, List<EclipseApi.ContentValidationError> errors) { String rawKey = string(raw, "key", true, source, errors); String name = Optional.ofNullable(string(raw, "name", true, source, errors)).orElse(rawKey); Object durationRaw = raw.get("duration_ticks"); Object tracksRaw = raw.get("tracks"); if (rawKey == null || durationRaw == null) { errors.add(new EclipseApi.ContentValidationError("missing_field", "Timeline requires 'duration_ticks'", source.path(), rawKey == null ? "" : rawKey)); return null; } if (!(tracksRaw instanceof List<?> list) || list.isEmpty()) { errors.add(new EclipseApi.ContentValidationError("missing_field", "Timeline requires a non-empty 'tracks' list", source.path(), rawKey)); return null; } EclipseApi.NamespacedKey key = key(rawKey, requiredNamespace, source, errors); if (key == null) return null; List<EclipseApi.NamespacedKey> assetReferences = new ArrayList<>(); for (Object track : list) { if (track instanceof Map<?, ?> map) { Object asset = map.get("asset"); if (asset != null) { EclipseApi.NamespacedKey assetKey = key(String.valueOf(asset), requiredNamespace, source, errors); if (assetKey != null) assetReferences.add(assetKey); } } } return new EclipseApi.GenericDefinition(key, registryName(), name, tags(raw), copyValues(raw), Map.of("assets", assetReferences), source); } @Override public void validateReferences(EclipseApi.GenericDefinition definition, Map<String, Map<String, EclipseApi.GenericDefinition>> stagedByRegistry, List<EclipseApi.ContentValidationError> errors) { requireReference("assets", definition, stagedByRegistry, "asset", errors); } }

final class GuiScreenSchema extends AbstractSchema { @Override public String registryName() { return "gui_screen"; } @Override public String directoryName() { return "gui_screens"; } @Override public EclipseApi.GenericDefinition parse(Map<String, Object> raw, EclipseApi.DefinitionSource source, String requiredNamespace, List<EclipseApi.ContentValidationError> errors) { String rawKey = string(raw, "key", true, source, errors); String name = Optional.ofNullable(string(raw, "name", true, source, errors)).orElse(rawKey); if (rawKey == null) return null; EclipseApi.NamespacedKey key = key(rawKey, requiredNamespace, source, errors); if (key == null) return null; List<EclipseApi.NamespacedKey> assets = new ArrayList<>(); List<EclipseApi.NamespacedKey> timelines = new ArrayList<>(); if (raw.get("background_asset") != null) { EclipseApi.NamespacedKey ref = key(String.valueOf(raw.get("background_asset")), requiredNamespace, source, errors); if (ref != null) assets.add(ref); } if (raw.get("open_timeline") != null) { EclipseApi.NamespacedKey ref = key(String.valueOf(raw.get("open_timeline")), requiredNamespace, source, errors); if (ref != null) timelines.add(ref); } if (raw.get("close_timeline") != null) { EclipseApi.NamespacedKey ref = key(String.valueOf(raw.get("close_timeline")), requiredNamespace, source, errors); if (ref != null) timelines.add(ref); } return new EclipseApi.GenericDefinition(key, registryName(), name, tags(raw), copyValues(raw), Map.of("assets", assets, "timelines", timelines), source); } @Override public void validateReferences(EclipseApi.GenericDefinition definition, Map<String, Map<String, EclipseApi.GenericDefinition>> stagedByRegistry, List<EclipseApi.ContentValidationError> errors) { requireReference("assets", definition, stagedByRegistry, "asset", errors); requireReference("timelines", definition, stagedByRegistry, "timeline", errors); } }

final class RegionSchema extends AbstractSchema { @Override public String registryName() { return "region"; } @Override public String directoryName() { return "regions"; } @Override public EclipseApi.GenericDefinition parse(Map<String, Object> raw, EclipseApi.DefinitionSource source, String requiredNamespace, List<EclipseApi.ContentValidationError> errors) { String rawKey = string(raw, "key", true, source, errors); String name = Optional.ofNullable(string(raw, "name", true, source, errors)).orElse(rawKey); if (rawKey == null) return null; EclipseApi.NamespacedKey key = key(rawKey, requiredNamespace, source, errors); if (key == null) return null; List<EclipseApi.NamespacedKey> assets = new ArrayList<>(); List<EclipseApi.NamespacedKey> screens = new ArrayList<>(); if (raw.get("music_asset") != null) { EclipseApi.NamespacedKey ref = key(String.valueOf(raw.get("music_asset")), requiredNamespace, source, errors); if (ref != null) assets.add(ref); } if (raw.get("overlay_screen") != null) { EclipseApi.NamespacedKey ref = key(String.valueOf(raw.get("overlay_screen")), requiredNamespace, source, errors); if (ref != null) screens.add(ref); } return new EclipseApi.GenericDefinition(key, registryName(), name, tags(raw), copyValues(raw), Map.of("assets", assets, "gui_screens", screens), source); } @Override public void validateReferences(EclipseApi.GenericDefinition definition, Map<String, Map<String, EclipseApi.GenericDefinition>> stagedByRegistry, List<EclipseApi.ContentValidationError> errors) { requireReference("assets", definition, stagedByRegistry, "asset", errors); requireReference("gui_screens", definition, stagedByRegistry, "gui_screen", errors); } }

final class WarpSchema extends AbstractSchema { @Override public String registryName() { return "warp"; } @Override public String directoryName() { return "warps"; } @Override public EclipseApi.GenericDefinition parse(Map<String, Object> raw, EclipseApi.DefinitionSource source, String requiredNamespace, List<EclipseApi.ContentValidationError> errors) { String rawKey = string(raw, "key", true, source, errors); String name = Optional.ofNullable(string(raw, "name", true, source, errors)).orElse(rawKey); if (rawKey == null) return null; EclipseApi.NamespacedKey key = key(rawKey, requiredNamespace, source, errors); if (key == null) return null; List<EclipseApi.NamespacedKey> regions = new ArrayList<>(); if (raw.get("target_region") != null) { EclipseApi.NamespacedKey ref = key(String.valueOf(raw.get("target_region")), requiredNamespace, source, errors); if (ref != null) regions.add(ref); } return new EclipseApi.GenericDefinition(key, registryName(), name, tags(raw), copyValues(raw), Map.of("regions", regions), source); } @Override public void validateReferences(EclipseApi.GenericDefinition definition, Map<String, Map<String, EclipseApi.GenericDefinition>> stagedByRegistry, List<EclipseApi.ContentValidationError> errors) { requireReference("regions", definition, stagedByRegistry, "region", errors); } }

final class DimensionSchema extends AbstractSchema { @Override public String registryName() { return "dimension"; } @Override public String directoryName() { return "dimensions"; } @Override public EclipseApi.GenericDefinition parse(Map<String, Object> raw, EclipseApi.DefinitionSource source, String requiredNamespace, List<EclipseApi.ContentValidationError> errors) { String rawKey = string(raw, "key", true, source, errors); String name = Optional.ofNullable(string(raw, "name", true, source, errors)).orElse(rawKey); if (rawKey == null) return null; EclipseApi.NamespacedKey key = key(rawKey, requiredNamespace, source, errors); if (key == null) return null; List<EclipseApi.NamespacedKey> warps = new ArrayList<>(); if (raw.get("entry_warp") != null) { EclipseApi.NamespacedKey ref = key(String.valueOf(raw.get("entry_warp")), requiredNamespace, source, errors); if (ref != null) warps.add(ref); } return new EclipseApi.GenericDefinition(key, registryName(), name, tags(raw), copyValues(raw), Map.of("warps", warps), source); } @Override public void validateReferences(EclipseApi.GenericDefinition definition, Map<String, Map<String, EclipseApi.GenericDefinition>> stagedByRegistry, List<EclipseApi.ContentValidationError> errors) { requireReference("warps", definition, stagedByRegistry, "warp", errors); } }

final class AbilitySchema extends AbstractSchema { @Override public String registryName() { return "ability"; } @Override public String directoryName() { return "abilities"; } @Override public EclipseApi.GenericDefinition parse(Map<String, Object> raw, EclipseApi.DefinitionSource source, String requiredNamespace, List<EclipseApi.ContentValidationError> errors) { String rawKey = string(raw, "key", true, source, errors); String name = Optional.ofNullable(string(raw, "name", true, source, errors)).orElse(rawKey); String timeline = string(raw, "timeline", true, source, errors); if (rawKey == null || timeline == null) return null; EclipseApi.NamespacedKey key = key(rawKey, requiredNamespace, source, errors); EclipseApi.NamespacedKey timelineKey = key(timeline, requiredNamespace, source, errors); if (key == null || timelineKey == null) return null; Map<String, Object> values = copyValues(raw); values.putIfAbsent("mana_cost", 0); values.putIfAbsent("cooldown_seconds", 0.0D); return new EclipseApi.GenericDefinition(key, registryName(), name, tags(raw), values, Map.of("timeline", List.of(timelineKey)), source); } @Override public void validateReferences(EclipseApi.GenericDefinition definition, Map<String, Map<String, EclipseApi.GenericDefinition>> stagedByRegistry, List<EclipseApi.ContentValidationError> errors) { requireReference("timeline", definition, stagedByRegistry, "timeline", errors); } }

final class ItemSchema extends AbstractSchema { @Override public String registryName() { return "item"; } @Override public String directoryName() { return "items"; } @Override public EclipseApi.GenericDefinition parse(Map<String, Object> raw, EclipseApi.DefinitionSource source, String requiredNamespace, List<EclipseApi.ContentValidationError> errors) { String rawKey = string(raw, "key", true, source, errors); String name = Optional.ofNullable(string(raw, "name", true, source, errors)).orElse(rawKey); if (rawKey == null) return null; EclipseApi.NamespacedKey key = key(rawKey, requiredNamespace, source, errors); if (key == null) return null; List<EclipseApi.NamespacedKey> abilityRefs = stringList(raw, "abilities").stream().map(reference -> key(reference, requiredNamespace, source, errors)).filter(Objects::nonNull).toList(); List<EclipseApi.NamespacedKey> assetRefs = new ArrayList<>(); if (raw.get("model") != null) { EclipseApi.NamespacedKey model = key(String.valueOf(raw.get("model")), requiredNamespace, source, errors); if (model != null) assetRefs.add(model); } if (raw.get("icon") != null) { EclipseApi.NamespacedKey icon = key(String.valueOf(raw.get("icon")), requiredNamespace, source, errors); if (icon != null) assetRefs.add(icon); } return new EclipseApi.GenericDefinition(key, registryName(), name, tags(raw), copyValues(raw), Map.of("abilities", abilityRefs, "assets", assetRefs), source); } @Override public void validateReferences(EclipseApi.GenericDefinition definition, Map<String, Map<String, EclipseApi.GenericDefinition>> stagedByRegistry, List<EclipseApi.ContentValidationError> errors) { requireReference("abilities", definition, stagedByRegistry, "ability", errors); requireReference("assets", definition, stagedByRegistry, "asset", errors); } }

final class LootTableSchema extends AbstractSchema { @Override public String registryName() { return "loot_table"; } @Override public String directoryName() { return "loot_tables"; } @Override public EclipseApi.GenericDefinition parse(Map<String, Object> raw, EclipseApi.DefinitionSource source, String requiredNamespace, List<EclipseApi.ContentValidationError> errors) { String rawKey = string(raw, "key", true, source, errors); String name = Optional.ofNullable(string(raw, "name", true, source, errors)).orElse(rawKey); if (rawKey == null) return null; EclipseApi.NamespacedKey key = key(rawKey, requiredNamespace, source, errors); if (key == null) return null; Object dropsObject = raw.get("drops"); if (!(dropsObject instanceof List<?> drops) || drops.isEmpty()) { errors.add(new EclipseApi.ContentValidationError("missing_field", "Loot tables require a non-empty 'drops' list", source.path(), rawKey)); return null; } List<EclipseApi.NamespacedKey> references = new ArrayList<>(); for (Object drop : drops) { if (!(drop instanceof Map<?, ?> map)) { errors.add(new EclipseApi.ContentValidationError("invalid_drop", "Each loot-table drop must be an object", source.path(), rawKey)); continue; } Object ref = map.get("key"); if (ref == null) { errors.add(new EclipseApi.ContentValidationError("invalid_drop", "Loot-table drop is missing 'key'", source.path(), rawKey)); continue; } EclipseApi.NamespacedKey parsed = key(String.valueOf(ref), requiredNamespace, source, errors); if (parsed != null) references.add(parsed); } return new EclipseApi.GenericDefinition(key, registryName(), name, tags(raw), copyValues(raw), Map.of("drops", references), source); } @Override public void validateReferences(EclipseApi.GenericDefinition definition, Map<String, Map<String, EclipseApi.GenericDefinition>> stagedByRegistry, List<EclipseApi.ContentValidationError> errors) { requireReference("drops", definition, stagedByRegistry, "item", errors); } }

final class MobSchema extends AbstractSchema { @Override public String registryName() { return "mob"; } @Override public String directoryName() { return "mobs"; } @Override public EclipseApi.GenericDefinition parse(Map<String, Object> raw, EclipseApi.DefinitionSource source, String requiredNamespace, List<EclipseApi.ContentValidationError> errors) { String rawKey = string(raw, "key", true, source, errors); String name = Optional.ofNullable(string(raw, "name", true, source, errors)).orElse(rawKey); String lootTable = string(raw, "loot_table", true, source, errors); if (rawKey == null || lootTable == null) return null; EclipseApi.NamespacedKey key = key(rawKey, requiredNamespace, source, errors); EclipseApi.NamespacedKey lootTableKey = key(lootTable, requiredNamespace, source, errors); if (key == null || lootTableKey == null) return null; List<EclipseApi.NamespacedKey> abilityRefs = stringList(raw, "abilities").stream().map(reference -> key(reference, requiredNamespace, source, errors)).filter(Objects::nonNull).toList(); return new EclipseApi.GenericDefinition(key, registryName(), name, tags(raw), copyValues(raw), Map.of("abilities", abilityRefs, "loot_table", List.of(lootTableKey)), source); } @Override public void validateReferences(EclipseApi.GenericDefinition definition, Map<String, Map<String, EclipseApi.GenericDefinition>> stagedByRegistry, List<EclipseApi.ContentValidationError> errors) { requireReference("abilities", definition, stagedByRegistry, "ability", errors); requireReference("loot_table", definition, stagedByRegistry, "loot_table", errors); } }

final class RankSchema extends AbstractSchema { @Override public String registryName() { return "rank"; } @Override public String directoryName() { return "ranks"; } @Override public EclipseApi.GenericDefinition parse(Map<String, Object> raw, EclipseApi.DefinitionSource source, String requiredNamespace, List<EclipseApi.ContentValidationError> errors) { String rawKey = string(raw, "key", true, source, errors); String name = Optional.ofNullable(string(raw, "name", true, source, errors)).orElse(rawKey); if (rawKey == null) return null; EclipseApi.NamespacedKey key = key(rawKey, requiredNamespace, source, errors); if (key == null) return null; Map<String, Object> values = copyValues(raw); values.putIfAbsent("cosmetic", true); values.putIfAbsent("permissions", List.of()); return new EclipseApi.GenericDefinition(key, registryName(), name, tags(raw), values, Map.of(), source); } @Override public void validateReferences(EclipseApi.GenericDefinition definition, Map<String, Map<String, EclipseApi.GenericDefinition>> stagedByRegistry, List<EclipseApi.ContentValidationError> errors) { } }
