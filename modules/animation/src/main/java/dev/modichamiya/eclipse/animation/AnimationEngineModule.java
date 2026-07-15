package dev.modichamiya.eclipse.animation;

import dev.modichamiya.eclipse.api.EclipseApi;
import dev.modichamiya.eclipse.core.CoreRuntime;

import java.util.Set;

public final class AnimationEngineModule implements CoreRuntime.EngineModule {
    @Override
    public String id() {
        return "animation";
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("core", "registry", "assets");
    }

    @Override
    public void onEnable(CoreRuntime.ModuleContext context) {
        context.services().register(EclipseApi.TimelineService.class, () -> "Timeline service scaffolded: shared animation engine pending Phase 4 implementation");
        context.logger(id()).info("Animation/timeline module scaffolded.");
    }
}
