package dev.modichamiya.eclipse.content;

import dev.modichamiya.eclipse.api.EclipseApi;
import dev.modichamiya.eclipse.core.CoreRuntime;

import java.time.Instant;
import java.util.Set;

public final class ContentEngineModule implements CoreRuntime.EngineModule {
    @Override
    public String id() {
        return "content";
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("core", "registry", "assets", "animation", "gui", "world", "ai", "admin", "gameplay");
    }

    @Override
    public void onEnable(CoreRuntime.ModuleContext context) {
        context.services().register(EclipseApi.ContentService.class, () -> "Content layer scaffolded: item, mob, boss, quest, dungeon, economy, and social data modules pending phased implementation");
        context.logger(id()).info("Content module scaffolded for phases 9-20.");
    }

    @Override
    public void onReload(CoreRuntime.ModuleContext context) {
        context.eventBus().publish(new EclipseApi.ContentReloadedEvent(Instant.now()));
    }
}
