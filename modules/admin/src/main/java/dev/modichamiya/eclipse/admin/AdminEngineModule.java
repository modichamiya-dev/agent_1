package dev.modichamiya.eclipse.admin;

import dev.modichamiya.eclipse.api.EclipseApi;
import dev.modichamiya.eclipse.core.CoreRuntime;

import java.util.Set;

public final class AdminEngineModule implements CoreRuntime.EngineModule {
    @Override
    public String id() {
        return "admin";
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("core", "gui", "registry", "world", "ai", "animation", "assets");
    }

    @Override
    public void onEnable(CoreRuntime.ModuleContext context) {
        context.services().register(EclipseApi.AdminEditorService.class, () -> "Admin editor suite scaffolded: schema-driven editors pending Phase 8 implementation");
        context.logger(id()).info("Admin/editor module scaffolded.");
    }
}
