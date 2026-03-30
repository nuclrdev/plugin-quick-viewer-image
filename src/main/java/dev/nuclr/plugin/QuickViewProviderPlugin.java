package dev.nuclr.plugin;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;

public interface QuickViewProviderPlugin extends BasePlugin {

	JComponent getPanel();

	boolean supports(PluginPathResource item);

	default int getPriority() {
		return 0;
	}

	boolean openItem(PluginPathResource item, AtomicBoolean cancelled) throws Exception;

	default void closeItem() throws Exception {
	}
}
