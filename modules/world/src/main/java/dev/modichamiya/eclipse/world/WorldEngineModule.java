package dev.modichamiya.eclipse.world;

import dev.modichamiya.eclipse.api.EclipseApi;
import dev.modichamiya.eclipse.core.CoreRuntime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class WorldEngineModule implements CoreRuntime.EngineModule {
    @Override
    public String id() {
        return "world";
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("core", "config", "registry", "assets", "animation", "gui");
    }

    @Override
    public void onEnable(CoreRuntime.ModuleContext context) {
        WorldServiceImpl service = new WorldServiceImpl(context, context.services().require(EclipseApi.RegistryService.class));
        service.refreshCatalog();
        context.services().register(EclipseApi.WorldService.class, service);
        context.eventBus().subscribe(EclipseApi.ContentReloadedEvent.class, event -> service.refreshCatalog());
        context.logger(id()).info("World engine foundation enabled.");
    }
}

final class WorldServiceImpl implements EclipseApi.WorldService {
    private final CoreRuntime.ModuleContext context;
    private final EclipseApi.RegistryService registryService;
    private final AtomicLong instanceSequence = new AtomicLong();
    private final Map<Long, EclipseApi.DimensionInstanceSnapshot> activeInstances = new ConcurrentHashMap<>();
    private volatile EclipseApi.WorldCatalog catalog = EclipseApi.WorldCatalog.empty();

    WorldServiceImpl(CoreRuntime.ModuleContext context, EclipseApi.RegistryService registryService) {
        this.context = context;
        this.registryService = registryService;
    }

    @Override
    public EclipseApi.WorldCatalog currentCatalog() {
        return catalog;
    }

    @Override
    public Optional<EclipseApi.WorldLookupResult> locate(String worldName, double x, double y, double z) {
        for (EclipseApi.RegionDefinition region : catalog.regions().values()) {
            if (!region.worldName().equalsIgnoreCase(worldName)) {
                continue;
            }
            if (region.contains(x, y, z)) {
                List<String> zones = region.zones().stream()
                        .filter(zone -> zone.contains(x, y, z))
                        .sorted(Comparator.comparingInt(EclipseApi.ZoneDefinition::priority).reversed())
                        .map(EclipseApi.ZoneDefinition::id)
                        .toList();
                String music = region.musicAssetKey();
                if (!zones.isEmpty()) {
                    Optional<EclipseApi.ZoneDefinition> first = region.zones().stream().filter(zone -> zone.id().equals(zones.get(0))).findFirst();
                    if (first.isPresent() && !first.get().musicAssetKey().isBlank()) {
                        music = first.get().musicAssetKey();
                    }
                }
                return Optional.of(new EclipseApi.WorldLookupResult(region.key().asString(), zones, region.overlayScreenKey(), music));
            }
        }
        return Optional.empty();
    }

    @Override
    public EclipseApi.DimensionRequestResult requestInstance(String dimensionKey, String requester) {
        EclipseApi.DimensionDefinition definition = catalog.dimensions().get(dimensionKey);
        if (definition == null) {
            return new EclipseApi.DimensionRequestResult(false, "Unknown dimension '" + dimensionKey + "'", -1L);
        }
        long currentCount = activeInstances.values().stream().filter(instance -> instance.dimensionKey().equals(dimensionKey)).count();
        if (currentCount >= definition.maxInstances()) {
            return new EclipseApi.DimensionRequestResult(false, "Max instances reached for '" + dimensionKey + "'", -1L);
        }
        long instanceId = instanceSequence.incrementAndGet();
        EclipseApi.DimensionInstanceSnapshot snapshot = new EclipseApi.DimensionInstanceSnapshot(instanceId, dimensionKey, requester, "provisioned", Instant.now(), definition.entryWarpKey());
        activeInstances.put(instanceId, snapshot);
        context.logger("world").info("Provisioned dimension instance=" + instanceId + " key=" + dimensionKey + " requester=" + requester);
        return new EclipseApi.DimensionRequestResult(true, "Instance reserved", instanceId);
    }

    @Override
    public Collection<EclipseApi.DimensionInstanceSnapshot> activeInstances() {
        return activeInstances.values().stream().sorted(Comparator.comparingLong(EclipseApi.DimensionInstanceSnapshot::instanceId)).toList();
    }

    void refreshCatalog() {
        Map<String, EclipseApi.RegionDefinition> regions = new LinkedHashMap<>();
        for (EclipseApi.GenericDefinition definition : registryService.registry("region", EclipseApi.GenericDefinition.class).snapshot()) {
            List<EclipseApi.ZoneDefinition> zones = new ArrayList<>();
            Object rawZones = definition.values().get("zones");
            if (rawZones instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof Map<?, ?> map) {
                        String id = String.valueOf(map.getOrDefault("id", "zone"));
                        String display = String.valueOf(map.getOrDefault("name", id));
                        int priority = asInt(map.getOrDefault("priority", 0));
                        double minX = asDouble(map.getOrDefault("min_x", definition.values().getOrDefault("min_x", 0)));
                        double maxX = asDouble(map.getOrDefault("max_x", definition.values().getOrDefault("max_x", 0)));
                        double minY = asDouble(map.getOrDefault("min_y", definition.values().getOrDefault("min_y", 0)));
                        double maxY = asDouble(map.getOrDefault("max_y", definition.values().getOrDefault("max_y", 0)));
                        double minZ = asDouble(map.getOrDefault("min_z", definition.values().getOrDefault("min_z", 0)));
                        double maxZ = asDouble(map.getOrDefault("max_z", definition.values().getOrDefault("max_z", 0)));
                        boolean pvpEnabled = asBoolean(map.getOrDefault("pvp_enabled", false));
                        String musicAsset = String.valueOf(map.getOrDefault("music_asset", ""));
                        Set<String> tags = new LinkedHashSet<>();
                        Object rawTags = map.get("tags");
                        if (rawTags instanceof List<?> tagList) {
                            tagList.forEach(tag -> tags.add(String.valueOf(tag)));
                        }
                        zones.add(new EclipseApi.ZoneDefinition(id, display, priority, minX, maxX, minY, maxY, minZ, maxZ, pvpEnabled, musicAsset, tags));
                    }
                }
            }
            regions.put(definition.key(), new EclipseApi.RegionDefinition(
                    definition.namespacedKey(),
                    definition.displayName(),
                    String.valueOf(definition.values().getOrDefault("world", "world")),
                    asDouble(definition.values().getOrDefault("min_x", 0)),
                    asDouble(definition.values().getOrDefault("max_x", 0)),
                    asDouble(definition.values().getOrDefault("min_y", 0)),
                    asDouble(definition.values().getOrDefault("max_y", 0)),
                    asDouble(definition.values().getOrDefault("min_z", 0)),
                    asDouble(definition.values().getOrDefault("max_z", 0)),
                    String.valueOf(definition.values().getOrDefault("music_asset", "")),
                    String.valueOf(definition.values().getOrDefault("overlay_screen", "")),
                    zones,
                    definition.tags()
            ));
        }

        Map<String, EclipseApi.WarpDefinition> warps = new LinkedHashMap<>();
        for (EclipseApi.GenericDefinition definition : registryService.registry("warp", EclipseApi.GenericDefinition.class).snapshot()) {
            warps.put(definition.key(), new EclipseApi.WarpDefinition(
                    definition.namespacedKey(),
                    definition.displayName(),
                    String.valueOf(definition.values().getOrDefault("world", "world")),
                    asDouble(definition.values().getOrDefault("x", 0)),
                    asDouble(definition.values().getOrDefault("y", 0)),
                    asDouble(definition.values().getOrDefault("z", 0)),
                    (float) asDouble(definition.values().getOrDefault("yaw", 0)),
                    (float) asDouble(definition.values().getOrDefault("pitch", 0)),
                    String.valueOf(definition.values().getOrDefault("target_region", "")),
                    definition.tags()
            ));
        }

        Map<String, EclipseApi.DimensionDefinition> dimensions = new LinkedHashMap<>();
        for (EclipseApi.GenericDefinition definition : registryService.registry("dimension", EclipseApi.GenericDefinition.class).snapshot()) {
            dimensions.put(definition.key(), new EclipseApi.DimensionDefinition(
                    definition.namespacedKey(),
                    definition.displayName(),
                    String.valueOf(definition.values().getOrDefault("template_name", "")),
                    String.valueOf(definition.values().getOrDefault("environment_type", "NORMAL")),
                    asInt(definition.values().getOrDefault("max_instances", 1)),
                    String.valueOf(definition.values().getOrDefault("entry_warp", "")),
                    definition.tags()
            ));
        }

        this.catalog = new EclipseApi.WorldCatalog(Instant.now(), regions, warps, dimensions);
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
