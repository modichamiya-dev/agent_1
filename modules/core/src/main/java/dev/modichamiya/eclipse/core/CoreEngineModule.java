package dev.modichamiya.eclipse.core;

public final class CoreEngineModule implements CoreRuntime.EngineModule {
    @Override
    public String id() {
        return "core";
    }

    @Override
    public void onLoad(CoreRuntime.ModuleContext context) {
        context.logger(id()).info("Core runtime prepared.");
    }
}
