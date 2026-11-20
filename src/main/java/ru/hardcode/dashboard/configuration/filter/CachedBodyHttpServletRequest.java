package ru.hardcode.dashboard.configuration.filter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

	private final byte[] cachedBody;

	CachedBodyHttpServletRequest(HttpServletRequest request, int maxBytes) throws IOException {
		super(request);
		if (request.getContentLengthLong() > maxBytes) {
			throw new RequestBodyTooLargeException(maxBytes);
		}
		byte[] body = request.getInputStream().readNBytes(maxBytes + 1);
		if (body.length > maxBytes) {
			throw new RequestBodyTooLargeException(maxBytes);
		}
		this.cachedBody = body;
	}

	String bodyPreview(int maxBytes) {
		int length = Math.min(cachedBody.length, maxBytes);
		return new String(cachedBody, 0, length, StandardCharsets.UTF_8)
				+ (length < cachedBody.length ? " [truncated]" : "");
	}

	@Override
	public ServletInputStream getInputStream() {
		var input = new ByteArrayInputStream(cachedBody);
		return new ServletInputStream() {
			@Override
			public int read() {
				return input.read();
			}

			@Override
			public int read(byte[] bytes, int offset, int length) {
				return input.read(bytes, offset, length);
			}

			@Override
			public boolean isFinished() {
				return input.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener listener) {
				throw new UnsupportedOperationException("Non-blocking reads are not supported for cached JSON requests");
			}
		};
	}

	@Override
	public BufferedReader getReader() throws IOException {
		String encoding = getCharacterEncoding();
		var reader = encoding == null
				? new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)
				: new InputStreamReader(getInputStream(), encoding);
		return new BufferedReader(reader);
	}
}
