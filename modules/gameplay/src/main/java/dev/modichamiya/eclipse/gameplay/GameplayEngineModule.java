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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class GameplayEngineModule implements CoreRuntime.EngineModule {
    private PlayerProfileServiceImpl profileService;

    @Override public String id() { return "gameplay"; }
    @Override public Set<String> dependencies() { return Set.of("core", "config", "database", "registry"); }

    @Override
    public void onEnable(CoreRuntime.ModuleContext context) {
        EclipseApi.DatabaseService databaseService = context.services().require(EclipseApi.DatabaseService.class);
        EclipseApi.ConfigService configService = context.services().require(EclipseApi.ConfigService.class);
        EclipseApi.RegistryService registryService = context.services().require(EclipseApi.RegistryService.class);

        this.profileService = new PlayerProfileServiceImpl(databaseService, context);
        StatServiceImpl statService = new StatServiceImpl(profileService, registryService);
        ProgressionServiceImpl progressionService = new ProgressionServiceImpl(profileService, statService);
        ItemServiceImpl itemService = new ItemServiceImpl(registryService);

        context.services().register(EclipseApi.PlayerProfileService.class, profileService);
        context.services().register(EclipseApi.ProgressionService.class, progressionService);
        context.services().register(EclipseApi.StatService.class, statService);
        context.services().register(EclipseApi.ItemService.class, itemService);
        context.services().register(EclipseApi.GameplaySystemService.class, () -> "Phase 1/9/10 profile, progression, and item scaffold online");

        context.plugin().getServer().getPluginManager().registerEvents(new ProfileListener(profileService), context.plugin());
        long autosaveTicks = configService.config("eclipse").longValue("autosave-interval-ticks", 20L * 60L * 5L);
        context.plugin().getServer().getScheduler().runTaskTimerAsynchronously(context.plugin(), () -> profileService.saveAll(), autosaveTicks, autosaveTicks);
        context.logger(id()).info("Gameplay foundation enabled with autosave every " + autosaveTicks + " ticks.");
    }

    @Override
    public void onDisable(CoreRuntime.ModuleContext context) {
        if (profileService != null) profileService.saveAll().orTimeout(10, TimeUnit.SECONDS).join();
    }
}

final class ProfileListener implements Listener {
    private final EclipseApi.PlayerProfileService profileService;
    ProfileListener(EclipseApi.PlayerProfileService profileService) { this.profileService = profileService; }
    @EventHandler public void onJoin(PlayerJoinEvent event) { profileService.loadOrCreate(event.getPlayer().getUniqueId(), event.getPlayer().getName()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { profileService.save(event.getPlayer().getUniqueId()); }
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
            try (Connection connection = databaseService.dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM players WHERE uuid = ?")) {
                statement.setString(1, uniqueId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    CachedProfile loaded;
                    if (resultSet.next()) {
                        loaded = new CachedProfile(uniqueId, resultSet.getString("last_name"), Instant.parse(resultSet.getString("created_at")), Instant.parse(resultSet.getString("last_seen_at")), gson.fromJson(resultSet.getString("progression_json"), EclipseApi.ProgressionScaffold.class), false);
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

    @Override public Optional<EclipseApi.PlayerProfile> getCached(UUID uniqueId) { CachedProfile cached = cache.get(uniqueId); return cached == null ? Optional.empty() : Optional.of(cached.snapshot()); }
    @Override public Collection<EclipseApi.PlayerProfile> onlineProfiles() { return cache.values().stream().map(CachedProfile::snapshot).toList(); }
    @Override public CompletableFuture<Void> save(UUID uniqueId) { CachedProfile cached = cache.get(uniqueId); if (cached == null || !cached.dirty) return CompletableFuture.completedFuture(null); return databaseService.runAsync(() -> saveProfile(cached)).thenRun(() -> cached.dirty = false); }
    @Override public CompletableFuture<Void> saveAll() { CompletableFuture<?>[] saves = cache.keySet().stream().map(this::save).toArray(CompletableFuture[]::new); return CompletableFuture.allOf(saves); }

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
        try (Connection connection = databaseService.dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("INSERT INTO players(uuid, last_name, created_at, last_seen_at, progression_json) VALUES (?, ?, ?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET last_name = excluded.last_name, created_at = excluded.created_at, last_seen_at = excluded.last_seen_at, progression_json = excluded.progression_json")) {
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

final class ProgressionServiceImpl implements EclipseApi.ProgressionService {
    private final PlayerProfileServiceImpl profileService;
    private final StatServiceImpl statService;

    ProgressionServiceImpl(PlayerProfileServiceImpl profileService, StatServiceImpl statService) {
        this.profileService = profileService;
        this.statService = statService;
    }

    @Override
    public EclipseApi.ProgressionSnapshot snapshot(UUID uniqueId) {
        EclipseApi.PlayerProfile profile = profileService.getCached(uniqueId).orElseGet(() -> new EclipseApi.PlayerProfile(uniqueId, "unknown", Instant.EPOCH, Instant.EPOCH, EclipseApi.ProgressionScaffold.empty()));
        return new EclipseApi.ProgressionSnapshot(uniqueId, profile.lastKnownName(), profile.progression().level(), profile.progression().experience(), profile.progression().skills(), profile.progression().collections(), statService.resolve(uniqueId));
    }

    @Override public Collection<EclipseApi.ProgressionSnapshot> onlineSnapshots() { return profileService.onlineProfiles().stream().map(profile -> snapshot(profile.uniqueId())).toList(); }
    @Override public Optional<EclipseApi.SkillProgress> skill(UUID uniqueId, String skillKey) { return Optional.ofNullable(snapshot(uniqueId).skills().get(skillKey)); }
    @Override public Optional<EclipseApi.CollectionProgress> collection(UUID uniqueId, String collectionKey) { return Optional.ofNullable(snapshot(uniqueId).collections().get(collectionKey)); }
}

final class StatServiceImpl implements EclipseApi.StatService {
    private final PlayerProfileServiceImpl profileService;
    private final EclipseApi.RegistryService registryService;

    StatServiceImpl(PlayerProfileServiceImpl profileService, EclipseApi.RegistryService registryService) {
        this.profileService = profileService;
        this.registryService = registryService;
    }

    @Override
    public EclipseApi.ResolvedStatBlock resolve(UUID uniqueId) {
        Map<String, Double> values = new LinkedHashMap<>(definitions());
        List<String> sources = new ArrayList<>();
        profileService.getCached(uniqueId).ifPresent(profile -> {
            sources.add("base_attributes");
            sources.add("profile_level=" + profile.progression().level());
            values.computeIfPresent("eclipse:strength", (key, base) -> base + (profile.progression().level() - 1) * 0.5D);
            values.computeIfPresent("eclipse:intelligence", (key, base) -> base + (profile.progression().level() - 1) * 0.75D);
            for (EclipseApi.SkillProgress skill : profile.progression().skills().values()) {
                registryService.registry("skill", EclipseApi.GenericDefinition.class).get(skill.skillKey()).ifPresent(definition -> {
                    Object raw = definition.values().get("attribute_bonuses_per_level");
                    if (raw instanceof Map<?, ?> map) {
                        map.forEach((attributeKey, scalar) -> {
                            double perLevel = scalar instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(scalar));
                            values.merge(String.valueOf(attributeKey), perLevel * skill.level(), Double::sum);
                        });
                    }
                    sources.add("skill:" + skill.skillKey() + "@" + skill.level());
                });
            }
        });
        return new EclipseApi.ResolvedStatBlock(values, sources);
    }

    @Override
    public Map<String, Double> definitions() {
        Map<String, Double> base = new LinkedHashMap<>();
        for (EclipseApi.GenericDefinition definition : registryService.registry("attribute", EclipseApi.GenericDefinition.class).snapshot()) {
            Object rawBase = definition.values().getOrDefault("base_value", 0.0D);
            double value = rawBase instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(rawBase));
            base.put(definition.key(), value);
        }
        return base;
    }
}

final class ItemServiceImpl implements EclipseApi.ItemService {
    private final EclipseApi.RegistryService registryService;
    private final Map<UUID, EclipseApi.ItemInstance> items = new ConcurrentHashMap<>();
    private final Map<UUID, MutableLoadout> loadouts = new ConcurrentHashMap<>();

    ItemServiceImpl(EclipseApi.RegistryService registryService) {
        this.registryService = registryService;
    }

    @Override
    public EclipseApi.ItemInstance createInstance(String definitionKey, UUID owner, String sourceTag) {
        EclipseApi.GenericDefinition definition = registryService.registry("item", EclipseApi.GenericDefinition.class).get(definitionKey)
                .orElseThrow(() -> new IllegalArgumentException("Unknown item definition: " + definitionKey));
        UUID itemId = UUID.randomUUID();
        String rarity = String.valueOf(definition.values().getOrDefault("rarity", "common"));
        Map<String, Double> rolledStats = new LinkedHashMap<>();
        Object rawStats = definition.values().get("base_stats");
        if (rawStats instanceof Map<?, ?> map) {
            map.forEach((key, value) -> rolledStats.put(String.valueOf(key), value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value))));
        }
        EclipseApi.ItemInstance instance = new EclipseApi.ItemInstance(itemId, definitionKey, owner, sourceTag, rarity, 0, rolledStats, List.of(), List.of(), Optional.empty());
        items.put(itemId, instance);
        loadouts.computeIfAbsent(owner, ignored -> new MutableLoadout(owner));
        return instance;
    }

    @Override public Optional<EclipseApi.ItemInstance> getInstance(UUID itemId) { return Optional.ofNullable(items.get(itemId)); }
    @Override public Collection<EclipseApi.ItemInstance> equipped(UUID owner) { return items.values().stream().filter(item -> item.owner().equals(owner) && item.equippedSlot().isPresent()).toList(); }
    @Override public EclipseApi.EquipmentLoadout loadout(UUID owner) { return loadouts.computeIfAbsent(owner, MutableLoadout::new).snapshot(); }

    @Override
    public boolean equip(UUID owner, EclipseApi.EquipmentSlot slot, UUID itemId) {
        EclipseApi.ItemInstance instance = items.get(itemId);
        if (instance == null || !instance.owner().equals(owner)) return false;
        EclipseApi.GenericDefinition definition = registryService.registry("item", EclipseApi.GenericDefinition.class).get(instance.definitionKey()).orElse(null);
        if (definition == null) return false;
        String primarySlot = String.valueOf(definition.values().getOrDefault("primary_slot", slot.name()));
        if (!slot.name().equalsIgnoreCase(primarySlot) && slot != EclipseApi.EquipmentSlot.ACCESSORY_BAG) return false;
        MutableLoadout loadout = loadouts.computeIfAbsent(owner, MutableLoadout::new);
        loadout.slots.put(slot, itemId);
        if (slot == EclipseApi.EquipmentSlot.ACCESSORY_BAG && !loadout.accessoryBag.contains(itemId)) loadout.accessoryBag.add(itemId);
        items.put(itemId, new EclipseApi.ItemInstance(instance.itemId(), instance.definitionKey(), instance.owner(), instance.sourceTag(), instance.rarity(), instance.upgradeLevel(), instance.rolledStats(), instance.affixes(), instance.sockets(), Optional.of(slot)));
        return true;
    }

    @Override
    public boolean unequip(UUID owner, EclipseApi.EquipmentSlot slot) {
        MutableLoadout loadout = loadouts.get(owner);
        if (loadout == null) return false;
        UUID itemId = loadout.slots.remove(slot);
        if (itemId == null) return false;
        EclipseApi.ItemInstance instance = items.get(itemId);
        if (instance != null) {
            items.put(itemId, new EclipseApi.ItemInstance(instance.itemId(), instance.definitionKey(), instance.owner(), instance.sourceTag(), instance.rarity(), instance.upgradeLevel(), instance.rolledStats(), instance.affixes(), instance.sockets(), Optional.empty()));
        }
        if (slot == EclipseApi.EquipmentSlot.ACCESSORY_BAG) loadout.accessoryBag.remove(itemId);
        return true;
    }
}

final class MutableLoadout {
    final UUID owner;
    final Map<EclipseApi.EquipmentSlot, UUID> slots = new ConcurrentHashMap<>();
    final List<UUID> accessoryBag = new ArrayList<>();
    MutableLoadout(UUID owner) { this.owner = owner; }
    EclipseApi.EquipmentLoadout snapshot() { return new EclipseApi.EquipmentLoadout(owner, slots, accessoryBag); }
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

    EclipseApi.PlayerProfile snapshot() { return new EclipseApi.PlayerProfile(uniqueId, lastKnownName, createdAt, lastSeenAt, progression); }
}
