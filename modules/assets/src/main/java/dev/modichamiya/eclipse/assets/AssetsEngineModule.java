package dev.modichamiya.eclipse.assets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.modichamiya.eclipse.api.EclipseApi;
import dev.modichamiya.eclipse.core.CoreRuntime;

import java.io.IOException;
import java.io.Writer;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public final class AssetsEngineModule implements CoreRuntime.EngineModule {
    @Override
    public String id() {
        return "assets";
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("core", "config", "registry");
    }

    @Override
    public void onEnable(CoreRuntime.ModuleContext context) {
        AssetServiceImpl service = new AssetServiceImpl(context, context.services().require(EclipseApi.ConfigService.class), context.services().require(EclipseApi.RegistryService.class));
        context.services().register(EclipseApi.AssetService.class, service);
        service.ensureDefaultSourceFiles();
        context.eventBus().subscribe(EclipseApi.ContentReloadedEvent.class, event -> service.rebuildPack());
        context.logger(id()).info("Asset pipeline foundation enabled.");
    }
}

final class AssetServiceImpl implements EclipseApi.AssetService {
    private final CoreRuntime.ModuleContext context;
    private final EclipseApi.ConfigService configService;
    private final EclipseApi.RegistryService registryService;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, String> sampleSourceFiles = Map.ofEntries(
            Map.entry("models/items/apprentice_staff.model.json", "{\n  \"parent\": \"minecraft:item/generated\",\n  \"textures\": {\n    \"layer0\": \"eclipse:item/apprentice_staff\"\n  }\n}\n"),
            Map.entry("textures/gui/apprentice_staff_icon.txt", "placeholder icon asset for apprentice staff\n"),
            Map.entry("textures/gui/stormbound_talisman_icon.txt", "placeholder icon asset for stormbound talisman\n"),
            Map.entry("textures/gui/character_sheet_bg.txt", "placeholder GUI background for the character sheet\n"),
            Map.entry("textures/gui/region_title_overlay.txt", "placeholder overlay art for region title banner\n"),
            Map.entry("particles/arcane_bolt_charge.json", "{\n  \"emitter\": \"point\",\n  \"color\": \"#66ccff\"\n}\n"),
            Map.entry("particles/chain_lightning_arc.json", "{\n  \"emitter\": \"line\",\n  \"color\": \"#99ffff\"\n}\n"),
            Map.entry("sounds/arcane_cast.ogg.placeholder.txt", "placeholder sound source for arcane cast\n"),
            Map.entry("sounds/chain_lightning.ogg.placeholder.txt", "placeholder sound source for chain lightning\n"),
            Map.entry("sounds/music/starter_kingdom.ogg.placeholder.txt", "placeholder looping music for starter kingdom\n")
    );

    private volatile EclipseApi.AssetBuildReport currentReport = EclipseApi.AssetBuildReport.empty();

    AssetServiceImpl(CoreRuntime.ModuleContext context, EclipseApi.ConfigService configService, EclipseApi.RegistryService registryService) {
        this.context = context;
        this.configService = configService;
        this.registryService = registryService;
    }

    @Override
    public CompletableFuture<EclipseApi.AssetBuildReport> rebuildPack() {
        return CompletableFuture.supplyAsync(this::buildSynchronously, context.sharedExecutor())
                .exceptionally(exception -> new EclipseApi.AssetBuildReport(false, Instant.now(), Map.of(), List.of(exception.getMessage()), List.of(), outputDirectory().toString(), manifestFile().toString(), assetsConfig().string("resource-pack-url", "")));
    }

    @Override
    public EclipseApi.AssetBuildReport currentReport() {
        return currentReport;
    }

    @Override
    public Collection<EclipseApi.AssetDescriptor> assetIndex() {
        return collectDescriptors();
    }

    void ensureDefaultSourceFiles() {
        Path sourceRoot = sourceDirectory();
        try {
            Files.createDirectories(sourceRoot);
            for (Map.Entry<String, String> entry : sampleSourceFiles.entrySet()) {
                Path target = sourceRoot.resolve(entry.getKey());
                Files.createDirectories(target.getParent());
                if (Files.notExists(target)) {
                    Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scaffold asset source files", exception);
        }
    }

    private EclipseApi.AssetBuildReport buildSynchronously() {
        Collection<EclipseApi.AssetDescriptor> descriptors = collectDescriptors();
        Map<String, Integer> counts = descriptors.stream().collect(Collectors.toMap(EclipseApi.AssetDescriptor::assetKind, descriptor -> 1, Integer::sum, LinkedHashMap::new));
        List<String> missingSources = new ArrayList<>();
        Set<String> logicalPaths = new LinkedHashSet<>();
        List<String> duplicates = new ArrayList<>();

        for (EclipseApi.AssetDescriptor descriptor : descriptors) {
            Path source = sourceDirectory().resolve(descriptor.sourcePath());
            if (Files.notExists(source)) {
                missingSources.add(descriptor.key().asString() + " -> " + source);
            }
            if (!logicalPaths.add(descriptor.logicalPath())) {
                duplicates.add(descriptor.logicalPath());
            }
        }

        boolean success = missingSources.isEmpty() && duplicates.isEmpty();
        try {
            Files.createDirectories(outputDirectory());
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("generatedAt", Instant.now().toString());
            manifest.put("resourcePackUrl", assetsConfig().string("resource-pack-url", ""));
            manifest.put("assetCounts", counts);
            manifest.put("assets", descriptors.stream().map(descriptor -> Map.of(
                    "key", descriptor.key().asString(),
                    "kind", descriptor.assetKind(),
                    "source", descriptor.sourcePath(),
                    "logicalPath", descriptor.logicalPath(),
                    "tags", descriptor.tags(),
                    "metadata", descriptor.metadata()
            )).toList());
            try (Writer writer = Files.newBufferedWriter(manifestFile(), StandardCharsets.UTF_8)) {
                gson.toJson(manifest, writer);
            }
        } catch (IOException exception) {
            return new EclipseApi.AssetBuildReport(false, Instant.now(), counts, List.of(exception.getMessage()), duplicates, outputDirectory().toString(), manifestFile().toString(), assetsConfig().string("resource-pack-url", ""));
        }

        EclipseApi.AssetBuildReport report = new EclipseApi.AssetBuildReport(success, Instant.now(), counts, missingSources, duplicates, outputDirectory().toString(), manifestFile().toString(), assetsConfig().string("resource-pack-url", ""));
        currentReport = report;
        return report;
    }

    private Collection<EclipseApi.AssetDescriptor> collectDescriptors() {
        return registryService.registry("asset", EclipseApi.GenericDefinition.class).snapshot().stream()
                .map(definition -> new EclipseApi.AssetDescriptor(
                        definition.namespacedKey(),
                        String.valueOf(definition.values().getOrDefault("asset_kind", "unknown")),
                        String.valueOf(definition.values().getOrDefault("source", "")),
                        String.valueOf(definition.values().getOrDefault("logical_path", "")),
                        definition.tags(),
                        definition.values()
                ))
                .sorted(Comparator.comparing(descriptor -> descriptor.key().asString()))
                .toList();
    }

    private EclipseApi.ConfigSection assetsConfig() {
        return configService.config("assets");
    }

    private Path sourceDirectory() {
        return context.plugin().getDataFolder().toPath().resolve(assetsConfig().string("source-directory", "assets-src"));
    }

    private Path outputDirectory() {
        return context.plugin().getDataFolder().toPath().resolve(assetsConfig().string("output-directory", "generated-pack"));
    }

    private Path manifestFile() {
        return outputDirectory().resolve(assetsConfig().string("manifest-name", "eclipse-pack-manifest.json"));
    }
}
