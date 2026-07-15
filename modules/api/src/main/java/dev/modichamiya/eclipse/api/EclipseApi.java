package dev.modichamiya.eclipse.api;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

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
        String key();
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

    public interface ContentService {
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
