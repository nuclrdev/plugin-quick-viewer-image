package dev.nuclr.plugin.core.quick.viewer;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.swing.JPanel;

import dev.nuclr.plugin.QuickViewItem;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class ImageViewPanel extends JPanel {

	private BufferedImage image;

	static final Set<String> IMAGE_EXTENSIONS = Set
			.of(
					"jpg",
					"jpeg",
					"png",
					"gif",
					"bmp"
					);

	public void load(QuickViewItem item) {
		try (var in = item.openStream()) {
			this.image = ImageIO.read(in);
			repaint();
		} catch (Exception e) {
			log.error("Failed to read image: {}", item.name(), e);
			this.image = null;
			repaint();
		}
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);

		g.setColor(Color.black);
		g.fillRect(0, 0, getWidth(), getHeight());

		if (image == null) {
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

	public void clear() {
		this.image = null;
		repaint();
	}
}
