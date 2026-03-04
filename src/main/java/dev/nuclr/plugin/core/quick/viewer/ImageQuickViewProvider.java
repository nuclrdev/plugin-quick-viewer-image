package dev.nuclr.plugin.core.quick.viewer;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;

import dev.nuclr.plugin.PluginTheme;
import dev.nuclr.plugin.QuickViewItem;
import dev.nuclr.plugin.QuickViewProvider;

public class ImageQuickViewProvider implements QuickViewProvider {

	private ImageViewPanel panel;
	private volatile AtomicBoolean currentCancelled;
	private PluginTheme theme;

	@Override
	public String getPluginClass() {
		return getClass().getName();
	}

	@Override
	public boolean matches(QuickViewItem item) {
		return ImageViewPanel.IMAGE_EXTENSIONS.contains(item.extension().toLowerCase());
	}

	@Override
	public JComponent getPanel() {
		if (this.panel == null) {
			this.panel = new ImageViewPanel();
			this.panel.applyTheme(theme);
		}
		return panel;
	}

	@Override
	public void applyTheme(PluginTheme theme) {
		this.theme = theme;
		if (panel != null) {
			panel.applyTheme(theme);
		}
	}

	@Override
	public boolean open(QuickViewItem item, AtomicBoolean cancelled) {
		if (currentCancelled != null) currentCancelled.set(true);
		this.currentCancelled = cancelled;
		getPanel(); // ensure panel exists
		return this.panel.load(item, cancelled);
	}

	@Override
	public void close() {
		if (currentCancelled != null) currentCancelled.set(true);
		if (this.panel != null) {
			this.panel.clear();
		}
	}

	@Override
	public void unload() {
		close();
		this.panel = null;
	}

	@Override
	public int priority() {
		return 1;
	}

}
