package dev.modichamiya.eclipse.gui;

import dev.modichamiya.eclipse.api.EclipseApi;
import dev.modichamiya.eclipse.core.CoreRuntime;

import java.util.Set;

public final class GuiEngineModule implements CoreRuntime.EngineModule {
    @Override
    public String id() {
        return "gui";
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("core", "assets", "animation");
    }

    @Override
    public void onEnable(CoreRuntime.ModuleContext context) {
        context.services().register(EclipseApi.GuiService.class, () -> "GUI framework scaffolded: animated screens and HUD pipeline pending Phase 5 implementation");
        context.logger(id()).info("GUI module scaffolded.");
    }
}
