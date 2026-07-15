package dev.modichamiya.eclipse.animation;

import dev.modichamiya.eclipse.api.EclipseApi;
import dev.modichamiya.eclipse.core.CoreRuntime;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class AnimationEngineModule implements CoreRuntime.EngineModule {
    @Override
    public String id() {
        return "animation";
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("core", "config", "registry", "assets");
    }

    @Override
    public void onEnable(CoreRuntime.ModuleContext context) {
        TimelineServiceImpl service = new TimelineServiceImpl(context, context.services().require(EclipseApi.ConfigService.class), context.services().require(EclipseApi.RegistryService.class));
        context.services().register(EclipseApi.TimelineService.class, service);
        context.eventBus().subscribe(EclipseApi.ContentReloadedEvent.class, event -> service.refreshCatalog());
        service.refreshCatalog();
        context.logger(id()).info("Animation/timeline module enabled.");
    }
}

final class TimelineServiceImpl implements EclipseApi.TimelineService {
    private final CoreRuntime.ModuleContext context;
    private final EclipseApi.ConfigService configService;
    private final EclipseApi.RegistryService registryService;
    private final AtomicLong instanceSequence = new AtomicLong();
    private final Map<Long, RuntimeInstance> activeInstances = new ConcurrentHashMap<>();
    private volatile EclipseApi.TimelineCatalog catalog = EclipseApi.TimelineCatalog.empty();
    private volatile BukkitTask tickerTask;

    TimelineServiceImpl(CoreRuntime.ModuleContext context, EclipseApi.ConfigService configService, EclipseApi.RegistryService registryService) {
        this.context = context;
        this.configService = configService;
        this.registryService = registryService;
    }

    @Override
    public EclipseApi.TimelineCatalog currentCatalog() {
        return catalog;
    }

    @Override
    public EclipseApi.TimelinePlayResult play(String timelineKey, Map<String, String> contextMap) {
        EclipseApi.TimelineDefinition definition = catalog.definitions().get(timelineKey);
        if (definition == null) {
            return new EclipseApi.TimelinePlayResult(false, "Unknown timeline '" + timelineKey + "'", -1L, List.of());
        }
        int maxActive = configService.config("animation").intValue("max-active-instances", 256);
        if (activeInstances.size() >= maxActive) {
            return new EclipseApi.TimelinePlayResult(false, "Maximum active timeline instances reached", -1L, List.of());
        }
        long instanceId = instanceSequence.incrementAndGet();
        RuntimeInstance instance = new RuntimeInstance(instanceId, definition, contextMap);
        activeInstances.put(instanceId, instance);
        ensureTicker();
        return new EclipseApi.TimelinePlayResult(true, "Timeline scheduled", instanceId, List.of());
    }

    @Override
    public boolean stop(long instanceId) {
        return activeInstances.remove(instanceId) != null;
    }

    @Override
    public Collection<EclipseApi.TimelineInstanceSnapshot> activeInstances() {
        return activeInstances.values().stream()
                .sorted(Comparator.comparingLong(RuntimeInstance::instanceId))
                .map(RuntimeInstance::snapshot)
                .toList();
    }

    void refreshCatalog() {
        Map<String, EclipseApi.TimelineDefinition> definitions = new LinkedHashMap<>();
        for (EclipseApi.GenericDefinition definition : registryService.registry("timeline", EclipseApi.GenericDefinition.class).snapshot()) {
            int duration = asInt(definition.values().getOrDefault("duration_ticks", 0));
            Object rawTracks = definition.values().get("tracks");
            List<EclipseApi.TimelineCue> cues = new ArrayList<>();
            if (rawTracks instanceof List<?> tracks) {
                for (Object track : tracks) {
                    if (track instanceof Map<?, ?> map) {
                        Map<String, Object> copied = new LinkedHashMap<>();
                        map.forEach((key, value) -> copied.put(String.valueOf(key), value));
                        int tick = asInt(copied.getOrDefault("tick", 0));
                        String trackType = String.valueOf(copied.getOrDefault("type", "callback"));
                        String label = String.valueOf(copied.getOrDefault("label", copied.getOrDefault("name", trackType)));
                        String assetKey = copied.get("asset") == null ? "" : String.valueOf(copied.get("asset"));
                        cues.add(new EclipseApi.TimelineCue(tick, trackType, label, assetKey, copied));
                    }
                }
            }
            cues.sort(Comparator.comparingInt(EclipseApi.TimelineCue::tick));
            definitions.put(definition.key(), new EclipseApi.TimelineDefinition(definition.namespacedKey(), definition.displayName(), duration, cues, definition.tags()));
        }
        this.catalog = new EclipseApi.TimelineCatalog(Instant.now(), definitions);
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private void ensureTicker() {
        if (tickerTask != null && !tickerTask.isCancelled()) {
            return;
        }
        if (!configService.config("animation").booleanValue("auto-start-ticker", true)) {
            return;
        }
        tickerTask = context.plugin().getServer().getScheduler().runTaskTimer(context.plugin(), this::tickInstances, 1L, 1L);
    }

    private void tickInstances() {
        boolean debugCallbacks = configService.config("animation").booleanValue("debug-log-callbacks", true);
        List<Long> completed = new ArrayList<>();
        for (RuntimeInstance instance : activeInstances.values()) {
            instance.currentTick++;
            for (EclipseApi.TimelineCue cue : instance.definition().cues()) {
                if (cue.tick() == instance.currentTick) {
                    if (cue.trackType().equalsIgnoreCase("callback")) {
                        instance.firedCallbacks.add(cue.label());
                        if (debugCallbacks) {
                            context.logger("animation").info("Timeline callback fired: instance=" + instance.instanceId + ", timeline=" + instance.definition().key().asString() + ", callback=" + cue.label() + ", context=" + instance.context());
                        }
                    }
                }
            }
            if (instance.currentTick >= instance.definition().durationTicks()) {
                completed.add(instance.instanceId);
            }
        }
        completed.forEach(activeInstances::remove);
        if (activeInstances.isEmpty() && tickerTask != null) {
            tickerTask.cancel();
            tickerTask = null;
        }
    }
}

record RuntimeInstance(long instanceId, EclipseApi.TimelineDefinition definition, Map<String, String> context, List<String> firedCallbacks, int currentTick) {
    RuntimeInstance(long instanceId, EclipseApi.TimelineDefinition definition, Map<String, String> context) {
        this(instanceId, definition, new LinkedHashMap<>(context), new ArrayList<>(), -1);
    }

    EclipseApi.TimelineInstanceSnapshot snapshot() {
        return new EclipseApi.TimelineInstanceSnapshot(instanceId, definition.key().asString(), currentTick, definition.durationTicks(), currentTick >= definition.durationTicks() ? "completed" : "running", context, firedCallbacks);
    }
}
