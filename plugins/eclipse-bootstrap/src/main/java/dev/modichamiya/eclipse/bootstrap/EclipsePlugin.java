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
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

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
            sender.sendMessage("§e/eclipse reload §7- reload module-backed config and content");
            sender.sendMessage("§e/eclipse module list §7- list module states");
            sender.sendMessage("§e/eclipse profile <player> §7- dump a cached Phase 1 profile");
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

        sender.sendMessage("§cUnknown subcommand.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "module", "profile");
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
        return List.of();
    }
}
