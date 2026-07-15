package dev.modichamiya.eclipse.api;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class EclipseApi {
    private EclipseApi() {
    }

    public interface ConfigSection {
        Object raw(String path);

        String string(String path, String fallback);

        int intValue(String path, int fallback);

        long longValue(String path, long fallback);

        boolean booleanValue(String path, boolean fallback);

        double doubleValue(String path, double fallback);

        List<String> stringList(String path);

        Map<String, Object> asMap();
    }

    public interface DatabaseService {
        DataSource dataSource();

        ExecutorService ioExecutor();

        <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier);

        CompletableFuture<Void> runAsync(Runnable runnable);
    }

    public interface ConfigService {
        ConfigSection config(String name);

        Set<String> names();

        void saveDefaults();

        void reloadAll();
    }

    public interface PlayerProfileService {
        CompletableFuture<PlayerProfile> loadOrCreate(UUID uniqueId, String lastKnownName);

        Optional<PlayerProfile> getCached(UUID uniqueId);

        Collection<PlayerProfile> onlineProfiles();

        CompletableFuture<Void> save(UUID uniqueId);

        CompletableFuture<Void> saveAll();
    }

    public interface MutableRegistry<T extends KeyedDefinition> {
        void register(T definition);

        void unregister(String key);

        Optional<T> get(String key);

        Collection<T> values();

        Collection<T> snapshot();

        void replaceAll(Collection<T> definitions);

        boolean isFrozen();

        void freeze();

        void unfreeze();
    }

    public interface RegistryService {
        <T extends KeyedDefinition> MutableRegistry<T> registry(String name, Class<T> type);

        Set<String> registryNames();

        void freezeAll();

        void unfreezeAll();
    }

    public interface KeyedDefinition {
        NamespacedKey namespacedKey();

        default String key() {
            return namespacedKey().asString();
        }
    }

    public interface ContentService {
        CompletableFuture<ContentReloadResult> reloadContent();

        ContentReloadResult currentSnapshot();
    }

    public interface AssetService {
        String status();
    }

    public interface TimelineService {
        String status();
    }

    public interface GuiService {
        String status();
    }

    public interface WorldService {
        String status();
    }

    public interface AiService {
        String status();
    }

    public interface AdminEditorService {
        String status();
    }

    public interface GameplaySystemService {
        String status();
    }

    public interface CombatService {
        String status();
    }

    public interface AbilityService {
        String status();
    }

    public interface EconomyService {
        String status();
    }

    public interface SocialService {
        String status();
    }

    public record NamespacedKey(String namespace, String value) implements Comparable<NamespacedKey> {
        public NamespacedKey {
            if (namespace == null || namespace.isBlank()) {
                throw new IllegalArgumentException("namespace cannot be blank");
            }
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("value cannot be blank");
            }
        }

        public static NamespacedKey parse(String raw) {
            if (raw == null || raw.isBlank() || !raw.contains(":")) {
                throw new IllegalArgumentException("Expected namespaced key in the format namespace:value but got '" + raw + "'");
            }
            String[] split = raw.split(":", 2);
            return new NamespacedKey(split[0].trim().toLowerCase(), split[1].trim().toLowerCase());
        }

        public String asString() {
            return namespace + ":" + value;
        }

        @Override
        public String toString() {
            return asString();
        }

        @Override
        public int compareTo(NamespacedKey other) {
            return asString().compareTo(other.asString());
        }
    }

    public record DefinitionSource(String path, int priority) {
    }

    public record GenericDefinition(
            NamespacedKey namespacedKey,
            String registryName,
            String displayName,
            Set<String> tags,
            Map<String, Object> values,
            Map<String, List<NamespacedKey>> references,
            DefinitionSource source
    ) implements KeyedDefinition {
        public GenericDefinition {
            tags = Set.copyOf(tags);
            values = Map.copyOf(values);
            references = references.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    entry -> List.copyOf(entry.getValue())
            ));
        }
    }

    public record ContentValidationError(String code, String message, String sourcePath, String key) {
    }

    public record ContentReloadResult(
            boolean success,
            Instant loadedAt,
            Map<String, Integer> registryCounts,
            Map<String, List<String>> loadedKeys,
            List<ContentValidationError> errors
    ) {
        public ContentReloadResult {
            registryCounts = Map.copyOf(registryCounts);
            loadedKeys = loadedKeys.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    entry -> List.copyOf(entry.getValue())
            ));
            errors = List.copyOf(errors);
        }

        public static ContentReloadResult empty() {
            return new ContentReloadResult(true, Instant.EPOCH, Map.of(), Map.of(), List.of());
        }
    }

    public record ProgressionScaffold(
            int level,
            long experience,
            int skillPoints,
            int talentPoints,
            String activeTitle,
            Map<String, Integer> achievements,
            Map<String, Integer> masteries,
            int prestige,
            Map<String, Integer> reputation,
            Set<String> discoveries
    ) {
        public ProgressionScaffold {
            achievements = Map.copyOf(achievements);
            masteries = Map.copyOf(masteries);
            reputation = Map.copyOf(reputation);
            discoveries = Set.copyOf(discoveries);
        }

        public static ProgressionScaffold empty() {
            return new ProgressionScaffold(1, 0L, 0, 0, null, Map.of(), Map.of(), 0, Map.of(), Set.of());
        }
    }

    public record PlayerProfile(
            UUID uniqueId,
            String lastKnownName,
            Instant createdAt,
            Instant lastSeenAt,
            ProgressionScaffold progression
    ) {
    }

    public record ProfileLoadedEvent(PlayerProfile profile) {
    }

    public record ContentReloadedEvent(Instant occurredAt) {
    }

    public record ModuleReloadedEvent(String moduleId, Instant occurredAt) {
    }
}
