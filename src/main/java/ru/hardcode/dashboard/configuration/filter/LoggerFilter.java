package ru.hardcode.dashboard.configuration.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class LoggerFilter extends OncePerRequestFilter {

	private static final Logger LOG = LoggerFactory.getLogger(LoggerFilter.class);
	private static final String REQUEST_ID = "requestId";
	private static final int BODY_PREVIEW_BYTES = 4096;

	private final HandlerExceptionResolver exceptionResolver;
	private final int maxCachedBodyBytes;
	private final boolean logRequestBody;

	public LoggerFilter(
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver,
			@Value("${request-logging.max-cached-body-size:1MB}") DataSize maxCachedBodySize,
			@Value("${request-logging.log-request-body:false}") boolean logRequestBody
	) {
		this.exceptionResolver = exceptionResolver;
		long bytes = maxCachedBodySize.toBytes();
		if (bytes <= 0 || bytes >= Integer.MAX_VALUE) {
			throw new IllegalArgumentException("request-logging.max-cached-body-size must be positive and less than 2 GiB");
		}
		this.maxCachedBodyBytes = (int) bytes;
		this.logRequestBody = logRequestBody;
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain
	) throws ServletException, IOException {
		long start = System.nanoTime();
		String previousRequestId = MDC.get(REQUEST_ID);
		String requestId = UUID.randomUUID().toString();
		String path = singleLine(request.getRequestURI());
		MDC.put(REQUEST_ID, requestId);
		try {
			response.setHeader("X-Request-Id", requestId);
			HttpServletRequest downstreamRequest = request;
			if (isJson(request)) {
				try {
					var cachedRequest = new CachedBodyHttpServletRequest(request, maxCachedBodyBytes);
					downstreamRequest = cachedRequest;
					if (logRequestBody && LOG.isDebugEnabled()) {
						LOG.debug("JSON request body: {}", singleLine(cachedRequest.bodyPreview(BODY_PREVIEW_BYTES)));
					}
				} catch (RequestBodyTooLargeException exception) {
					if (exceptionResolver.resolveException(request, response, null, exception) == null) {
						response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
					}
					LOG.info("HTTP {} {} status={} durationMs={}", request.getMethod(), path, response.getStatus(), elapsedMillis(start));
					return;
				}
			}
			filterChain.doFilter(downstreamRequest, response);
			if (request.isAsyncStarted()) {
				LOG.info("HTTP {} {} dispatched async durationMs={}", request.getMethod(), path, elapsedMillis(start));
			} else {
				LOG.info("HTTP {} {} status={} durationMs={}", request.getMethod(), path, response.getStatus(), elapsedMillis(start));
			}
		} catch (IOException | ServletException | RuntimeException exception) {
			LOG.warn("HTTP {} {} failed type={} durationMs={}",
					request.getMethod(), path, exception.getClass().getSimpleName(), elapsedMillis(start));
			throw exception;
		} finally {
			if (previousRequestId == null) {
				MDC.remove(REQUEST_ID);
			} else {
				MDC.put(REQUEST_ID, previousRequestId);
			}
		}
	}

	private static boolean isJson(HttpServletRequest request) {
		String contentType = request.getContentType();
		if (contentType == null) {
			return false;
		}
		try {
			MediaType mediaType = MediaType.parseMediaType(contentType);
			return "application".equals(mediaType.getType())
					&& ("json".equals(mediaType.getSubtype()) || mediaType.getSubtype().endsWith("+json"));
		} catch (InvalidMediaTypeException exception) {
			return false;
		}
	}

	private static String singleLine(String value) {
		return value.replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]", " ");
	}

	private static long elapsedMillis(long start) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
	}
}
