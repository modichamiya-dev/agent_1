package dev.modichamiya.eclipse.ai;

import dev.modichamiya.eclipse.api.EclipseApi;
import dev.modichamiya.eclipse.core.CoreRuntime;

import java.util.Set;

public final class AiEngineModule implements CoreRuntime.EngineModule {
    @Override
    public String id() {
        return "ai";
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("core", "registry", "animation", "world");
    }

    @Override
    public void onEnable(CoreRuntime.ModuleContext context) {
        context.services().register(EclipseApi.AiService.class, () -> "AI framework scaffolded: behavior trees, threat, and async pathing pending Phase 7 implementation");
        context.logger(id()).info("AI module scaffolded.");
    }
}
