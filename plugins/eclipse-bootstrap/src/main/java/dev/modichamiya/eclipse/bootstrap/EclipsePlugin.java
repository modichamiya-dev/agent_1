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
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            moduleManager.reloadAll();
            sender.sendMessage("§aProject Eclipse reloaded all registered modules.");
            return true;
        }

        if (args[0].equalsIgnoreCase("module") && args.length > 1 && args[1].equalsIgnoreCase("list")) {
            sender.sendMessage("§6Project Eclipse modules:");
            for (CoreRuntime.ModuleState state : moduleManager.describeModules()) {
                sender.sendMessage("§7- §f" + state.id() + " §8(" + state.status() + ") §7deps=" + state.dependencies());
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("profile") && args.length > 1) {
            EclipseApi.PlayerProfileService profileService = moduleManager.context().services().require(EclipseApi.PlayerProfileService.class);
            String lookup = args[1];
            profileService.onlineProfiles().stream()
                    .filter(profile -> profile.lastKnownName().equalsIgnoreCase(lookup))
                    .findFirst()
                    .ifPresentOrElse(profile -> {
                        sender.sendMessage("§6Profile §f" + profile.lastKnownName());
                        sender.sendMessage("§7UUID: §f" + profile.uniqueId());
                        sender.sendMessage("§7Created: §f" + profile.createdAt());
                        sender.sendMessage("§7Last Seen: §f" + profile.lastSeenAt());
                        sender.sendMessage("§7Level/XP: §f" + profile.progression().level() + " / " + profile.progression().experience());
                        sender.sendMessage("§7Skill/Talent Points: §f" + profile.progression().skillPoints() + " / " + profile.progression().talentPoints());
                        sender.sendMessage("§7Achievements: §f" + profile.progression().achievements().size() + "  §7Discoveries: §f" + profile.progression().discoveries().size());
                    }, () -> sender.sendMessage("§cNo cached profile found for player '" + lookup + "'."));
            return true;
        }

        if (args[0].equalsIgnoreCase("content")) {
            return handleContentCommands(sender, args);
        }

        if (args[0].equalsIgnoreCase("assets")) {
            return handleAssetCommands(sender, args);
        }

        if (args[0].equalsIgnoreCase("timeline")) {
            return handleTimelineCommands(sender, args);
        }

        if (args[0].equalsIgnoreCase("gui")) {
            return handleGuiCommands(sender, args);
        }

        if (args[0].equalsIgnoreCase("world")) {
            return handleWorldCommands(sender, args);
        }

        sender.sendMessage("§cUnknown subcommand.");
        return true;
    }

    private boolean handleContentCommands(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /eclipse content <reload|status>");
            return true;
        }
        if (args[1].equalsIgnoreCase("reload")) {
            EclipseApi.ContentService contentService = moduleManager.context().services().require(EclipseApi.ContentService.class);
            EclipseApi.ContentReloadResult result = contentService.reloadContent().join();
            if (!result.success()) {
                sender.sendMessage("§cContent reload failed with " + result.errors().size() + " error(s).");
                result.errors().stream().limit(5).forEach(error -> sender.sendMessage("§7- §c" + error.code() + " §7" + error.message() + " §8@ " + error.sourcePath()));
                return true;
            }
            sender.sendMessage("§aContent reload succeeded.");
            for (Map.Entry<String, Integer> entry : result.registryCounts().entrySet()) {
                sender.sendMessage("§7- §f" + entry.getKey() + ": §a" + entry.getValue());
            }
            return true;
        }
        if (args[1].equalsIgnoreCase("status")) {
            EclipseApi.ContentReloadResult snapshot = moduleManager.context().services().require(EclipseApi.ContentService.class).currentSnapshot();
            sender.sendMessage("§6Content snapshot @ §f" + snapshot.loadedAt());
            for (Map.Entry<String, Integer> entry : snapshot.registryCounts().entrySet()) {
                sender.sendMessage("§7- §f" + entry.getKey() + ": §a" + entry.getValue());
            }
            return true;
        }
        sender.sendMessage("§cUsage: /eclipse content <reload|status>");
        return true;
    }

    private boolean handleAssetCommands(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /eclipse assets <build|status>");
            return true;
        }
        EclipseApi.AssetService assetService = moduleManager.context().services().require(EclipseApi.AssetService.class);
        if (args[1].equalsIgnoreCase("build")) {
            EclipseApi.AssetBuildReport report = assetService.rebuildPack().join();
            sender.sendMessage((report.success() ? "§a" : "§c") + "Asset build finished. success=" + report.success());
            sender.sendMessage("§7Manifest: §f" + report.manifestFile());
            sender.sendMessage("§7Output: §f" + report.outputDirectory());
            sender.sendMessage("§7Counts: §f" + report.assetCounts());
            if (!report.missingSources().isEmpty()) {
                report.missingSources().stream().limit(5).forEach(line -> sender.sendMessage("§cMissing: §7" + line));
            }
            if (!report.duplicateLogicalPaths().isEmpty()) {
                report.duplicateLogicalPaths().stream().limit(5).forEach(line -> sender.sendMessage("§cDuplicate logical path: §7" + line));
            }
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
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /eclipse timeline <status|play <key>>");
            return true;
        }
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
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /eclipse gui <status|open <key>|sessions>");
            return true;
        }
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
            for (EclipseApi.GuiSessionSnapshot session : guiService.activeSessions()) {
                sender.sendMessage("§7- §f" + session.sessionId() + " §8screen=§f" + session.screenKey() + " §8viewer=§f" + session.viewer() + " §8timelines=§f" + session.timelinesTriggered());
            }
            if (guiService.activeSessions().isEmpty()) {
                sender.sendMessage("§7No active GUI sessions.");
            }
            return true;
        }
        sender.sendMessage("§cUsage: /eclipse gui <status|open <key>|sessions>");
        return true;
    }

    private boolean handleWorldCommands(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /eclipse world <status|locate|instance <key>>");
            return true;
        }
        EclipseApi.WorldService worldService = moduleManager.context().services().require(EclipseApi.WorldService.class);
        if (args[1].equalsIgnoreCase("status")) {
            EclipseApi.WorldCatalog catalog = worldService.currentCatalog();
            sender.sendMessage("§6World catalog @ §f" + catalog.generatedAt());
            sender.sendMessage("§7Regions: §f" + catalog.regions().size() + " §7Warps: §f" + catalog.warps().size() + " §7Dimensions: §f" + catalog.dimensions().size());
            sender.sendMessage("§7Active instances: §f" + worldService.activeInstances().size());
            return true;
        }
        if (args[1].equalsIgnoreCase("locate")) {
            String worldName;
            double x;
            double y;
            double z;
            if (args.length >= 6) {
                worldName = args[2];
                x = Double.parseDouble(args[3]);
                y = Double.parseDouble(args[4]);
                z = Double.parseDouble(args[5]);
            } else if (sender instanceof Player player) {
                worldName = player.getWorld().getName();
                x = player.getLocation().getX();
                y = player.getLocation().getY();
                z = player.getLocation().getZ();
            } else {
                sender.sendMessage("§cUsage: /eclipse world locate <world> <x> <y> <z>");
                return true;
            }
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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "module", "profile", "content", "assets", "timeline", "gui", "world");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("module")) {
            return List.of("list");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("profile")) {
            List<String> names = new ArrayList<>();
            EclipseApi.PlayerProfileService profileService = moduleManager.context().services().require(EclipseApi.PlayerProfileService.class);
            profileService.onlineProfiles().forEach(profile -> names.add(profile.lastKnownName()));
            return names;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("content")) {
            return List.of("reload", "status");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("assets")) {
            return List.of("build", "status");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("timeline")) {
            return List.of("status", "play");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("gui")) {
            return List.of("status", "open", "sessions");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("world")) {
            return List.of("status", "locate", "instance");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("timeline") && args[1].equalsIgnoreCase("play")) {
            return new ArrayList<>(moduleManager.context().services().require(EclipseApi.TimelineService.class).currentCatalog().definitions().keySet());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("gui") && args[1].equalsIgnoreCase("open")) {
            return new ArrayList<>(moduleManager.context().services().require(EclipseApi.GuiService.class).currentCatalog().definitions().keySet());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("world") && args[1].equalsIgnoreCase("instance")) {
            return new ArrayList<>(moduleManager.context().services().require(EclipseApi.WorldService.class).currentCatalog().dimensions().keySet());
        }
        return List.of();
    }
}
