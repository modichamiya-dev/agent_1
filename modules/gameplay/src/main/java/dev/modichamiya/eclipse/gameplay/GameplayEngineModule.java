package dev.modichamiya.eclipse.gameplay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.modichamiya.eclipse.api.EclipseApi;
import dev.modichamiya.eclipse.core.CoreRuntime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class GameplayEngineModule implements CoreRuntime.EngineModule {
    private PlayerProfileServiceImpl profileService;

    @Override
    public String id() {
        return "gameplay";
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("core", "config", "database", "registry");
    }

    @Override
    public void onEnable(CoreRuntime.ModuleContext context) {
        EclipseApi.DatabaseService databaseService = context.services().require(EclipseApi.DatabaseService.class);
        EclipseApi.ConfigService configService = context.services().require(EclipseApi.ConfigService.class);
        this.profileService = new PlayerProfileServiceImpl(databaseService, context);
        context.services().register(EclipseApi.PlayerProfileService.class, profileService);
        context.services().register(EclipseApi.GameplaySystemService.class, () -> "Phase 1 profile + progression scaffold online");

        context.plugin().getServer().getPluginManager().registerEvents(new ProfileListener(profileService), context.plugin());

        long autosaveTicks = configService.config("eclipse").longValue("autosave-interval-ticks", 20L * 60L * 5L);
        context.plugin().getServer().getScheduler().runTaskTimerAsynchronously(context.plugin(), () -> profileService.saveAll(), autosaveTicks, autosaveTicks);
        context.logger(id()).info("Gameplay foundation enabled with autosave every " + autosaveTicks + " ticks.");
    }

    @Override
    public void onDisable(CoreRuntime.ModuleContext context) {
        if (profileService != null) {
            profileService.saveAll().orTimeout(10, TimeUnit.SECONDS).join();
        }
    }
}

final class ProfileListener implements Listener {
    private final EclipseApi.PlayerProfileService profileService;

    ProfileListener(EclipseApi.PlayerProfileService profileService) {
        this.profileService = profileService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        profileService.loadOrCreate(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        profileService.save(event.getPlayer().getUniqueId());
    }
}

final class PlayerProfileServiceImpl implements EclipseApi.PlayerProfileService {
    private final EclipseApi.DatabaseService databaseService;
    private final CoreRuntime.ModuleContext context;
    private final Map<UUID, CachedProfile> cache = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder().create();

    PlayerProfileServiceImpl(EclipseApi.DatabaseService databaseService, CoreRuntime.ModuleContext context) {
        this.databaseService = databaseService;
        this.context = context;
    }

    @Override
    public CompletableFuture<EclipseApi.PlayerProfile> loadOrCreate(UUID uniqueId, String lastKnownName) {
        CachedProfile cached = cache.get(uniqueId);
        if (cached != null) {
            cached.lastKnownName = lastKnownName;
            cached.lastSeenAt = Instant.now();
            cached.dirty = true;
            return CompletableFuture.completedFuture(cached.snapshot());
        }

        return databaseService.supplyAsync(() -> {
            try (Connection connection = databaseService.dataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT * FROM players WHERE uuid = ?")) {
                statement.setString(1, uniqueId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    CachedProfile loaded;
                    if (resultSet.next()) {
                        loaded = new CachedProfile(
                                uniqueId,
                                resultSet.getString("last_name"),
                                Instant.parse(resultSet.getString("created_at")),
                                Instant.parse(resultSet.getString("last_seen_at")),
                                gson.fromJson(resultSet.getString("progression_json"), EclipseApi.ProgressionScaffold.class),
                                false
                        );
                    } else {
                        loaded = new CachedProfile(uniqueId, lastKnownName, Instant.now(), Instant.now(), EclipseApi.ProgressionScaffold.empty(), true);
                        insertNewProfile(connection, loaded);
                        loaded.dirty = false;
                    }
                    loaded.lastKnownName = lastKnownName;
                    loaded.lastSeenAt = Instant.now();
                    loaded.dirty = true;
                    cache.put(uniqueId, loaded);
                    EclipseApi.PlayerProfile snapshot = loaded.snapshot();
                    context.eventBus().publish(new EclipseApi.ProfileLoadedEvent(snapshot));
                    return snapshot;
                }
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to load player profile for " + uniqueId, exception);
            }
        });
    }

    @Override
    public Optional<EclipseApi.PlayerProfile> getCached(UUID uniqueId) {
        CachedProfile cached = cache.get(uniqueId);
        return cached == null ? Optional.empty() : Optional.of(cached.snapshot());
    }

    @Override
    public Collection<EclipseApi.PlayerProfile> onlineProfiles() {
        return cache.values().stream().map(CachedProfile::snapshot).toList();
    }

    @Override
    public CompletableFuture<Void> save(UUID uniqueId) {
        CachedProfile cached = cache.get(uniqueId);
        if (cached == null || !cached.dirty) {
            return CompletableFuture.completedFuture(null);
        }
        return databaseService.runAsync(() -> saveProfile(cached)).thenRun(() -> cached.dirty = false);
    }

    @Override
    public CompletableFuture<Void> saveAll() {
        CompletableFuture<?>[] saves = cache.keySet().stream().map(this::save).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(saves);
    }

    Optional<EclipseApi.PlayerProfile> findByName(String name) {
        return cache.values().stream()
                .map(CachedProfile::snapshot)
                .filter(profile -> profile.lastKnownName().equalsIgnoreCase(name))
                .findFirst();
    }

    private void insertNewProfile(Connection connection, CachedProfile profile) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO players(uuid, last_name, created_at, last_seen_at, progression_json) VALUES (?, ?, ?, ?, ?)")) {
            insert.setString(1, profile.uniqueId.toString());
            insert.setString(2, profile.lastKnownName);
            insert.setString(3, profile.createdAt.toString());
            insert.setString(4, profile.lastSeenAt.toString());
            insert.setString(5, gson.toJson(profile.progression));
            insert.executeUpdate();
        }
    }

    private void saveProfile(CachedProfile profile) {
        try (Connection connection = databaseService.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement("INSERT INTO players(uuid, last_name, created_at, last_seen_at, progression_json) VALUES (?, ?, ?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET last_name = excluded.last_name, created_at = excluded.created_at, last_seen_at = excluded.last_seen_at, progression_json = excluded.progression_json")) {
            statement.setString(1, profile.uniqueId.toString());
            statement.setString(2, profile.lastKnownName);
            statement.setString(3, profile.createdAt.toString());
            statement.setString(4, profile.lastSeenAt.toString());
            statement.setString(5, gson.toJson(profile.progression));
            statement.executeUpdate();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to save player profile for " + profile.uniqueId, exception);
        }
    }
}

final class CachedProfile {
    final UUID uniqueId;
    String lastKnownName;
    final Instant createdAt;
    Instant lastSeenAt;
    EclipseApi.ProgressionScaffold progression;
    volatile boolean dirty;

    CachedProfile(UUID uniqueId, String lastKnownName, Instant createdAt, Instant lastSeenAt, EclipseApi.ProgressionScaffold progression, boolean dirty) {
        this.uniqueId = uniqueId;
        this.lastKnownName = lastKnownName;
        this.createdAt = createdAt;
        this.lastSeenAt = lastSeenAt;
        this.progression = progression == null ? EclipseApi.ProgressionScaffold.empty() : progression;
        this.dirty = dirty;
    }

    EclipseApi.PlayerProfile snapshot() {
        return new EclipseApi.PlayerProfile(uniqueId, lastKnownName, createdAt, lastSeenAt, progression);
    }
}
