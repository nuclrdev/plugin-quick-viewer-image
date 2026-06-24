package dev.nuclr.plugin.core.quick.viewer;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.Cursor;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.UIManager;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.plugin.NuclrResource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class ImageViewPanel extends JPanel {

	static {
		// Decode straight from memory instead of spilling a temp cache file to disk.
		ImageIO.setUseCache(false);
	}

	/**
	 * Upper bound on the decoded image's largest dimension. Images bigger than this are
	 * decoded with integer subsampling, so a 50-megapixel photo never pays the full decode
	 * cost just to be shown in a preview pane. The cap is generous (≈2× the screen) so normal
	 * images decode at full resolution and only enormous ones are trimmed.
	 */
	private static final int MAX_DECODE_DIMENSION = computeMaxDecodeDimension();

	private Color backgroundColor = Color.BLACK;
	private Color messageColor = new Color(235, 235, 235);
	private Color detailColor = new Color(170, 170, 170);
	private BufferedImage image;
	private NuclrResource currentResource;
	private String messageTitle;
	private String messageDetail;

	/**
	 * Pre-scaled, display-sized copy of {@link #image} for the fit view (zoom == 1). Built once
	 * (off the EDT during load, or lazily on first paint) so repaints are plain hardware blits
	 * instead of re-scaling millions of source pixels every frame. Published via volatile so the
	 * background loader and the EDT painter never see a half-updated cache.
	 */
	private volatile ScaledImage scaledCache;

	/** Immutable snapshot tying a pre-scaled bitmap to the exact source and target size it was built for. */
	private record ScaledImage(BufferedImage source, int width, int height, BufferedImage bitmap) {
	}

	private static final double MIN_ZOOM = 1.0;
	private static final double MAX_ZOOM = 16.0;
	private static final double ZOOM_STEP = 1.15;

	/** Zoom multiplier applied on top of the fit-to-panel scale. 1.0 == fit. */
	private double zoom = 1.0;
	/** Pan offset (in panel pixels) relative to the centered position. */
	private double offsetX = 0.0;
	private double offsetY = 0.0;
	private Point lastDragPoint;
	private final JPopupMenu contextMenu = new JPopupMenu();
	
	private final RotateAction rotateLeftAction = new RotateAction("Rotate left (L)", false);
	private final RotateAction rotateRightAction = new RotateAction("Rotate right (R)", true);
	private final JMenuItem rotateLeftItem = new JMenuItem(rotateLeftAction);
	private final JMenuItem rotateRightItem = new JMenuItem(rotateRightAction);
	private final JMenuItem copyImageItem = new JMenuItem(new CopyImageAction());
	private final JMenuItem copyFileItem = new JMenuItem(new CopyFileAction());
	private final JMenuItem openInExplorerItem = new JMenuItem(new OpenInExplorerAction());
	private final JMenuItem copyPathItem = new JMenuItem(new CopyPathAction());

	static final Set<String> IMAGE_EXTENSIONS = Set
			.of(
					"jpg",
					"jpeg",
					"png",
					"gif",
					"bmp"
					);

	public ImageViewPanel() {
		refreshCommanderFont(null);
		setFocusable(true);
		contextMenu.add(rotateLeftItem);
		contextMenu.add(rotateRightItem);
		contextMenu.addSeparator();
		contextMenu.add(copyImageItem);
		contextMenu.add(copyFileItem);
		contextMenu.add(copyPathItem);
		contextMenu.addSeparator();
		contextMenu.add(openInExplorerItem);
		addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				// Take keyboard focus so the L/R rotate keys work after clicking the preview.
				requestFocusInWindow();
				showContextMenuIfTriggered(e);
				if (javax.swing.SwingUtilities.isLeftMouseButton(e) && isZoomed()) {
					lastDragPoint = e.getPoint();
				}
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				showContextMenuIfTriggered(e);
				lastDragPoint = null;
				updateCursor();
			}
		});
		addMouseMotionListener(new MouseAdapter() {
			@Override
			public void mouseDragged(MouseEvent e) {
				if (lastDragPoint == null) {
					return;
				}
				offsetX += e.getX() - lastDragPoint.x;
				offsetY += e.getY() - lastDragPoint.y;
				lastDragPoint = e.getPoint();
				clampOffsets();
				repaint();
			}
		});
		addMouseWheelListener(this::handleMouseWheel);

		// L / R rotate the image 90° counter-clockwise / clockwise. Bound WHEN_FOCUSED so they only
		// fire while the preview itself holds focus, never hijacking keys from the file panel.
		getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_R, 0), "rotateRight");
		getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_L, 0), "rotateLeft");
		getActionMap().put("rotateRight", rotateRightAction);
		getActionMap().put("rotateLeft", rotateLeftAction);

		updateContextActions();
	}

	public boolean load(NuclrResource item, AtomicBoolean cancelled) {

		BufferedImage img;

		try {
			img = decode(item, cancelled);
		} catch (Exception e) {
			if (cancelled != null && cancelled.get()) {
				return false;
			}
			log.error("Failed to read image: {}", item.getName(), e);
			showMessage("Image preview unavailable", e.getMessage() != null ? e.getMessage() : "Failed to load image data.");
			return false;
		}

		try {
			if (cancelled != null && cancelled.get()) return false;
			if (img == null) {
				showMessage("Invalid image", "The selected file could not be decoded as an image.");
				return false;
			}
			this.currentResource = item;
			this.image = img;
			this.scaledCache = null;
			this.messageTitle = null;
			this.messageDetail = null;
			resetZoom();
			updateContextActions();
			// Build the fit-view bitmap now, while we are still off the EDT, so the very first
			// paint is an instant blit rather than a full-resolution rescale.
			prebuildScaledCache();
			repaint();
			return true;
		} catch (Exception e) {
			showMessage("Image preview unavailable", e.getMessage() != null ? e.getMessage() : "Failed to load image data.");
			return false;
		}
	}

	/**
	 * Decode {@code item} into a {@link BufferedImage}. Local files are read directly through an
	 * {@link ImageReader} (faster than wrapping a generic stream) and large images are subsampled
	 * during decode. Remote resources, or formats whose reader can't seek a file, fall back to a
	 * buffered, cancelable stream decode.
	 */
	private BufferedImage decode(NuclrResource item, AtomicBoolean cancelled) throws Exception {
		
		Path path = item.getPath();
		
		if (path != null && Files.isReadable(path)) {
			try {
				BufferedImage img = decodeFromFile(item, cancelled);
				if (img != null) {
					return img;
				}
			} catch (Exception e) {
				if (cancelled != null && cancelled.get()) {
					return null;
				}
				log.debug("Fast file decode failed for {}, falling back to stream", item.getName(), e);
			}
		}

		try (var rawIn = item.openInputStream()) {
			var in = new BufferedInputStream(new CancelableInputStream(rawIn, cancelled), 1 << 16);
			return ImageIO.read(in);
		}
	}

	private BufferedImage decodeFromFile(NuclrResource item, AtomicBoolean cancelled) throws IOException {
		
		try (var iis = item.openInputStream()) {
			
			Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
			if (!readers.hasNext()) {
				return null;
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(iis, true, true);
				int index = reader.getMinIndex();
				int w = reader.getWidth(index);
				int h = reader.getHeight(index);

				ImageReadParam param = reader.getDefaultReadParam();
				int subsampling = subsamplingFor(w, h);
				if (subsampling > 1) {
					param.setSourceSubsampling(subsampling, subsampling, 0, 0);
				}

				if (cancelled != null && cancelled.get()) {
					return null;
				}
				return reader.read(index, param);
			} finally {
				reader.dispose();
			}
		} catch (Exception e) {
			if (cancelled != null && cancelled.get()) {
				return null;
			}
		}
		
		return null;
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);

		g.setColor(backgroundColor);
		g.fillRect(0, 0, getWidth(), getHeight());

		if (image == null) {
			paintMessage((Graphics2D) g.create());
			return;
		}

		final int panelW = getWidth();
		final int panelH = getHeight();

		if (panelW <= 0 || panelH <= 0) {
			return;
		}

		final int imgW = image.getWidth();
		final int imgH = image.getHeight();

		if (imgW <= 0 || imgH <= 0) {
			return;
		}

		// Fit-to-panel scale (never upscaling), then apply the user zoom on top.
		final double scale = baseScale() * zoom;

		final int drawW = (int) Math.round(imgW * scale);
		final int drawH = (int) Math.round(imgH * scale);

		// Center, then apply the pan offset.
		final int x = (panelW - drawW) / 2 + (int) Math.round(offsetX);
		final int y = (panelH - drawH) / 2 + (int) Math.round(offsetY);

		Graphics2D g2 = (Graphics2D) g.create();
		try {
			// Fit view (zoom == 1): blit a pre-scaled, display-sized bitmap 1:1 — no per-frame rescale.
			BufferedImage prescaled = (zoom == 1.0) ? getOrBuildScaled(drawW, drawH) : null;

			if (prescaled != null && prescaled.getWidth() == drawW && prescaled.getHeight() == drawH) {
				g2.drawImage(prescaled, x, y, null);
			} else {
				g2
						.setRenderingHint(
								RenderingHints.KEY_INTERPOLATION,
								scale < 1.0
										? RenderingHints.VALUE_INTERPOLATION_BILINEAR
										: RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

				g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

				g2.drawImage(image, x, y, drawW, drawH, null);
			}
		} finally {
			g2.dispose();
		}

		paintZoomIndicator((Graphics2D) g.create(), scale);
	}

	/** Draws the current on-screen scale (relative to the image's actual pixels) in the corner. */
	private void paintZoomIndicator(Graphics2D g2, double scale) {
		try {
			String text = Math.round(scale * 100) + "%";

			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

			Font baseFont = commanderFont();
			Font font = baseFont.deriveFont(Font.PLAIN, Math.max(11f, baseFont.getSize2D()));
			g2.setFont(font);
			FontMetrics fm = g2.getFontMetrics();

			int padX = 8;
			int padY = 4;
			int textW = fm.stringWidth(text);
			int textH = fm.getAscent() + fm.getDescent();
			int boxW = textW + padX * 2;
			int boxH = textH + padY * 2;
			int margin = 10;
			int boxX = getWidth() - boxW - margin;
			int boxY = getHeight() - boxH - margin;

			g2.setColor(new Color(0, 0, 0, 140));
			g2.fillRoundRect(boxX, boxY, boxW, boxH, 8, 8);

			g2.setColor(messageColor);
			g2.drawString(text, boxX + padX, boxY + padY + fm.getAscent());
		} finally {
			g2.dispose();
		}
	}

	/** Fit-to-panel scale (contain), capped so images are never upscaled at zoom 1.0. */
	private double baseScale() {
		if (image == null) {
			return 1.0;
		}
		int panelW = getWidth();
		int panelH = getHeight();
		int imgW = image.getWidth();
		int imgH = image.getHeight();
		if (panelW <= 0 || panelH <= 0 || imgW <= 0 || imgH <= 0) {
			return 1.0;
		}
		double fitScale = Math.min((double) panelW / imgW, (double) panelH / imgH);
		return Math.min(1.0, fitScale);
	}

	private void handleMouseWheel(MouseWheelEvent e) {
		// Only zoom while Ctrl is held; otherwise ignore (leave normal scrolling to the parent).
		if (image == null || !e.isControlDown()) {
			return;
		}

		double oldZoom = zoom;
		double base = baseScale();
		// Wheel up (negative rotation) zooms in, wheel down zooms out.
		double factor = Math.pow(ZOOM_STEP, -e.getPreciseWheelRotation());
		double newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, oldZoom * factor));

		// Snap to actual size (100%) when the on-screen scale lands near it, for easy 1:1 viewing.
		double snappedScale = base * newZoom;
		if (snappedScale >= 0.95 && snappedScale <= 1.05) {
			newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, 1.0 / base));
		}

		if (newZoom == oldZoom) {
			return;
		}

		// Keep the image point under the cursor anchored while zooming.
		double oldScale = base * oldZoom;
		double newScale = base * newZoom;
		int imgW = image.getWidth();
		int imgH = image.getHeight();

		double oldImgX = (getWidth() - imgW * oldScale) / 2.0 + offsetX;
		double oldImgY = (getHeight() - imgH * oldScale) / 2.0 + offsetY;
		double pixelX = (e.getX() - oldImgX) / oldScale;
		double pixelY = (e.getY() - oldImgY) / oldScale;

		zoom = newZoom;
		offsetX = e.getX() - pixelX * newScale - (getWidth() - imgW * newScale) / 2.0;
		offsetY = e.getY() - pixelY * newScale - (getHeight() - imgH * newScale) / 2.0;

		clampOffsets();
		updateCursor();
		repaint();
	}

	private boolean isZoomed() {
		return image != null && zoom > MIN_ZOOM;
	}

	private void resetZoom() {
		zoom = 1.0;
		offsetX = 0.0;
		offsetY = 0.0;
		lastDragPoint = null;
		updateCursor();
	}

	/** Constrain the pan offset so the zoomed image can't be dragged off-screen. */
	private void clampOffsets() {
		if (image == null) {
			offsetX = 0.0;
			offsetY = 0.0;
			return;
		}
		double scale = baseScale() * zoom;
		double drawW = image.getWidth() * scale;
		double drawH = image.getHeight() * scale;

		double maxX = Math.max(0.0, (drawW - getWidth()) / 2.0);
		double maxY = Math.max(0.0, (drawH - getHeight()) / 2.0);

		offsetX = Math.max(-maxX, Math.min(maxX, offsetX));
		offsetY = Math.max(-maxY, Math.min(maxY, offsetY));
	}

	private void updateCursor() {
		setCursor(Cursor.getPredefinedCursor(isZoomed() ? Cursor.MOVE_CURSOR : Cursor.DEFAULT_CURSOR));
	}

	private void paintMessage(Graphics2D g2) {
		try {
			if (messageTitle == null || messageTitle.isBlank()) {
				return;
			}

			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

			Font baseFont = commanderFont();
			Font titleFont = baseFont.deriveFont(Font.BOLD, Math.max(15f, baseFont.getSize2D() + 2f));
			Font detailFont = baseFont.deriveFont(Font.PLAIN, Math.max(12f, baseFont.getSize2D()));

			FontMetrics titleMetrics = g2.getFontMetrics(titleFont);
			FontMetrics detailMetrics = g2.getFontMetrics(detailFont);

			int centerX = getWidth() / 2;
			int centerY = getHeight() / 2;
			int spacing = 8;
			int totalHeight = titleMetrics.getHeight();
			if (messageDetail != null && !messageDetail.isBlank()) {
				totalHeight += spacing + detailMetrics.getHeight();
			}

			int y = centerY - (totalHeight / 2) + titleMetrics.getAscent();

			g2.setFont(titleFont);
			g2.setColor(messageColor);
			g2.drawString(messageTitle, centerX - (titleMetrics.stringWidth(messageTitle) / 2), y);

			if (messageDetail != null && !messageDetail.isBlank()) {
				y += spacing + detailMetrics.getAscent();
				g2.setFont(detailFont);
				g2.setColor(detailColor);
				g2.drawString(messageDetail, centerX - (detailMetrics.stringWidth(messageDetail) / 2), y);
			}
		} finally {
			g2.dispose();
		}
	}

	private void showMessage(String title, String detail) {
		this.image = null;
		this.scaledCache = null;
		this.messageTitle = title;
		this.messageDetail = detail;
		updateContextActions();
		repaint();
	}

	public void clear() {
		this.image = null;
		this.scaledCache = null;
		this.currentResource = null;
		this.messageTitle = null;
		this.messageDetail = null;
		resetZoom();
		updateContextActions();
		repaint();
	}

	// -------------------------------------------------------------------------
	// Rotation
	// -------------------------------------------------------------------------

	/**
	 * Rotate the current image 90° in place ({@code clockwise} for R, counter-clockwise for L).
	 * The backing bitmap is replaced so the existing fit-scale, cache and clamp logic all keep
	 * working against the new (swapped) dimensions; zoom and pan reset back to the fit view.
	 */
	private void rotate(boolean clockwise) {
		if (image == null) {
			return;
		}
		image = rotate90(image, clockwise);
		scaledCache = null;
		resetZoom();
		prebuildScaledCache();
		repaint();
	}

	/** Produce a new bitmap that is {@code src} rotated 90° (clockwise or counter-clockwise). */
	private static BufferedImage rotate90(BufferedImage src, boolean clockwise) {
		int w = src.getWidth();
		int h = src.getHeight();
		BufferedImage dst = newCompatibleImage(h, w, src.getColorModel().hasAlpha());
		Graphics2D g = dst.createGraphics();
		try {
			if (clockwise) {
				g.translate(h, 0);
				g.rotate(Math.PI / 2);
			} else {
				g.translate(0, w);
				g.rotate(-Math.PI / 2);
			}
			g.drawImage(src, 0, 0, null);
		} finally {
			g.dispose();
		}
		return dst;
	}

	// -------------------------------------------------------------------------
	// Scaled-image cache & GPU-friendly bitmap helpers
	// -------------------------------------------------------------------------

	/** Pre-build the fit-view bitmap for the current panel size, ignoring any failure. */
	private void prebuildScaledCache() {
		try {
			BufferedImage img = image;
			if (img == null) {
				return;
			}
			double scale = baseScale();
			int drawW = (int) Math.round(img.getWidth() * scale);
			int drawH = (int) Math.round(img.getHeight() * scale);
			if (drawW > 0 && drawH > 0) {
				getOrBuildScaled(drawW, drawH);
			}
		} catch (Exception e) {
			log.debug("Could not pre-build scaled image", e);
		}
	}

	/**
	 * Return a bitmap sized exactly {@code w}×{@code h} for the current {@link #image}, reusing the
	 * cached one when possible. When no downscaling is needed (panel ≥ image) the source image is
	 * returned directly so small images cost nothing.
	 */
	private BufferedImage getOrBuildScaled(int w, int h) {
		BufferedImage src = image;
		if (src == null || w <= 0 || h <= 0) {
			return null;
		}

		if (w >= src.getWidth() && h >= src.getHeight()) {
			return src;
		}

		ScaledImage cached = scaledCache;
		if (cached != null && cached.source() == src && cached.width() == w && cached.height() == h) {
			return cached.bitmap();
		}

		BufferedImage scaled = createScaled(src, w, h);
		scaledCache = new ScaledImage(src, w, h, scaled);
		return scaled;
	}

	/** High-quality downscale using progressive halving into GPU-compatible bitmaps. */
	private BufferedImage createScaled(BufferedImage src, int targetW, int targetH) {
		int w = src.getWidth();
		int h = src.getHeight();
		BufferedImage current = src;

		// Halve repeatedly until within 2× of the target — far better quality than a single
		// large bilinear step, and still cheap because each pass shrinks the working set.
		while (w > targetW * 2 || h > targetH * 2) {
			w = Math.max(targetW, w / 2);
			h = Math.max(targetH, h / 2);
			current = renderResized(current, w, h);
		}

		return renderResized(current, targetW, targetH);
	}

	private BufferedImage renderResized(BufferedImage src, int w, int h) {
		BufferedImage dst = newCompatibleImage(w, h, src.getColorModel().hasAlpha());
		Graphics2D g = dst.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(src, 0, 0, w, h, null);
		} finally {
			g.dispose();
		}
		return dst;
	}

	private static BufferedImage newCompatibleImage(int w, int h, boolean hasAlpha) {
		int transparency = hasAlpha ? Transparency.TRANSLUCENT : Transparency.OPAQUE;
		GraphicsConfiguration gc = defaultConfiguration();
		if (gc != null) {
			return gc.createCompatibleImage(w, h, transparency);
		}
		return new BufferedImage(w, h, hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
	}

	private static GraphicsConfiguration defaultConfiguration() {
		try {
			if (GraphicsEnvironment.isHeadless()) {
				return null;
			}
			return GraphicsEnvironment.getLocalGraphicsEnvironment()
					.getDefaultScreenDevice()
					.getDefaultConfiguration();
		} catch (Exception e) {
			return null;
		}
	}

	private static int subsamplingFor(int w, int h) {
		int max = Math.max(w, h);
		if (max <= MAX_DECODE_DIMENSION) {
			return 1;
		}
		return (int) Math.ceil((double) max / MAX_DECODE_DIMENSION);
	}

	private static int computeMaxDecodeDimension() {
		try {
			if (!GraphicsEnvironment.isHeadless()) {
				Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
				int max = Math.max(screen.width, screen.height);
				if (max > 0) {
					return Math.max(2048, max * 2);
				}
			}
		} catch (Exception ignored) {
			// fall through to default
		}
		return 4096;
	}

	public void applyTheme(NuclrThemeScheme themeScheme) {
		refreshCommanderFont(themeScheme);

		// The theme scheme's uiDefaults map only holds the keys a theme explicitly overrides, so it
		// can't be relied on for the active palette. The commander bakes the fully-resolved theme
		// (base LAF + overrides) into the global UIManager before broadcasting the update, so resolve
		// colors from there — with any explicit scheme override taking precedence.
		Map<String, String> overrides = themeScheme != null ? themeScheme.getUiDefaults() : Map.of();
		backgroundColor = resolveThemeColor(overrides, "Panel.background", backgroundColor);
		messageColor = resolveThemeColor(overrides, "Label.foreground", messageColor);
		detailColor = resolveThemeColor(overrides, "Label.disabledForeground", detailColor);

		repaint();
	}

	private void refreshCommanderFont(NuclrThemeScheme themeScheme) {
		Font font = themeScheme != null ? themeScheme.defaultFont() : commanderFont();
		if (font == null) {
			font = commanderFont();
		}
		setFont(font);
		contextMenu.setFont(font);
		rotateLeftItem.setFont(font);
		rotateRightItem.setFont(font);
		copyImageItem.setFont(font);
		copyFileItem.setFont(font);
		copyPathItem.setFont(font);
		openInExplorerItem.setFont(font);
	}

	private Font commanderFont() {
		Font font = UIManager.getFont("defaultFont");
		if (font == null) {
			font = UIManager.getFont("Label.font");
		}
		if (font == null) {
			font = getFont();
		}
		return font != null ? font : new Font(Font.DIALOG, Font.PLAIN, 12);
	}

	/**
	 * Resolve a theme color for {@code key}, preferring an explicit scheme override, then the live
	 * {@link UIManager} value (which reflects the full active theme), and finally {@code defaultColor}.
	 * The {@code UIManager} value is copied into a plain {@link Color} so we don't retain a
	 * {@code UIResource} that a later L&amp;F swap could mutate.
	 */
	private static Color resolveThemeColor(Map<String, String> overrides, String key, Color defaultColor) {
		String override = overrides.get(key);
		if (override != null) {
			try {
				return Color.decode(override);
			} catch (NumberFormatException ignored) {
			}
		}
		Color uiColor = UIManager.getColor(key);
		if (uiColor != null) {
			return new Color(uiColor.getRGB(), true);
		}
		return defaultColor;
	}

	private void showContextMenuIfTriggered(MouseEvent event) {
		if (!event.isPopupTrigger()) {
			return;
		}
		updateContextActions();
		contextMenu.show(event.getComponent(), event.getX(), event.getY());
	}

	private void updateContextActions() {
		boolean hasImage = image != null;
		boolean hasPath = currentPath() != null;
		rotateLeftItem.setEnabled(hasImage);
		rotateRightItem.setEnabled(hasImage);
		copyImageItem.setEnabled(hasImage);
		copyFileItem.setEnabled(hasPath);
		openInExplorerItem.setEnabled(hasPath);
		copyPathItem.setEnabled(hasPath);
	}

	private Path currentPath() {
		return currentResource != null ? currentResource.getPath() : null;
	}

	private void copyToClipboard(Transferable transferable) {
		Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
		clipboard.setContents(transferable, null);
	}

	private final class RotateAction extends AbstractAction {
		private static final long serialVersionUID = 1L;

		private final boolean clockwise;

		private RotateAction(String name, boolean clockwise) {
			super(name);
			this.clockwise = clockwise;
		}

		@Override
		public void actionPerformed(ActionEvent e) {
			rotate(clockwise);
		}
	}

	private final class CopyImageAction extends AbstractAction {
		private static final long serialVersionUID = 1L;

		private CopyImageAction() {
			super("Copy image");
		}

		@Override
		public void actionPerformed(ActionEvent e) {
			if (image == null) {
				return;
			}
			copyToClipboard(new TransferableImage(image));
		}
	}

	private final class OpenInExplorerAction extends AbstractAction {
		private static final long serialVersionUID = 1L;

		private OpenInExplorerAction() {
			super("Open in Explorer");
		}

		@Override
		public void actionPerformed(ActionEvent e) {
			Path path = currentPath();
			if (path == null) {
				return;
			}
			try {
				if (Desktop.isDesktopSupported()) {
					File file = path.toFile();
					Desktop.getDesktop().open(file.getParentFile() != null ? file.getParentFile() : file);
				}
			} catch (IOException ex) {
				log.warn("Failed to open Explorer for {}", path, ex);
			}
		}
	}

	private final class CopyFileAction extends AbstractAction {
		private static final long serialVersionUID = 1L;

		private CopyFileAction() {
			super("Copy file");
		}

		@Override
		public void actionPerformed(ActionEvent e) {
			Path path = currentPath();
			if (path == null) {
				return;
			}
			copyToClipboard(new TransferableFile(path.toFile()));
		}
	}

	private final class CopyPathAction extends AbstractAction {
		private static final long serialVersionUID = 1L;

		private CopyPathAction() {
			super("Copy path");
		}

		@Override
		public void actionPerformed(ActionEvent e) {
			Path path = currentPath();
			if (path == null) {
				return;
			}
			copyToClipboard(new StringSelection(path.toString()));
		}
	}

	private static final class TransferableImage implements Transferable {
		private final BufferedImage image;

		private TransferableImage(BufferedImage image) {
			this.image = image;
		}

		@Override
		public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
			return new java.awt.datatransfer.DataFlavor[] { java.awt.datatransfer.DataFlavor.imageFlavor };
		}

		@Override
		public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor) {
			return java.awt.datatransfer.DataFlavor.imageFlavor.equals(flavor);
		}

		@Override
		public Object getTransferData(java.awt.datatransfer.DataFlavor flavor)
				throws UnsupportedFlavorException {
			if (!isDataFlavorSupported(flavor)) {
				throw new UnsupportedFlavorException(flavor);
			}
			return image;
		}
	}

	private static final class TransferableFile implements Transferable {
		private final List<File> files;

		private TransferableFile(File file) {
			this.files = List.of(file);
		}

		@Override
		public DataFlavor[] getTransferDataFlavors() {
			return new DataFlavor[] { DataFlavor.javaFileListFlavor };
		}

		@Override
		public boolean isDataFlavorSupported(DataFlavor flavor) {
			return DataFlavor.javaFileListFlavor.equals(flavor);
		}

		@Override
		public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
			if (!isDataFlavorSupported(flavor)) {
				throw new UnsupportedFlavorException(flavor);
			}
			return files;
		}
	}
}
