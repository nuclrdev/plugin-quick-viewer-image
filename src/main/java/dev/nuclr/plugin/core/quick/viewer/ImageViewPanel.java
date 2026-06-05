package dev.nuclr.plugin.core.quick.viewer;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Cursor;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

import dev.nuclr.platform.plugin.NuclrResource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class ImageViewPanel extends JPanel {

	private Color backgroundColor = Color.BLACK;
	private Color messageColor = new Color(235, 235, 235);
	private Color detailColor = new Color(170, 170, 170);
	private BufferedImage image;
	private NuclrResource currentResource;
	private String messageTitle;
	private String messageDetail;

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
		setFocusable(true);
		contextMenu.add(copyImageItem);
		contextMenu.add(copyFileItem);
		contextMenu.add(copyPathItem);
		contextMenu.addSeparator();
		contextMenu.add(openInExplorerItem);
		addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
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
		updateContextActions();
	}

	public boolean load(NuclrResource item, AtomicBoolean cancelled) {
		
		BufferedImage img = null;

		try (var rawIn = item.openInputStream()) {
			
			var in = new CancelableInputStream(rawIn, cancelled);
				
			img = ImageIO.read(in);
			
		} catch (Exception e) {
			log.error("Failed to read image: {}", item.getName(), e);
			showMessage("Image preview unavailable", e.getMessage() != null ? e.getMessage() : "Failed to load image data.");
			return false;
		}
		
		try {
			if (cancelled.get()) return false;
			if (img == null) {
				showMessage("Invalid image", "The selected file could not be decoded as an image.");
				return false;
			}
			this.currentResource = item;
			this.image = img;
			this.messageTitle = null;
			this.messageDetail = null;
			resetZoom();
			updateContextActions();
			repaint();
			return true;
		} catch (Exception e) {
			showMessage("Image preview unavailable", e.getMessage() != null ? e.getMessage() : "Failed to load image data.");
			return false;
		}
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

			g2
					.setRenderingHint(
							RenderingHints.KEY_INTERPOLATION,
							scale < 1.0
									? RenderingHints.VALUE_INTERPOLATION_BILINEAR
									: RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

			g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

			g2.drawImage(image, x, y, drawW, drawH, null);
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

			Font baseFont = getFont() != null ? getFont() : new Font(Font.DIALOG, Font.PLAIN, 12);
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

			Font baseFont = getFont() != null ? getFont() : new Font(Font.DIALOG, Font.PLAIN, 12);
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
		this.messageTitle = title;
		this.messageDetail = detail;
		updateContextActions();
		repaint();
	}

	public void clear() {
		this.image = null;
		this.currentResource = null;
		this.messageTitle = null;
		this.messageDetail = null;
		resetZoom();
		updateContextActions();
		repaint();
	}

	public void applyTheme(Map<String, ?> theme) {
		if (theme == null) {
			return;
		}

		backgroundColor = themeColor(theme, "Panel.background", backgroundColor);
		messageColor = themeColor(theme, "Label.foreground", messageColor);
		detailColor = themeColor(theme, "Component.linkColor", detailColor);

		repaint();
	}

	private static Color themeColor(Map<String, ?> theme, String key, Color defaultColor) {
		Object value = theme.get(key);
		if (value instanceof String text) {
			try {
				return Color.decode(text);
			} catch (NumberFormatException ignored) {
			}
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
