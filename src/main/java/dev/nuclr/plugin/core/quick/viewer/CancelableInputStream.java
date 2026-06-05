package dev.nuclr.plugin.core.quick.viewer;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.io.input.ProxyInputStream;

public class CancelableInputStream extends ProxyInputStream {

	private final AtomicBoolean cancelled;

	public CancelableInputStream(InputStream inputStream, AtomicBoolean cancelled) {
		super(inputStream);
		this.cancelled = cancelled;
	}

	private void checkCancelled() {
		if (cancelled != null && cancelled.get()) {
			throw new CancellationException("Input stream read cancelled");
		}
	}

	@Override
	protected void beforeRead(int n) throws IOException {
		checkCancelled();
	}

	@Override
	protected void afterRead(int n) throws IOException {
		checkCancelled();
	}
}