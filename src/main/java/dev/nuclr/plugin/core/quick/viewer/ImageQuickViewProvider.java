package dev.nuclr.plugin.core.quick.viewer;

import java.io.InputStream;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nuclr.plugin.ApplicationPluginContext;
import dev.nuclr.plugin.MenuResource;
import dev.nuclr.plugin.PluginManifest;
import dev.nuclr.plugin.PluginPathResource;
import dev.nuclr.plugin.QuickViewProviderPlugin;
import dev.nuclr.plugin.ResourceContentPlugin;
import dev.nuclr.platform.events.NuclrEventListener;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ImageQuickViewProvider implements QuickViewProviderPlugin, ResourceContentPlugin, NuclrEventListener {

	private static final String THEME_UPDATED_EVENT_TYPE = "dev.nuclr.platform.theme.updated";

	private NuclrPluginContext context;
	private ImageViewPanel panel;
	private volatile AtomicBoolean currentCancelled;
	private Map<String, Object> theme;

	@Override
	public PluginManifest manifest() {
		ObjectMapper objectMapper = context != null ? context.getObjectMapper() : new ObjectMapper();
		try (InputStream is = getClass().getResourceAsStream("/plugin.json")) {
			if (is != null) {
				return objectMapper.readValue(is, PluginManifest.class);
			}
		} catch (Exception e) {
			log.error("Error reading /plugin.json for ImageQuickViewProvider", e);
		}
		return null;
	}

	@Override
	public JComponent panel() {
		if (panel == null) {
			panel = new ImageViewPanel();
			panel.applyTheme(theme);
		}
		return panel;
	}

	@Override
	public JComponent getPanel() {
		return panel();
	}

	@Override
	public List<MenuResource> menuItems(PluginPathResource source) {
		return List.of();
	}

	@Override
	public void load(NuclrPluginContext context) {
		this.context = context;
		context.getEventBus().subscribe(this);
		applyTheme(resolveTheme(context));
	}

	@Override
	public void load(ApplicationPluginContext context) {
		load((NuclrPluginContext) context);
	}

	@Override
	public void unload() {
		closeResource();
		if (context != null) {
			context.getEventBus().unsubscribe(this);
		}
		panel = null;
		context = null;
	}

	@Override
	public boolean supports(PluginPathResource resource) {
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
	public int getPriority() {
		return priority();
	}

	@Override
	public boolean openResource(PluginPathResource resource, AtomicBoolean cancelled) {
		if (currentCancelled != null) {
			currentCancelled.set(true);
		}
		currentCancelled = cancelled;
		panel();
		return panel.load(resource, cancelled);
	}

	@Override
	public boolean openItem(PluginPathResource resource, AtomicBoolean cancelled) {
		return openResource(resource, cancelled);
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
	public void closeItem() {
		closeResource();
	}

	public void applyTheme(Map<String, Object> theme) {
		this.theme = theme;
		if (panel != null) {
			panel.applyTheme(theme);
		}
	}

	@Override
	public boolean isMessageSupported(String type) {
		return THEME_UPDATED_EVENT_TYPE.equals(type);
	}

	@Override
	public void handleMessage(String type, Map<String, Object> event) {
		if (THEME_UPDATED_EVENT_TYPE.equals(type)) {
			applyTheme(resolveTheme(context));
		}
	}

	private static Map<String, Object> resolveTheme(NuclrPluginContext context) {
		if (context == null) {
			return null;
		}
		return context.getTheme();
	}
}
