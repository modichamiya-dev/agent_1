package dev.modichamiya.eclipse.bootstrap;

import dev.modichamiya.eclipse.admin.AdminEngineModule;
import dev.modichamiya.eclipse.ai.AiEngineModule;
import dev.modichamiya.eclipse.animation.AnimationEngineModule;
import dev.modichamiya.eclipse.api.EclipseApi;
import dev.modichamiya.eclipse.assets.AssetsEngineModule;
import dev.modichamiya.eclipse.config.ConfigEngineModule;
import dev.modichamiya.eclipse.content.ContentEngineModule;
import dev.modichamiya.eclipse.core.CoreEngineModule;
import dev.modichamiya.eclipse.core.CoreRuntime;
import dev.modichamiya.eclipse.database.DatabaseEngineModule;
import dev.modichamiya.eclipse.gameplay.GameplayEngineModule;
import dev.modichamiya.eclipse.gui.GuiEngineModule;
import dev.modichamiya.eclipse.registry.RegistryEngineModule;
import dev.modichamiya.eclipse.world.WorldEngineModule;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class EclipsePlugin extends JavaPlugin {
    private CoreRuntime.ModuleManager moduleManager;

    @Override
    public void onLoad() {
        this.moduleManager = new CoreRuntime.ModuleManager(this);
        moduleManager.registerModules(List.of(
                new CoreEngineModule(),
                new ConfigEngineModule(),
                new DatabaseEngineModule(),
                new RegistryEngineModule(),
                new AssetsEngineModule(),
                new AnimationEngineModule(),
                new GuiEngineModule(),
                new WorldEngineModule(),
                new AiEngineModule(),
                new AdminEngineModule(),
                new ContentEngineModule(),
                new GameplayEngineModule()
        ));
        moduleManager.loadAll();
    }

    @Override
    public void onEnable() {
        moduleManager.enableAll();
        EclipseCommand executor = new EclipseCommand(moduleManager);
        if (getCommand("eclipse") != null) {
            getCommand("eclipse").setExecutor(executor);
            getCommand("eclipse").setTabCompleter(executor);
        }
        getLogger().info("Project Eclipse bootstrap enabled.");
    }

    @Override
    public void onDisable() {
        if (moduleManager != null) {
            moduleManager.disableAll();
        }
    }
}

final class EclipseCommand implements CommandExecutor, TabCompleter {
    private final CoreRuntime.ModuleManager moduleManager;

    EclipseCommand(CoreRuntime.ModuleManager moduleManager) {
        this.moduleManager = moduleManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("eclipse.admin")) {
            sender.sendMessage("§cYou do not have permission to use Project Eclipse admin commands.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§e/eclipse reload §7- reload all registered modules");
            sender.sendMessage("§e/eclipse module list §7- list module states");
            sender.sendMessage("§e/eclipse profile <player> §7- dump a cached Phase 1 profile");
            sender.sendMessage("§e/eclipse content reload|status §7- manage content registries");
            sender.sendMessage("§e/eclipse assets build|status §7- build and inspect the generated pack manifest");
            sender.sendMessage("§e/eclipse timeline status|play <key> §7- inspect or preview timelines");
            sender.sendMessage("§e/eclipse gui status|open <key>|sessions §7- inspect GUI definitions and preview sessions");
            sender.sendMessage("§e/eclipse world status|locate|instance <key> §7- inspect region resolution and dimension reservations");
            sender.sendMessage("§e/eclipse progression status|player <name> §7- inspect progression/stat snapshots");
            sender.sendMessage("§e/eclipse item create <definition>|equip <slot> <uuid>|loadout [player] §7- inspect item scaffolding");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) return handleReload(sender);
        if (args[0].equalsIgnoreCase("module") && args.length > 1 && args[1].equalsIgnoreCase("list")) return handleModuleList(sender);
        if (args[0].equalsIgnoreCase("profile") && args.length > 1) return handleProfile(sender, args[1]);
        if (args[0].equalsIgnoreCase("content")) return handleContentCommands(sender, args);
        if (args[0].equalsIgnoreCase("assets")) return handleAssetCommands(sender, args);
        if (args[0].equalsIgnoreCase("timeline")) return handleTimelineCommands(sender, args);
        if (args[0].equalsIgnoreCase("gui")) return handleGuiCommands(sender, args);
        if (args[0].equalsIgnoreCase("world")) return handleWorldCommands(sender, args);
        if (args[0].equalsIgnoreCase("progression")) return handleProgressionCommands(sender, args);
        if (args[0].equalsIgnoreCase("item")) return handleItemCommands(sender, args);

        sender.sendMessage("§cUnknown subcommand.");
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        moduleManager.reloadAll();
        sender.sendMessage("§aProject Eclipse reloaded all registered modules.");
        return true;
    }

    private boolean handleModuleList(CommandSender sender) {
        sender.sendMessage("§6Project Eclipse modules:");
        for (CoreRuntime.ModuleState state : moduleManager.describeModules()) {
            sender.sendMessage("§7- §f" + state.id() + " §8(" + state.status() + ") §7deps=" + state.dependencies());
        }
        return true;
    }

    private boolean handleProfile(CommandSender sender, String lookup) {
        EclipseApi.PlayerProfileService profileService = moduleManager.context().services().require(EclipseApi.PlayerProfileService.class);
        profileService.onlineProfiles().stream().filter(profile -> profile.lastKnownName().equalsIgnoreCase(lookup)).findFirst().ifPresentOrElse(profile -> {
            sender.sendMessage("§6Profile §f" + profile.lastKnownName());
            sender.sendMessage("§7UUID: §f" + profile.uniqueId());
            sender.sendMessage("§7Created: §f" + profile.createdAt());
            sender.sendMessage("§7Last Seen: §f" + profile.lastSeenAt());
            sender.sendMessage("§7Level/XP: §f" + profile.progression().level() + " / " + profile.progression().experience());
            sender.sendMessage("§7Skills: §f" + profile.progression().skills().keySet());
            sender.sendMessage("§7Collections: §f" + profile.progression().collections().keySet());
        }, () -> sender.sendMessage("§cNo cached profile found for player '" + lookup + "'."));
        return true;
    }

    private boolean handleContentCommands(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("§cUsage: /eclipse content <reload|status>"); return true; }
        if (args[1].equalsIgnoreCase("reload")) {
            EclipseApi.ContentReloadResult result = moduleManager.context().services().require(EclipseApi.ContentService.class).reloadContent().join();
            if (!result.success()) {
                sender.sendMessage("§cContent reload failed with " + result.errors().size() + " error(s).");
                result.errors().stream().limit(5).forEach(error -> sender.sendMessage("§7- §c" + error.code() + " §7" + error.message() + " §8@ " + error.sourcePath()));
                return true;
            }
            sender.sendMessage("§aContent reload succeeded.");
            result.registryCounts().forEach((key, value) -> sender.sendMessage("§7- §f" + key + ": §a" + value));
            return true;
        }
        if (args[1].equalsIgnoreCase("status")) {
            EclipseApi.ContentReloadResult snapshot = moduleManager.context().services().require(EclipseApi.ContentService.class).currentSnapshot();
            sender.sendMessage("§6Content snapshot @ §f" + snapshot.loadedAt());
            snapshot.registryCounts().forEach((key, value) -> sender.sendMessage("§7- §f" + key + ": §a" + value));
            return true;
        }
        sender.sendMessage("§cUsage: /eclipse content <reload|status>");
        return true;
    }

    private boolean handleAssetCommands(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("§cUsage: /eclipse assets <build|status>"); return true; }
        EclipseApi.AssetService assetService = moduleManager.context().services().require(EclipseApi.AssetService.class);
        if (args[1].equalsIgnoreCase("build")) {
            EclipseApi.AssetBuildReport report = assetService.rebuildPack().join();
            sender.sendMessage((report.success() ? "§a" : "§c") + "Asset build finished. success=" + report.success());
            sender.sendMessage("§7Manifest: §f" + report.manifestFile());
            sender.sendMessage("§7Output: §f" + report.outputDirectory());
            sender.sendMessage("§7Counts: §f" + report.assetCounts());
            if (!report.missingSources().isEmpty()) report.missingSources().stream().limit(5).forEach(line -> sender.sendMessage("§cMissing: §7" + line));
            if (!report.duplicateLogicalPaths().isEmpty()) report.duplicateLogicalPaths().stream().limit(5).forEach(line -> sender.sendMessage("§cDuplicate logical path: §7" + line));
            return true;
        }
        if (args[1].equalsIgnoreCase("status")) {
            EclipseApi.AssetBuildReport report = assetService.currentReport();
            sender.sendMessage("§6Asset build snapshot @ §f" + report.builtAt());
            sender.sendMessage("§7Success: §f" + report.success());
            sender.sendMessage("§7Counts: §f" + report.assetCounts());
            sender.sendMessage("§7Manifest: §f" + report.manifestFile());
            return true;
        }
        sender.sendMessage("§cUsage: /eclipse assets <build|status>");
        return true;
    }

    private boolean handleTimelineCommands(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("§cUsage: /eclipse timeline <status|play <key>>"); return true; }
        EclipseApi.TimelineService timelineService = moduleManager.context().services().require(EclipseApi.TimelineService.class);
        if (args[1].equalsIgnoreCase("status")) {
            EclipseApi.TimelineCatalog catalog = timelineService.currentCatalog();
            sender.sendMessage("§6Timeline catalog @ §f" + catalog.generatedAt());
            sender.sendMessage("§7Registered timelines: §f" + catalog.definitions().size());
            sender.sendMessage("§7Active instances: §f" + timelineService.activeInstances().size());
            return true;
        }
        if (args[1].equalsIgnoreCase("play") && args.length > 2) {
            EclipseApi.TimelinePlayResult result = timelineService.play(args[2], Map.of("sender", sender.getName()));
            sender.sendMessage((result.success() ? "§a" : "§c") + result.message() + " §7instance=" + result.instanceId());
            return true;
        }
        sender.sendMessage("§cUsage: /eclipse timeline <status|play <key>>");
        return true;
    }

    private boolean handleGuiCommands(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("§cUsage: /eclipse gui <status|open <key>|sessions>"); return true; }
        EclipseApi.GuiService guiService = moduleManager.context().services().require(EclipseApi.GuiService.class);
        if (args[1].equalsIgnoreCase("status")) {
            EclipseApi.GuiCatalog catalog = guiService.currentCatalog();
            sender.sendMessage("§6GUI catalog @ §f" + catalog.generatedAt());
            sender.sendMessage("§7Registered screens: §f" + catalog.definitions().size());
            sender.sendMessage("§7Active sessions: §f" + guiService.activeSessions().size());
            return true;
        }
        if (args[1].equalsIgnoreCase("open") && args.length > 2) {
            EclipseApi.GuiOpenResult result = guiService.openPreview(args[2], sender.getName(), Map.of());
            sender.sendMessage((result.success() ? "§a" : "§c") + result.message() + " §7session=" + result.sessionId());
            return true;
        }
        if (args[1].equalsIgnoreCase("sessions")) {
            if (guiService.activeSessions().isEmpty()) sender.sendMessage("§7No active GUI sessions.");
            guiService.activeSessions().forEach(session -> sender.sendMessage("§7- §f" + session.sessionId() + " §8screen=§f" + session.screenKey() + " §8viewer=§f" + session.viewer() + " §8timelines=§f" + session.timelinesTriggered()));
            return true;
        }
        sender.sendMessage("§cUsage: /eclipse gui <status|open <key>|sessions>");
        return true;
    }

    private boolean handleWorldCommands(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("§cUsage: /eclipse world <status|locate|instance <key>>"); return true; }
        EclipseApi.WorldService worldService = moduleManager.context().services().require(EclipseApi.WorldService.class);
        if (args[1].equalsIgnoreCase("status")) {
            EclipseApi.WorldCatalog catalog = worldService.currentCatalog();
            sender.sendMessage("§6World catalog @ §f" + catalog.generatedAt());
            sender.sendMessage("§7Regions: §f" + catalog.regions().size() + " §7Warps: §f" + catalog.warps().size() + " §7Dimensions: §f" + catalog.dimensions().size());
            sender.sendMessage("§7Active instances: §f" + worldService.activeInstances().size());
            return true;
        }
        if (args[1].equalsIgnoreCase("locate")) {
            String worldName; double x; double y; double z;
            if (args.length >= 6) { worldName = args[2]; x = Double.parseDouble(args[3]); y = Double.parseDouble(args[4]); z = Double.parseDouble(args[5]); }
            else if (sender instanceof Player player) { worldName = player.getWorld().getName(); x = player.getLocation().getX(); y = player.getLocation().getY(); z = player.getLocation().getZ(); }
            else { sender.sendMessage("§cUsage: /eclipse world locate <world> <x> <y> <z>"); return true; }
            worldService.locate(worldName, x, y, z).ifPresentOrElse(result -> {
                sender.sendMessage("§aRegion: §f" + result.regionKey());
                sender.sendMessage("§7Zones: §f" + result.zones());
                sender.sendMessage("§7Overlay: §f" + result.overlayScreenKey());
                sender.sendMessage("§7Music: §f" + result.musicAssetKey());
            }, () -> sender.sendMessage("§cNo region matched that location."));
            return true;
        }
        if (args[1].equalsIgnoreCase("instance") && args.length > 2) {
            EclipseApi.DimensionRequestResult result = worldService.requestInstance(args[2], sender.getName());
            sender.sendMessage((result.success() ? "§a" : "§c") + result.message() + " §7instance=" + result.instanceId());
            return true;
        }
        sender.sendMessage("§cUsage: /eclipse world <status|locate|instance <key>>");
        return true;
    }

    private boolean handleProgressionCommands(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("§cUsage: /eclipse progression <status|player <name>>"); return true; }
        EclipseApi.ProgressionService progressionService = moduleManager.context().services().require(EclipseApi.ProgressionService.class);
        if (args[1].equalsIgnoreCase("status")) {
            sender.sendMessage("§6Progression service status");
            sender.sendMessage("§7Profiles: §f" + progressionService.onlineSnapshots().size());
            return true;
        }
        if (args[1].equalsIgnoreCase("player") && args.length > 2) {
            moduleManager.context().services().require(EclipseApi.PlayerProfileService.class).onlineProfiles().stream().filter(profile -> profile.lastKnownName().equalsIgnoreCase(args[2])).findFirst().ifPresentOrElse(profile -> {
                EclipseApi.ProgressionSnapshot snapshot = progressionService.snapshot(profile.uniqueId());
                sender.sendMessage("§6Progression for §f" + snapshot.playerName());
                sender.sendMessage("§7Level/XP: §f" + snapshot.level() + " / " + snapshot.experience());
                sender.sendMessage("§7Skills: §f" + snapshot.skills().keySet());
                sender.sendMessage("§7Collections: §f" + snapshot.collections().keySet());
                sender.sendMessage("§7Stats: §f" + snapshot.stats().values());
            }, () -> sender.sendMessage("§cNo cached profile found for player '" + args[2] + "'."));
            return true;
        }
        sender.sendMessage("§cUsage: /eclipse progression <status|player <name>>");
        return true;
    }

    private boolean handleItemCommands(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("§cUsage: /eclipse item <create <definition>|equip <slot> <uuid>|loadout [player]>"); return true; }
        EclipseApi.ItemService itemService = moduleManager.context().services().require(EclipseApi.ItemService.class);
        UUID targetPlayer = resolveTargetPlayerId(sender, args.length > 3 ? args[3] : null).orElse(null);
        if (args[1].equalsIgnoreCase("create") && args.length > 2) {
            if (!(sender instanceof Player player)) { sender.sendMessage("§cOnly players can mint debug items without an explicit player context."); return true; }
            EclipseApi.ItemInstance item = itemService.createInstance(args[2], player.getUniqueId(), "debug_command");
            sender.sendMessage("§aCreated item instance §f" + item.itemId() + " §7from §f" + item.definitionKey());
            return true;
        }
        if (args[1].equalsIgnoreCase("equip") && args.length > 3) {
            if (!(sender instanceof Player player)) { sender.sendMessage("§cOnly players can equip debug items from command context."); return true; }
            try {
                EclipseApi.EquipmentSlot slot = EclipseApi.EquipmentSlot.valueOf(args[2].toUpperCase());
                UUID itemId = UUID.fromString(args[3]);
                boolean result = itemService.equip(player.getUniqueId(), slot, itemId);
                sender.sendMessage((result ? "§a" : "§c") + (result ? "Equipped item." : "Failed to equip item."));
                return true;
            } catch (Exception exception) {
                sender.sendMessage("§cInvalid slot or item UUID.");
                return true;
            }
        }
        if (args[1].equalsIgnoreCase("loadout")) {
            if (targetPlayer == null && sender instanceof Player player) targetPlayer = player.getUniqueId();
            if (targetPlayer == null) { sender.sendMessage("§cProvide a player or run this as a player."); return true; }
            EclipseApi.EquipmentLoadout loadout = itemService.loadout(targetPlayer);
            sender.sendMessage("§6Loadout for §f" + targetPlayer);
            sender.sendMessage("§7Slots: §f" + loadout.slots());
            sender.sendMessage("§7Accessory bag: §f" + loadout.accessoryBag());
            return true;
        }
        sender.sendMessage("§cUsage: /eclipse item <create <definition>|equip <slot> <uuid>|loadout [player]>");
        return true;
    }

    private Optional<UUID> resolveTargetPlayerId(CommandSender sender, String lookup) {
        if (lookup == null || lookup.isBlank()) return Optional.empty();
        return moduleManager.context().services().require(EclipseApi.PlayerProfileService.class).onlineProfiles().stream().filter(profile -> profile.lastKnownName().equalsIgnoreCase(lookup)).map(EclipseApi.PlayerProfile::uniqueId).findFirst();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("reload", "module", "profile", "content", "assets", "timeline", "gui", "world", "progression", "item");
        if (args.length == 2 && args[0].equalsIgnoreCase("module")) return List.of("list");
        if (args.length == 2 && args[0].equalsIgnoreCase("profile")) return onlineNames();
        if (args.length == 2 && args[0].equalsIgnoreCase("content")) return List.of("reload", "status");
        if (args.length == 2 && args[0].equalsIgnoreCase("assets")) return List.of("build", "status");
        if (args.length == 2 && args[0].equalsIgnoreCase("timeline")) return List.of("status", "play");
        if (args.length == 2 && args[0].equalsIgnoreCase("gui")) return List.of("status", "open", "sessions");
        if (args.length == 2 && args[0].equalsIgnoreCase("world")) return List.of("status", "locate", "instance");
        if (args.length == 2 && args[0].equalsIgnoreCase("progression")) return List.of("status", "player");
        if (args.length == 2 && args[0].equalsIgnoreCase("item")) return List.of("create", "equip", "loadout");
        if (args.length == 3 && args[0].equalsIgnoreCase("timeline") && args[1].equalsIgnoreCase("play")) return new ArrayList<>(moduleManager.context().services().require(EclipseApi.TimelineService.class).currentCatalog().definitions().keySet());
        if (args.length == 3 && args[0].equalsIgnoreCase("gui") && args[1].equalsIgnoreCase("open")) return new ArrayList<>(moduleManager.context().services().require(EclipseApi.GuiService.class).currentCatalog().definitions().keySet());
        if (args.length == 3 && args[0].equalsIgnoreCase("world") && args[1].equalsIgnoreCase("instance")) return new ArrayList<>(moduleManager.context().services().require(EclipseApi.WorldService.class).currentCatalog().dimensions().keySet());
        if (args.length == 3 && args[0].equalsIgnoreCase("progression") && args[1].equalsIgnoreCase("player")) return onlineNames();
        if (args.length == 3 && args[0].equalsIgnoreCase("item") && args[1].equalsIgnoreCase("create")) return new ArrayList<>(moduleManager.context().services().require(EclipseApi.RegistryService.class).registry("item", EclipseApi.GenericDefinition.class).snapshot().stream().map(EclipseApi.GenericDefinition::key).toList());
        if (args.length == 3 && args[0].equalsIgnoreCase("item") && args[1].equalsIgnoreCase("equip")) return java.util.Arrays.stream(EclipseApi.EquipmentSlot.values()).map(Enum::name).toList();
        return List.of();
    }

    private List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        moduleManager.context().services().require(EclipseApi.PlayerProfileService.class).onlineProfiles().forEach(profile -> names.add(profile.lastKnownName()));
        return names;
    }
}
