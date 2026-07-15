package dev.modichamiya.eclipse.world;

import dev.modichamiya.eclipse.api.EclipseApi;
import dev.modichamiya.eclipse.core.CoreRuntime;

import java.util.Set;

public final class WorldEngineModule implements CoreRuntime.EngineModule {
    @Override
    public String id() {
        return "world";
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("core", "registry", "assets", "animation", "gui");
    }

    @Override
    public void onEnable(CoreRuntime.ModuleContext context) {
        context.services().register(EclipseApi.WorldService.class, () -> "World service scaffolded: region, zone, and instanced dimension systems pending Phase 6 implementation");
        context.logger(id()).info("World module scaffolded.");
    }
}
