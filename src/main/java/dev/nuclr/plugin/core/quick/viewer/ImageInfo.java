package dev.nuclr.plugin.core.quick.viewer;

import static dev.nuclr.plugin.core.quick.viewer.Messages.tr;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;

import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

/**
 * Extracts a compact, human-readable set of image facts (dimensions, file size, and the most useful
 * EXIF tags such as camera, exposure and GPS) using the Metadata Extractor library. The result is a
 * small ordered list of {@code [label, value]} rows ready to be painted in the info overlay.
 *
 * <p>Reading is best-effort: a file with no embedded metadata still yields the basic name/size/
 * dimension rows, and any extraction failure is logged at debug level and otherwise ignored.
 */
@Slf4j
final class ImageInfo {

	/** Hard cap on a single value's length so a stray long tag can't blow out the overlay width. */
	private static final int MAX_VALUE_LENGTH = 64;

	private ImageInfo() {
	}

	/**
	 * Build the ordered label/value rows for {@code resource}. {@code decoded} is the already-loaded
	 * bitmap, used as a dimension fallback when the file headers don't carry width/height.
	 */
	static List<String[]> extract(NuclrResource resource, BufferedImage decoded) {
		Map<String, String> info = new LinkedHashMap<>();

		String name = resource != null ? resource.getName() : null;
		if (name != null && !name.isBlank()) {
			info.put(tr("info.file"), name);
			String ext = extension(name);
			if (ext != null) {
				info.put(tr("info.type"), ext.toUpperCase(Locale.ROOT));
			}
		}

		Metadata metadata = readMetadata(resource, info);

		putDimensions(info, metadata, decoded);
		putExif(info, metadata);

		List<String[]> rows = new ArrayList<>(info.size());
		for (Map.Entry<String, String> e : info.entrySet()) {
			rows.add(new String[] { e.getKey(), truncate(e.getValue()) });
		}
		return rows;
	}

	/** Read metadata from the local file (also recording the file size) or fall back to a stream. */
	private static Metadata readMetadata(NuclrResource resource, Map<String, String> info) {
		if (resource == null) {
			return null;
		}
		Path path = resource.getPath();
		try {
			if (path != null && Files.isReadable(path)) {
				info.put(tr("info.size"), humanReadableSize(Files.size(path)));
				return ImageMetadataReader.readMetadata(path.toFile());
			}
			try (InputStream in = resource.openInputStream()) {
				return ImageMetadataReader.readMetadata(in);
			}
		} catch (Exception e) {
			log.debug("Could not read image metadata for {}", resource.getName(), e);
			return null;
		}
	}

	private static void putDimensions(Map<String, String> info, Metadata metadata, BufferedImage decoded) {
		Integer w = scanInt(metadata, "Image Width");
		Integer h = scanInt(metadata, "Image Height");
		if ((w == null || h == null) && decoded != null) {
			// Headers gave us nothing; fall back to the decoded bitmap (may be subsampled for huge images).
			w = decoded.getWidth();
			h = decoded.getHeight();
		}
		if (w != null && h != null) {
			info.put(tr("info.dimensions"), tr("info.dimensions.value", String.valueOf(w), String.valueOf(h)));
		}
	}

	private static void putExif(Map<String, String> info, Metadata metadata) {
		if (metadata == null) {
			return;
		}
		ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
		ExifSubIFDDirectory sub = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
		GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);

		String camera = join(
				description(ifd0, ExifIFD0Directory.TAG_MAKE),
				description(ifd0, ExifIFD0Directory.TAG_MODEL));
		putIfPresent(info, tr("info.camera"), camera);
		putIfPresent(info, tr("info.lens"), description(sub, ExifSubIFDDirectory.TAG_LENS_MODEL));
		putIfPresent(info, tr("info.taken"), description(sub, ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL));
		putIfPresent(info, tr("info.exposure"), description(sub, ExifSubIFDDirectory.TAG_EXPOSURE_TIME));
		putIfPresent(info, tr("info.aperture"), description(sub, ExifSubIFDDirectory.TAG_FNUMBER));
		putIfPresent(info, tr("info.iso"), description(sub, ExifSubIFDDirectory.TAG_ISO_EQUIVALENT));
		putIfPresent(info, tr("info.focal.length"), description(sub, ExifSubIFDDirectory.TAG_FOCAL_LENGTH));
		putIfPresent(info, tr("info.orientation"), description(ifd0, ExifIFD0Directory.TAG_ORIENTATION));
		putIfPresent(info, tr("info.software"), description(ifd0, ExifIFD0Directory.TAG_SOFTWARE));

		if (gps != null && gps.getGeoLocation() != null) {
			putIfPresent(info, tr("info.gps"), gps.getGeoLocation().toDMSString());
		}
	}

	private static String description(Directory dir, int tagType) {
		if (dir == null || !dir.containsTag(tagType)) {
			return null;
		}
		String value = dir.getDescription(tagType);
		return value != null && !value.isBlank() ? value.trim() : null;
	}

	/** Scan every directory for the first tag with the given human name and parse its leading integer. */
	private static Integer scanInt(Metadata metadata, String tagName) {
		if (metadata == null) {
			return null;
		}
		for (Directory dir : metadata.getDirectories()) {
			for (Tag tag : dir.getTags()) {
				if (tagName.equalsIgnoreCase(tag.getTagName())) {
					Integer parsed = leadingInt(tag.getDescription());
					if (parsed != null) {
						return parsed;
					}
				}
			}
		}
		return null;
	}

	private static Integer leadingInt(String text) {
		if (text == null) {
			return null;
		}
		int i = 0;
		while (i < text.length() && Character.isDigit(text.charAt(i))) {
			i++;
		}
		if (i == 0) {
			return null;
		}
		try {
			return Integer.parseInt(text.substring(0, i));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static void putIfPresent(Map<String, String> info, String label, String value) {
		if (value != null && !value.isBlank()) {
			info.put(label, value.trim());
		}
	}

	private static String join(String a, String b) {
		if (a == null || a.isBlank()) {
			return b;
		}
		if (b == null || b.isBlank()) {
			return a;
		}
		// Avoid "Canon Canon EOS" style duplication when the model already repeats the make.
		if (b.toLowerCase(Locale.ROOT).startsWith(a.toLowerCase(Locale.ROOT))) {
			return b;
		}
		return a + " " + b;
	}

	private static String extension(String name) {
		int dot = name.lastIndexOf('.');
		if (dot < 0 || dot == name.length() - 1) {
			return null;
		}
		return name.substring(dot + 1);
	}

	private static String truncate(String value) {
		if (value.length() <= MAX_VALUE_LENGTH) {
			return value;
		}
		return value.substring(0, MAX_VALUE_LENGTH - 1) + "…";
	}

	private static String humanReadableSize(long bytes) {
		if (bytes < 1024) {
			return tr("info.size.bytes", String.valueOf(bytes));
		}
		String[] units = { tr("info.size.kb"), tr("info.size.mb"), tr("info.size.gb"), tr("info.size.tb") };
		double size = bytes;
		int unit = -1;
		do {
			size /= 1024.0;
			unit++;
		} while (size >= 1024 && unit < units.length - 1);
		return String.format(Locale.ROOT, "%.1f %s", size, units[unit]);
	}
}
