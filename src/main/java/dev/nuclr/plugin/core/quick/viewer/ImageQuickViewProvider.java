package dev.nuclr.plugin.core.quick.viewer;

import javax.swing.JComponent;

import dev.nuclr.plugin.QuickViewItem;
import dev.nuclr.plugin.QuickViewProvider;

public class ImageQuickViewProvider implements QuickViewProvider {

	private ImageViewPanel panel;

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
		}
		return panel;
	}

	@Override
	public boolean open(QuickViewItem item) {
		getPanel(); // ensure panel exists
		return this.panel.load(item);
	}

	@Override
	public void close() {
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
