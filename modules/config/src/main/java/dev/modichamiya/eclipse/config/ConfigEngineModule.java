package dev.modichamiya.eclipse.config;

import dev.modichamiya.eclipse.api.EclipseApi;
import dev.modichamiya.eclipse.core.CoreRuntime;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class ConfigEngineModule implements CoreRuntime.EngineModule {
    @Override
    public String id() {
        return "config";
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("core");
    }

    @Override
    public void onEnable(CoreRuntime.ModuleContext context) {
        Path configDirectory = context.plugin().getDataFolder().toPath().resolve("config");
        DefaultConfigService service = new DefaultConfigService(configDirectory, defaultConfigs());
        service.saveDefaults();
        service.reloadAll();
        context.services().register(EclipseApi.ConfigService.class, service);
        context.logger(id()).info("Loaded " + service.names().size() + " config files.");
    }

    @Override
    public void onReload(CoreRuntime.ModuleContext context) {
        context.services().require(EclipseApi.ConfigService.class).reloadAll();
    }

    private Map<String, Map<String, Object>> defaultConfigs() {
        Map<String, Map<String, Object>> defaults = new LinkedHashMap<>();

        defaults.put("eclipse", Map.of(
                "debug", false,
                "autosave-interval-ticks", 20L * 60L * 5L,
                "profile-ttl-seconds", 300L
        ));

        defaults.put("database", Map.of(
                "file-name", "eclipse.db",
                "pool-size", 8,
                "connection-timeout-ms", 10000L
        ));

        defaults.put("content", Map.of(
                "content-directory", "content",
                "overrides-directory", "content-overrides",
                "required-namespace", "eclipse",
                "hot-reload-enabled", true
        ));

        return defaults;
    }
}

final class DefaultConfigService implements EclipseApi.ConfigService {
    private final Path configDirectory;
    private final Map<String, Map<String, Object>> defaults;
    private final Map<String, ConfigSectionImpl> sections = new ConcurrentHashMap<>();
    private final Yaml yaml;

    DefaultConfigService(Path configDirectory, Map<String, Map<String, Object>> defaults) {
        this.configDirectory = configDirectory;
        this.defaults = defaults;
        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumperOptions.setPrettyFlow(true);
        this.yaml = new Yaml(dumperOptions);
    }

    @Override
    public EclipseApi.ConfigSection config(String name) {
        ConfigSectionImpl section = sections.get(name);
        if (section == null) {
            throw new IllegalArgumentException("Unknown config: " + name);
        }
        return section;
    }

    @Override
    public Set<String> names() {
        return Set.copyOf(defaults.keySet());
    }

    @Override
    public void saveDefaults() {
        try {
            Files.createDirectories(configDirectory);
            for (Map.Entry<String, Map<String, Object>> entry : defaults.entrySet()) {
                Path path = configDirectory.resolve(entry.getKey() + ".yml");
                if (Files.notExists(path)) {
                    writeYaml(path, entry.getValue());
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to save config defaults", exception);
        }
    }

    @Override
    public void reloadAll() {
        try {
            Files.createDirectories(configDirectory);
            for (Map.Entry<String, Map<String, Object>> entry : defaults.entrySet()) {
                Path path = configDirectory.resolve(entry.getKey() + ".yml");
                Map<String, Object> values = Files.exists(path) ? readYaml(path) : new LinkedHashMap<>();
                boolean changed = mergeMissing(values, entry.getValue());
                if (changed || Files.notExists(path)) {
                    writeYaml(path, values);
                }
                sections.put(entry.getKey(), new ConfigSectionImpl(values));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to reload configs", exception);
        }
    }

    private boolean mergeMissing(Map<String, Object> target, Map<String, Object> defaults) {
        boolean changed = false;
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            Object current = target.get(entry.getKey());
            if (current == null) {
                target.put(entry.getKey(), deepCopy(entry.getValue()));
                changed = true;
                continue;
            }
            if (current instanceof Map<?, ?> currentMap && entry.getValue() instanceof Map<?, ?> defaultMap) {
                @SuppressWarnings("unchecked") Map<String, Object> castCurrent = (Map<String, Object>) currentMap;
                @SuppressWarnings("unchecked") Map<String, Object> castDefault = (Map<String, Object>) defaultMap;
                changed |= mergeMissing(castCurrent, castDefault);
            }
        }
        return changed;
    }

    private Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(String.valueOf(key), deepCopy(nested)));
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::deepCopy).collect(Collectors.toCollection(ArrayList::new));
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYaml(Path path) throws IOException {
        try (InputStream inputStream = Files.newInputStream(path)) {
            Object loaded = yaml.load(inputStream);
            if (loaded instanceof Map<?, ?> map) {
                Map<String, Object> values = new LinkedHashMap<>();
                map.forEach((key, value) -> values.put(String.valueOf(key), value));
                return values;
            }
            return new LinkedHashMap<>();
        }
    }

    private void writeYaml(Path path, Map<String, Object> values) throws IOException {
        try (OutputStreamWriter writer = new OutputStreamWriter(Files.newOutputStream(path), StandardCharsets.UTF_8)) {
            yaml.dump(values, writer);
        }
    }
}

final class ConfigSectionImpl implements EclipseApi.ConfigSection {
    private final Map<String, Object> values;

    ConfigSectionImpl(Map<String, Object> values) {
        this.values = new LinkedHashMap<>(values);
    }

    @Override
    public Object raw(String path) {
        String[] parts = path.split("\\.");
        Object current = values;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    @Override
    public String string(String path, String fallback) {
        Object value = raw(path);
        return value == null ? fallback : String.valueOf(value);
    }

    @Override
    public int intValue(String path, int fallback) {
        Object value = raw(path);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    @Override
    public long longValue(String path, long fallback) {
        Object value = raw(path);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    @Override
    public boolean booleanValue(String path, boolean fallback) {
        Object value = raw(path);
        return value instanceof Boolean bool ? bool : fallback;
    }

    @Override
    public double doubleValue(String path, double fallback) {
        Object value = raw(path);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    @Override
    public List<String> stringList(String path) {
        Object value = raw(path);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    @Override
    public Map<String, Object> asMap() {
        return Map.copyOf(values);
    }
}
