package dev.modichamiya.eclipse.gui;

import dev.modichamiya.eclipse.api.EclipseApi;
import dev.modichamiya.eclipse.core.CoreRuntime;

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

public final class GuiEngineModule implements CoreRuntime.EngineModule {
    @Override
    public String id() {
        return "gui";
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("core", "config", "registry", "assets", "animation");
    }

    @Override
    public void onEnable(CoreRuntime.ModuleContext context) {
        GuiServiceImpl service = new GuiServiceImpl(context, context.services().require(EclipseApi.RegistryService.class), context.services().require(EclipseApi.TimelineService.class));
        service.refreshCatalog();
        context.services().register(EclipseApi.GuiService.class, service);
        context.eventBus().subscribe(EclipseApi.ContentReloadedEvent.class, event -> service.refreshCatalog());
        context.logger(id()).info("GUI framework foundation enabled.");
    }
}

final class GuiServiceImpl implements EclipseApi.GuiService {
    private final CoreRuntime.ModuleContext context;
    private final EclipseApi.RegistryService registryService;
    private final EclipseApi.TimelineService timelineService;
    private final AtomicLong sessionSequence = new AtomicLong();
    private final Map<Long, RuntimeGuiSession> activeSessions = new ConcurrentHashMap<>();
    private volatile EclipseApi.GuiCatalog catalog = EclipseApi.GuiCatalog.empty();

    GuiServiceImpl(CoreRuntime.ModuleContext context, EclipseApi.RegistryService registryService, EclipseApi.TimelineService timelineService) {
        this.context = context;
        this.registryService = registryService;
        this.timelineService = timelineService;
    }

    @Override
    public EclipseApi.GuiCatalog currentCatalog() {
        return catalog;
    }

    @Override
    public EclipseApi.GuiOpenResult openPreview(String screenKey, String viewer, Map<String, String> initialBindings) {
        EclipseApi.GuiDefinition definition = catalog.definitions().get(screenKey);
        if (definition == null) {
            return new EclipseApi.GuiOpenResult(false, "Unknown screen '" + screenKey + "'", -1L);
        }
        long sessionId = sessionSequence.incrementAndGet();
        Map<String, String> bindings = new LinkedHashMap<>();
        for (EclipseApi.GuiBinding binding : definition.bindings()) {
            bindings.put(binding.bindingKey(), initialBindings.getOrDefault(binding.bindingKey(), binding.defaultValue()));
        }
        RuntimeGuiSession session = new RuntimeGuiSession(sessionId, definition.key().asString(), viewer, bindings);
        if (!definition.openTimelineKey().isBlank()) {
            EclipseApi.TimelinePlayResult result = timelineService.play(definition.openTimelineKey(), Map.of("viewer", viewer, "screen", definition.key().asString()));
            if (result.success()) {
                session.timelinesTriggered.add(definition.openTimelineKey());
            }
        }
        activeSessions.put(sessionId, session);
        context.logger("gui").info("Opened GUI preview session=" + sessionId + " screen=" + screenKey + " viewer=" + viewer);
        return new EclipseApi.GuiOpenResult(true, "GUI preview opened", sessionId);
    }

    @Override
    public boolean close(long sessionId) {
        RuntimeGuiSession session = activeSessions.remove(sessionId);
        if (session == null) {
            return false;
        }
        EclipseApi.GuiDefinition definition = catalog.definitions().get(session.screenKey());
        if (definition != null && !definition.closeTimelineKey().isBlank()) {
            EclipseApi.TimelinePlayResult result = timelineService.play(definition.closeTimelineKey(), Map.of("viewer", session.viewer(), "screen", session.screenKey()));
            if (result.success()) {
                session.timelinesTriggered.add(definition.closeTimelineKey());
            }
        }
        return true;
    }

    @Override
    public Collection<EclipseApi.GuiSessionSnapshot> activeSessions() {
        return activeSessions.values().stream()
                .sorted(Comparator.comparingLong(RuntimeGuiSession::sessionId))
                .map(RuntimeGuiSession::snapshot)
                .toList();
    }

    void refreshCatalog() {
        Map<String, EclipseApi.GuiDefinition> definitions = new LinkedHashMap<>();
        for (EclipseApi.GenericDefinition definition : registryService.registry("gui_screen", EclipseApi.GenericDefinition.class).snapshot()) {
            String screenType = String.valueOf(definition.values().getOrDefault("screen_type", "menu"));
            int width = asInt(definition.values().getOrDefault("width", 9));
            int height = asInt(definition.values().getOrDefault("height", 6));
            String background = String.valueOf(definition.values().getOrDefault("background_asset", ""));
            String openTimeline = String.valueOf(definition.values().getOrDefault("open_timeline", ""));
            String closeTimeline = String.valueOf(definition.values().getOrDefault("close_timeline", ""));
            List<EclipseApi.GuiBinding> bindings = new ArrayList<>();
            Object rawBindings = definition.values().get("bindings");
            if (rawBindings instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof Map<?, ?> map) {
                        String componentId = String.valueOf(map.getOrDefault("component_id", "component"));
                        String bindingKey = String.valueOf(map.getOrDefault("binding_key", componentId));
                        String bindingType = String.valueOf(map.getOrDefault("binding_type", "text"));
                        String defaultValue = String.valueOf(map.getOrDefault("default", ""));
                        String formatter = String.valueOf(map.getOrDefault("formatter", "raw"));
                        bindings.add(new EclipseApi.GuiBinding(componentId, bindingKey, bindingType, defaultValue, formatter));
                    }
                }
            }
            definitions.put(definition.key(), new EclipseApi.GuiDefinition(definition.namespacedKey(), definition.displayName(), screenType, width, height, background, openTimeline, closeTimeline, bindings, definition.tags()));
        }
        this.catalog = new EclipseApi.GuiCatalog(Instant.now(), definitions);
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }
}

final class RuntimeGuiSession {
    private final long sessionId;
    private final String screenKey;
    private final String viewer;
    private final Map<String, String> bindings;
    final List<String> timelinesTriggered = new ArrayList<>();

    RuntimeGuiSession(long sessionId, String screenKey, String viewer, Map<String, String> bindings) {
        this.sessionId = sessionId;
        this.screenKey = screenKey;
        this.viewer = viewer;
        this.bindings = new LinkedHashMap<>(bindings);
    }

    long sessionId() {
        return sessionId;
    }

    String screenKey() {
        return screenKey;
    }

    String viewer() {
        return viewer;
    }

    EclipseApi.GuiSessionSnapshot snapshot() {
        return new EclipseApi.GuiSessionSnapshot(sessionId, screenKey, viewer, "open", bindings, timelinesTriggered);
    }
}
