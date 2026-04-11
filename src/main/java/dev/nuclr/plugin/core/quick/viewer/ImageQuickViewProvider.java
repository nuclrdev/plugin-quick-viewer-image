package dev.nuclr.plugin.core.quick.viewer;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.events.NuclrEventListener;
import dev.nuclr.platform.plugin.NuclrPlugin;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResourcePath;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ImageQuickViewProvider implements NuclrPlugin {

	private static final String THEME_UPDATED_EVENT_TYPE = "dev.nuclr.platform.theme.updated";

	private NuclrPluginContext context;
	private ImageViewPanel panel;
	private volatile AtomicBoolean currentCancelled;
	private Map<String, Object> theme;

	@Override
	public JComponent panel() {
		if (panel == null) {
			panel = new ImageViewPanel();
			panel.applyTheme(theme);
		}
		return panel;
	}

	@Override
	public void load(NuclrPluginContext context, boolean template) {
		this.context = context;
	}

	@Override
	public void unload() {
		closeResource();
		panel = null;
		context = null;
	}

	@Override
	public boolean supports(NuclrResourcePath resource) {
		if (resource == null || resource.getExtension() == null) {
			return false;
		}
		return ImageViewPanel.IMAGE_EXTENSIONS.contains(resource.getExtension().toLowerCase(Locale.ROOT));
	}

	@Override
	public int priority() {
		return 1;
	}

	@Override
	public boolean openResource(NuclrResourcePath resource, AtomicBoolean cancelled) {
		if (currentCancelled != null) {
			currentCancelled.set(true);
		}
		currentCancelled = cancelled;
		panel();
		return panel.load(resource, cancelled);
	}

	@Override
	public void closeResource() {
		if (currentCancelled != null) {
			currentCancelled.set(true);
			currentCancelled = null;
		}
		if (panel != null) {
			panel.clear();
		}
	}

	public void applyTheme(Map<String, Object> theme) {
		this.theme = theme;
		if (panel != null) {
			panel.applyTheme(theme);
		}
	}

	@Override
	public String id() {
		return "dev.nuclr.plugin.core.quick.viewer.image";
	}

	@Override
	public String name() {
		return "Image Quick Viewer";
	}

	@Override
	public String version() {
		return "1.0.0";
	}

	@Override
	public String description() {
		return "A quick viewer plugin for displaying images.";
	}

	@Override
	public String author() {
		return "Nuclr Team";
	}

	@Override
	public String license() {
		return "Apache-2.0";
	}

	@Override
	public String website() {
		return "https://nuclr.dev";
	}

	@Override
	public String pageUrl() {
		return "https://nuclr.dev/plugins/core/image-quick-viewer.html";
	}

	@Override
	public String docUrl() {
		return "https://nuclr.dev/plugins/core/image-quick-viewer.html";
	}

	@Override
	public Developer type() {
		return Developer.Official;
	}

	@Override
	public void updateTheme(NuclrThemeScheme themeScheme) {

	}

	@Override
	public boolean onFocusGained() {
		return false;
	}

	@Override
	public void onFocusLost() {
	}

	@Override
	public boolean isFocused() {
		return false;
	}

}
