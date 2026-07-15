package dev.modichamiya.eclipse.core;

import dev.modichamiya.eclipse.api.EclipseApi;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class CoreRuntime {
    private CoreRuntime() {
    }

    public interface EngineModule {
        String id();

        default Set<String> dependencies() {
            return Set.of();
        }

        default void onLoad(ModuleContext context) {
        }

        default void onEnable(ModuleContext context) {
        }

        default void onReload(ModuleContext context) {
        }

        default void onDisable(ModuleContext context) {
        }
    }

    public record ModuleState(String id, List<String> dependencies, String status) {
    }

    public static final class ModuleContext {
        private final JavaPlugin plugin;
        private final ServiceRegistry serviceRegistry;
        private final EventBus eventBus;
        private final ExecutorService sharedExecutor;

        ModuleContext(JavaPlugin plugin, ServiceRegistry serviceRegistry, EventBus eventBus, ExecutorService sharedExecutor) {
            this.plugin = plugin;
            this.serviceRegistry = serviceRegistry;
            this.eventBus = eventBus;
            this.sharedExecutor = sharedExecutor;
        }

        public JavaPlugin plugin() {
            return plugin;
        }

        public Logger logger(String moduleId) {
            return Logger.getLogger("Eclipse/" + moduleId);
        }

        public ServiceRegistry services() {
            return serviceRegistry;
        }

        public EventBus eventBus() {
            return eventBus;
        }

        public ExecutorService sharedExecutor() {
            return sharedExecutor;
        }
    }

    public static final class ServiceRegistry {
        private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

        public <T> void register(Class<T> type, T implementation) {
            services.put(type, type.cast(implementation));
        }

        public <T> Optional<T> find(Class<T> type) {
            Object value = services.get(type);
            return value == null ? Optional.empty() : Optional.of(type.cast(value));
        }

        public <T> T require(Class<T> type) {
            return find(type).orElseThrow(() -> new IllegalStateException("Missing required service: " + type.getName()));
        }

        public Collection<Class<?>> registeredTypes() {
            return Collections.unmodifiableSet(services.keySet());
        }
    }

    public static final class EventBus {
        private final Map<Class<?>, CopyOnWriteArrayList<Consumer<Object>>> listeners = new ConcurrentHashMap<>();

        public <T> void subscribe(Class<T> type, Consumer<T> consumer) {
            listeners.computeIfAbsent(type, ignored -> new CopyOnWriteArrayList<>())
                    .add(value -> consumer.accept(type.cast(value)));
        }

        public void publish(Object event) {
            listeners.forEach((type, consumers) -> {
                if (type.isAssignableFrom(event.getClass())) {
                    for (Consumer<Object> consumer : consumers) {
                        consumer.accept(event);
                    }
                }
            });
        }
    }

    public static final class ModuleManager {
        private final ModuleContext context;
        private final Map<String, EngineModule> modulesById = new HashMap<>();
        private final Map<String, String> states = new ConcurrentHashMap<>();
        private List<EngineModule> orderedModules = List.of();

        public ModuleManager(JavaPlugin plugin) {
            ExecutorService sharedExecutor = Executors.newScheduledThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors() / 2));
            this.context = new ModuleContext(plugin, new ServiceRegistry(), new EventBus(), sharedExecutor);
        }

        public ModuleContext context() {
            return context;
        }

        public void registerModules(List<EngineModule> modules) {
            modulesById.clear();
            states.clear();
            for (EngineModule module : modules) {
                EngineModule previous = modulesById.put(module.id(), module);
                if (previous != null) {
                    throw new IllegalStateException("Duplicate module id: " + module.id());
                }
                states.put(module.id(), "registered");
            }
            this.orderedModules = topologicalSort(modulesById);
        }

        public void loadAll() {
            for (EngineModule module : orderedModules) {
                module.onLoad(context);
                states.put(module.id(), "loaded");
            }
        }

        public void enableAll() {
            for (EngineModule module : orderedModules) {
                module.onEnable(context);
                states.put(module.id(), "enabled");
            }
        }

        public void reloadAll() {
            for (EngineModule module : orderedModules) {
                module.onReload(context);
                states.put(module.id(), "reloaded@" + Instant.now());
                context.eventBus().publish(new EclipseApi.ModuleReloadedEvent(module.id(), Instant.now()));
            }
        }

        public void disableAll() {
            List<EngineModule> reverse = new ArrayList<>(orderedModules);
            Collections.reverse(reverse);
            for (EngineModule module : reverse) {
                try {
                    module.onDisable(context);
                } finally {
                    states.put(module.id(), "disabled");
                }
            }
            context.sharedExecutor().shutdown();
        }

        public List<ModuleState> describeModules() {
            return orderedModules.stream()
                    .map(module -> new ModuleState(module.id(), module.dependencies().stream().sorted().toList(), states.getOrDefault(module.id(), "unknown")))
                    .sorted(Comparator.comparing(ModuleState::id))
                    .toList();
        }

        private List<EngineModule> topologicalSort(Map<String, EngineModule> modules) {
            Map<String, Integer> indegree = new HashMap<>();
            Map<String, Set<String>> dependents = new HashMap<>();
            for (EngineModule module : modules.values()) {
                indegree.put(module.id(), 0);
                dependents.put(module.id(), new HashSet<>());
            }
            for (EngineModule module : modules.values()) {
                for (String dependency : module.dependencies()) {
                    if (!modules.containsKey(dependency)) {
                        throw new IllegalStateException("Module '" + module.id() + "' depends on missing module '" + dependency + "'");
                    }
                    indegree.put(module.id(), indegree.get(module.id()) + 1);
                    dependents.get(dependency).add(module.id());
                }
            }

            ArrayDeque<String> queue = new ArrayDeque<>();
            indegree.forEach((id, value) -> {
                if (value == 0) {
                    queue.add(id);
                }
            });

            List<EngineModule> ordered = new ArrayList<>();
            while (!queue.isEmpty()) {
                String id = queue.removeFirst();
                ordered.add(Objects.requireNonNull(modules.get(id)));
                for (String dependent : dependents.getOrDefault(id, Set.of())) {
                    int next = indegree.computeIfPresent(dependent, (ignored, old) -> old - 1);
                    if (next == 0) {
                        queue.add(dependent);
                    }
                }
            }

            if (ordered.size() != modules.size()) {
                throw new IllegalStateException("Module dependency cycle detected");
            }
            return List.copyOf(ordered);
        }
    }
}
