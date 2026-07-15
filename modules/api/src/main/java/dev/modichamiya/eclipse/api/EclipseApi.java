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
        CompletableFuture<AssetBuildReport> rebuildPack();

        AssetBuildReport currentReport();

        Collection<AssetDescriptor> assetIndex();

        default String status() {
            AssetBuildReport report = currentReport();
            return "assets=" + report.assetCounts() + ", success=" + report.success();
        }
    }

    public interface TimelineService {
        TimelineCatalog currentCatalog();

        TimelinePlayResult play(String timelineKey, Map<String, String> context);

        boolean stop(long instanceId);

        Collection<TimelineInstanceSnapshot> activeInstances();

        default String status() {
            return "timelines=" + currentCatalog().definitions().size() + ", active=" + activeInstances().size();
        }
    }

    public interface GuiService {
        GuiCatalog currentCatalog();

        GuiOpenResult openPreview(String screenKey, String viewer, Map<String, String> initialBindings);

        boolean close(long sessionId);

        Collection<GuiSessionSnapshot> activeSessions();

        default String status() {
            return "screens=" + currentCatalog().definitions().size() + ", sessions=" + activeSessions().size();
        }
    }

    public interface WorldService {
        WorldCatalog currentCatalog();

        Optional<WorldLookupResult> locate(String worldName, double x, double y, double z);

        DimensionRequestResult requestInstance(String dimensionKey, String requester);

        Collection<DimensionInstanceSnapshot> activeInstances();

        default String status() {
            return "regions=" + currentCatalog().regions().size() + ", dimensions=" + currentCatalog().dimensions().size() + ", activeInstances=" + activeInstances().size();
        }
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

    public record AssetDescriptor(
            NamespacedKey key,
            String assetKind,
            String sourcePath,
            String logicalPath,
            Set<String> tags,
            Map<String, Object> metadata
    ) {
        public AssetDescriptor {
            tags = Set.copyOf(tags);
            metadata = Map.copyOf(metadata);
        }
    }

    public record AssetBuildReport(
            boolean success,
            Instant builtAt,
            Map<String, Integer> assetCounts,
            List<String> missingSources,
            List<String> duplicateLogicalPaths,
            String outputDirectory,
            String manifestFile,
            String resourcePackUrl
    ) {
        public AssetBuildReport {
            assetCounts = Map.copyOf(assetCounts);
            missingSources = List.copyOf(missingSources);
            duplicateLogicalPaths = List.copyOf(duplicateLogicalPaths);
        }

        public static AssetBuildReport empty() {
            return new AssetBuildReport(true, Instant.EPOCH, Map.of(), List.of(), List.of(), "", "", "");
        }
    }

    public record TimelineCue(
            int tick,
            String trackType,
            String label,
            String assetKey,
            Map<String, Object> data
    ) {
        public TimelineCue {
            data = Map.copyOf(data);
        }
    }

    public record TimelineDefinition(
            NamespacedKey key,
            String displayName,
            int durationTicks,
            List<TimelineCue> cues,
            Set<String> tags
    ) {
        public TimelineDefinition {
            cues = List.copyOf(cues);
            tags = Set.copyOf(tags);
        }
    }

    public record TimelineCatalog(Instant generatedAt, Map<String, TimelineDefinition> definitions) {
        public TimelineCatalog {
            definitions = Map.copyOf(definitions);
        }

        public static TimelineCatalog empty() {
            return new TimelineCatalog(Instant.EPOCH, Map.of());
        }
    }

    public record TimelinePlayResult(boolean success, String message, long instanceId, List<String> callbacks) {
        public TimelinePlayResult {
            callbacks = List.copyOf(callbacks);
        }
    }

    public record TimelineInstanceSnapshot(
            long instanceId,
            String timelineKey,
            int currentTick,
            int durationTicks,
            String state,
            Map<String, String> context,
            List<String> firedCallbacks
    ) {
        public TimelineInstanceSnapshot {
            context = Map.copyOf(context);
            firedCallbacks = List.copyOf(firedCallbacks);
        }
    }

    public record GuiBinding(
            String componentId,
            String bindingKey,
            String bindingType,
            String defaultValue,
            String formatter
    ) {
    }

    public record GuiDefinition(
            NamespacedKey key,
            String displayName,
            String screenType,
            int width,
            int height,
            String backgroundAssetKey,
            String openTimelineKey,
            String closeTimelineKey,
            List<GuiBinding> bindings,
            Set<String> tags
    ) {
        public GuiDefinition {
            bindings = List.copyOf(bindings);
            tags = Set.copyOf(tags);
        }
    }

    public record GuiCatalog(Instant generatedAt, Map<String, GuiDefinition> definitions) {
        public GuiCatalog {
            definitions = Map.copyOf(definitions);
        }

        public static GuiCatalog empty() {
            return new GuiCatalog(Instant.EPOCH, Map.of());
        }
    }

    public record GuiOpenResult(boolean success, String message, long sessionId) {
    }

    public record GuiSessionSnapshot(
            long sessionId,
            String screenKey,
            String viewer,
            String state,
            Map<String, String> bindings,
            List<String> timelinesTriggered
    ) {
        public GuiSessionSnapshot {
            bindings = Map.copyOf(bindings);
            timelinesTriggered = List.copyOf(timelinesTriggered);
        }
    }

    public record ZoneDefinition(
            String id,
            String displayName,
            int priority,
            double minX,
            double maxX,
            double minY,
            double maxY,
            double minZ,
            double maxZ,
            boolean pvpEnabled,
            String musicAssetKey,
            Set<String> tags
    ) {
        public ZoneDefinition {
            tags = Set.copyOf(tags);
        }

        public boolean contains(double x, double y, double z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    public record RegionDefinition(
            NamespacedKey key,
            String displayName,
            String worldName,
            double minX,
            double maxX,
            double minY,
            double maxY,
            double minZ,
            double maxZ,
            String musicAssetKey,
            String overlayScreenKey,
            List<ZoneDefinition> zones,
            Set<String> tags
    ) {
        public RegionDefinition {
            zones = List.copyOf(zones);
            tags = Set.copyOf(tags);
        }

        public boolean contains(double x, double y, double z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    public record WarpDefinition(
            NamespacedKey key,
            String displayName,
            String worldName,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            String targetRegionKey,
            Set<String> tags
    ) {
        public WarpDefinition {
            tags = Set.copyOf(tags);
        }
    }

    public record DimensionDefinition(
            NamespacedKey key,
            String displayName,
            String templateName,
            String environmentType,
            int maxInstances,
            String entryWarpKey,
            Set<String> tags
    ) {
        public DimensionDefinition {
            tags = Set.copyOf(tags);
        }
    }

    public record WorldCatalog(
            Instant generatedAt,
            Map<String, RegionDefinition> regions,
            Map<String, WarpDefinition> warps,
            Map<String, DimensionDefinition> dimensions
    ) {
        public WorldCatalog {
            regions = Map.copyOf(regions);
            warps = Map.copyOf(warps);
            dimensions = Map.copyOf(dimensions);
        }

        public static WorldCatalog empty() {
            return new WorldCatalog(Instant.EPOCH, Map.of(), Map.of(), Map.of());
        }
    }

    public record WorldLookupResult(String regionKey, List<String> zones, String overlayScreenKey, String musicAssetKey) {
        public WorldLookupResult {
            zones = List.copyOf(zones);
        }
    }

    public record DimensionRequestResult(boolean success, String message, long instanceId) {
    }

    public record DimensionInstanceSnapshot(
            long instanceId,
            String dimensionKey,
            String requester,
            String state,
            Instant createdAt,
            String entryWarpKey
    ) {
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
