package dev.nuclr.plugin;

public interface BasePlugin {

	default void load(ApplicationPluginContext pluginContext) throws Exception {
	}

	default void unload() throws Exception {
	}

	default Object getPluginInfo() {
		return null;
	}
}
