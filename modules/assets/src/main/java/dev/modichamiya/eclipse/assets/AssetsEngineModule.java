package dev.modichamiya.eclipse.assets;

import dev.modichamiya.eclipse.api.EclipseApi;
import dev.modichamiya.eclipse.core.CoreRuntime;

import java.util.Set;

public final class AssetsEngineModule implements CoreRuntime.EngineModule {
    @Override
    public String id() {
        return "assets";
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("core", "registry");
    }

    @Override
    public void onEnable(CoreRuntime.ModuleContext context) {
        context.services().register(EclipseApi.AssetService.class, () -> "Asset pipeline scaffolded: registry-bound pack builder pending Phase 3 implementation");
        context.logger(id()).info("Assets module scaffolded for Phase 3 resource-pack engine.");
    }
}
