package dev.modichamiya.eclipse.registry;

import dev.modichamiya.eclipse.api.EclipseApi;
import dev.modichamiya.eclipse.core.CoreRuntime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class RegistryEngineModule implements CoreRuntime.EngineModule {
    @Override
    public String id() {
        return "registry";
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("core");
    }

    @Override
    public void onEnable(CoreRuntime.ModuleContext context) {
        context.services().register(EclipseApi.RegistryService.class, new RegistryServiceImpl());
        context.logger(id()).info("Registry backbone online.");
    }

    @Override
    public void onReload(CoreRuntime.ModuleContext context) {
        EclipseApi.RegistryService registries = context.services().require(EclipseApi.RegistryService.class);
        registries.freezeAll();
        context.eventBus().publish(new EclipseApi.ContentReloadedEvent(java.time.Instant.now()));
    }
}

final class RegistryServiceImpl implements EclipseApi.RegistryService {
    private final Map<String, EclipseApi.MutableRegistry<?>> registries = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    @Override
    public <T extends EclipseApi.KeyedDefinition> EclipseApi.MutableRegistry<T> registry(String name, Class<T> type) {
        return (EclipseApi.MutableRegistry<T>) registries.computeIfAbsent(name, ignored -> new SimpleMutableRegistry<>());
    }

    @Override
    public Set<String> registryNames() {
        return Set.copyOf(registries.keySet());
    }

    @Override
    public void freezeAll() {
        registries.values().forEach(EclipseApi.MutableRegistry::freeze);
    }

    @Override
    public void unfreezeAll() {
        registries.values().forEach(EclipseApi.MutableRegistry::unfreeze);
    }
}

final class SimpleMutableRegistry<T extends EclipseApi.KeyedDefinition> implements EclipseApi.MutableRegistry<T> {
    private final Map<String, T> mutableEntries = new LinkedHashMap<>();
    private volatile Map<String, T> frozenView = Map.of();
    private volatile boolean frozen;

    @Override
    public synchronized void register(T definition) {
        if (frozen) {
            throw new IllegalStateException("Registry is frozen");
        }
        mutableEntries.put(definition.key(), definition);
    }

    @Override
    public synchronized void unregister(String key) {
        if (frozen) {
            throw new IllegalStateException("Registry is frozen");
        }
        mutableEntries.remove(key);
    }

    @Override
    public synchronized Optional<T> get(String key) {
        return Optional.ofNullable(frozen ? frozenView.get(key) : mutableEntries.get(key));
    }

    @Override
    public synchronized Collection<T> values() {
        return frozen ? List.copyOf(frozenView.values()) : List.copyOf(mutableEntries.values());
    }

    @Override
    public synchronized Collection<T> snapshot() {
        return values();
    }

    @Override
    public synchronized void replaceAll(Collection<T> definitions) {
        if (frozen) {
            throw new IllegalStateException("Registry is frozen");
        }
        mutableEntries.clear();
        for (T definition : definitions) {
            mutableEntries.put(definition.key(), definition);
        }
    }

    @Override
    public boolean isFrozen() {
        return frozen;
    }

    @Override
    public synchronized void freeze() {
        this.frozenView = Map.copyOf(mutableEntries);
        this.frozen = true;
    }

    @Override
    public synchronized void unfreeze() {
        this.frozen = false;
        this.frozenView = Map.of();
    }
}
