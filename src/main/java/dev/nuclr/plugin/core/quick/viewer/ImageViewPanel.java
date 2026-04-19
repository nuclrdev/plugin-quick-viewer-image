package dev.nuclr.plugin.core.quick.viewer;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;
import javax.swing.JPanel;

import dev.nuclr.platform.plugin.NuclrResourcePath;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class ImageViewPanel extends JPanel {

	private Color backgroundColor = Color.BLACK;
	private Color messageColor = new Color(235, 235, 235);
	private Color detailColor = new Color(170, 170, 170);
	private BufferedImage image;
	private String messageTitle;
	private String messageDetail;

	static final Set<String> IMAGE_EXTENSIONS = Set
			.of(
					"jpg",
					"jpeg",
					"png",
					"gif",
					"bmp"
					);

	public boolean load(NuclrResourcePath item, AtomicBoolean cancelled) {
		try (var in = item.openStream()) {
			BufferedImage img = ImageIO.read(in);
			if (cancelled.get()) return false;
			if (img == null) {
				showMessage("Invalid image", "The selected file could not be decoded as an image.");
				return false;
			}
			this.image = img;
			this.messageTitle = null;
			this.messageDetail = null;
			repaint();
			return true;
		} catch (Exception e) {
			log.error("Failed to read image: {}", item.getName(), e);
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

		// Fit inside panel (contain) while preserving aspect ratio
		final double fitScale = Math
				.min(
						(double) panelW / imgW,
						(double) panelH / imgH);

		// Never upscale
		final double scale = Math.min(1.0, fitScale);

		final int drawW = (int) Math.round(imgW * scale);
		final int drawH = (int) Math.round(imgH * scale);

		// Center
		final int x = (panelW - drawW) / 2;
		final int y = (panelH - drawH) / 2;

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
		repaint();
	}

	public void clear() {
		this.image = null;
		this.messageTitle = null;
		this.messageDetail = null;
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
}
