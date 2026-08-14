package dev.nuclr.plugin.core.quick.viewer;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;

import org.apache.commons.io.FilenameUtils;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.platform.plugin.QuickViewNuclrPlugin;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ImageQuickViewProvider implements QuickViewNuclrPlugin {

	private static final String PLUGIN_ID = "dev.nuclr.plugin.core.quick.viewer.image";
	private static final String THEME_UPDATED_EVENT_TYPE = "dev.nuclr.platform.theme.updated";

	private NuclrPluginContext context;
	private ImageViewPanel panel;
	private volatile AtomicBoolean currentCancelled;
	private NuclrThemeScheme themeScheme;
	private NuclrResource currentResource;

	@Override
	public JComponent panel() {
		if (panel == null) {
			panel = new ImageViewPanel();
			panel.applyTheme(themeScheme != null ? themeScheme : context != null ? context.getTheme() : null);
		}
		return panel;
	}

	@Override
	public void preinit(NuclrPluginContext context) {
		this.context = context;
		this.themeScheme = context != null ? context.getTheme() : null;
	}

	@Override
	public void unload() {
		closeResource();
		panel = null;
		context = null;
	}

	@Override
	public boolean supports(NuclrResource resource) {
		var path = resource.getPath();
		String extension = extension(path);
		
		if (extension == null || extension.isEmpty()) {
			extension = extension(resource);
		}
		
		if (extension == null) {
			return false;
		}
		return ImageViewPanel.IMAGE_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
	}

	private static String extension(Path path) {
		if (path == null) {
			return null; // path-less (virtual) resources are matched by name via extension(NuclrResource)
		}
		var name = path.getFileName() != null ? path.getFileName().toString() : path.toString();
		return FilenameUtils.getExtension(name);
	}
	

	private static String extension(NuclrResource resource) {
		if (resource == null || resource.getName() == null) {
			return null;
		}
		String name = resource.getName();
		int dot = name.lastIndexOf('.');
		if (dot < 0 || dot == name.length() - 1) {
			return null;
		}
		return name.substring(dot + 1);
	}



	@Override
	public boolean openResource(NuclrResource resource, AtomicBoolean cancelled) {
		if (currentCancelled != null) {
			currentCancelled.set(true);
		}
		currentResource = resource;
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

	@Override
	public void updateTheme(NuclrThemeScheme themeScheme) {
		this.themeScheme = themeScheme;
		if (panel != null) {
			panel.applyTheme(themeScheme);
		}
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

	@Override
	public NuclrResource getCurrentResource() {
		return currentResource;
	}

	@Override
	public String uuid() {
		return PLUGIN_ID;
	}


	@Override
	public NuclrPluginContext getContext() {
		return this.context;
	}

	@Override
	public void init() {
	}

}
