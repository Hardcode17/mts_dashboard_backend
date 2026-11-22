package ru.hardcode.dashboard.service.mcpdashboard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Signed opaque cursors of the MCP Dashboard (KAN-86). Every cursor is a payload plus an
 * HMAC-SHA256 tag; the payload binds the cursor to one tool, one caller, one snapshot or
 * journal epoch and one filter key, so a substituted, foreign or stale cursor is rejected
 * instead of silently reopening a different walk. Cursors stay under the 2048-character
 * contract limit.
 */
public final class McpDashboardCursors {

	private static final int MAX_LENGTH = 2048;
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
	private static final TypeReference<java.util.Map<String, Object>> PAYLOAD = new TypeReference<>() {};

	private final ObjectMapper json;
	private final byte[] secret;

	public McpDashboardCursors(ObjectMapper json, String secret) {
		this.json = json;
		this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
	}

	/** Cursor payload as encoded on the wire. */
	public record Cursor(String tool, String caller, String binding, long position, Instant expiresAt,
			Map<String, Object> extra) {

		/** Optional opaque payload the walk carries between pages, e.g. the checkpoint. */
		public String extraString(String key) {
			return extra.get(key) instanceof String text ? text : null;
		}
	}

	/**
	 * Encodes one page position. {@code binding} ties the cursor to the walk (snapshot and
	 * filters, or the journal epoch); {@code caller} must be the principal name of the
	 * authenticated caller so a cursor never works for someone else.
	 */
	public String encode(String tool, String caller, String binding, long position, Duration ttl, Instant now) {
		return encode(tool, caller, binding, position, ttl, now, Map.of());
	}

	/** Encodes one page position with extra signed payload (e.g. the fixed checkpoint). */
	public String encode(String tool, String caller, String binding, long position, Duration ttl, Instant now,
			Map<String, Object> extra) {
		if (secret.length == 0) {
			throw McpDashboardException.sourceUnavailable(
					"Cursor secret is not configured; snapshot walks cannot be continued");
		}
		Instant expiresAt = now.plus(ttl);
		var payloadFields = new java.util.LinkedHashMap<String, Object>();
		payloadFields.put("v", 1);
		payloadFields.put("t", tool);
		payloadFields.put("u", caller);
		payloadFields.put("b", binding);
		payloadFields.put("p", position);
		payloadFields.put("x", expiresAt.getEpochSecond());
		payloadFields.putAll(extra);
		return encode(json.writeValueAsString(payloadFields));
	}

	/**
	 * Decodes and fully validates one cursor: format, signature, tool, caller and binding
	 * must match (a {@code null} expected binding is accepted and returned); an expired
	 * cursor is a {@code CURSOR_EXPIRED} and asks the client to resync from a snapshot.
	 */
	public Cursor decode(String cursor, String expectedTool, String caller, String expectedBinding, Instant now) {
		if (cursor == null || cursor.isBlank() || cursor.length() > MAX_LENGTH) {
			throw McpDashboardException.invalidCursor("cursor is not a valid MCP dashboard cursor");
		}
		int separator = cursor.indexOf('.');
		if (separator <= 0 || separator == cursor.length() - 1) {
			throw McpDashboardException.invalidCursor("cursor is not a valid MCP dashboard cursor");
		}
		String payload;
		try {
			byte[] signed = DECODER.decode(cursor.substring(0, separator));
			byte[] tag = DECODER.decode(cursor.substring(separator + 1));
			byte[] expected = tag(signed);
			if (!MessageDigest.isEqual(expected, tag)) {
				throw McpDashboardException.invalidCursor("cursor is not a valid MCP dashboard cursor");
			}
			payload = new String(signed, StandardCharsets.UTF_8);
		} catch (IllegalArgumentException malformed) {
			throw McpDashboardException.invalidCursor("cursor is not a valid MCP dashboard cursor");
		}
		java.util.Map<String, Object> fields = readPayload(payload);
		String tool = string(fields, "t");
		String owner = string(fields, "u");
		String binding = string(fields, "b");
		Object position = fields.get("p");
		Object expiry = fields.get("x");
		if (!expectedTool.equals(tool) || !caller.equals(owner)
				|| (expectedBinding != null && !expectedBinding.equals(binding))
				|| !(position instanceof Number number) || !(expiry instanceof Number seconds)) {
			throw McpDashboardException.invalidCursor("cursor does not belong to this walk");
		}
		Instant expiresAt = Instant.ofEpochSecond(seconds.longValue());
		if (!now.isBefore(expiresAt)) {
			throw McpDashboardException.cursorExpired("cursor has expired; resync from a fresh snapshot");
		}
		var extra = new java.util.LinkedHashMap<String, Object>(fields);
		extra.remove("v");
		extra.remove("t");
		extra.remove("u");
		extra.remove("b");
		extra.remove("p");
		extra.remove("x");
		return new Cursor(tool, owner, binding, number.longValue(), expiresAt, java.util.Map.copyOf(extra));
	}

	private java.util.Map<String, Object> readPayload(String payload) {
		try {
			java.util.Map<String, Object> fields = json.readValue(payload, PAYLOAD);
			if (fields == null || !Integer.valueOf(1).equals(fields.get("v"))) {
				throw McpDashboardException.invalidCursor("cursor is not a valid MCP dashboard cursor");
			}
			return fields;
		} catch (RuntimeException unreadable) {
			throw McpDashboardException.invalidCursor("cursor is not a valid MCP dashboard cursor");
		}
	}

	private String encode(String payload) {
		byte[] signed = payload.getBytes(StandardCharsets.UTF_8);
		byte[] tag = tag(signed);
		return ENCODER.encodeToString(signed) + "." + ENCODER.encodeToString(tag);
	}

	private byte[] tag(byte[] signed) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			return mac.doFinal(signed);
		} catch (java.security.GeneralSecurityException broken) {
			throw new IllegalStateException("HMAC cursor signing is unavailable", broken);
		}
	}

	private static String string(java.util.Map<String, Object> fields, String name) {
		Object value = fields.get(name);
		return value instanceof String text ? text : null;
	}
}
