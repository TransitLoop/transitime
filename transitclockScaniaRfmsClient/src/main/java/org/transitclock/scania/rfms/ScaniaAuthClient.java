/*
 * This file is part of thetransitclock.org
 *
 * thetransitclock.org is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL) as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * thetransitclock.org is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with thetransitclock.org .  If not, see <http://www.gnu.org/licenses/>.
 */
package org.transitclock.scania.rfms;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scania FMS authentication.
 *
 * The secret key is never sent. The portal issues a challenge, the client
 * returns HMAC-SHA256(secret, challenge) as base64url, and Scania returns an
 * access token (about 1 hour) plus a refresh token (about 24 hours). Each
 * refresh returns a new refresh token and invalidates the previous one.
 */
public final class ScaniaAuthClient {

	private static final Logger logger = LoggerFactory.getLogger(ScaniaAuthClient.class);

	/** Refresh a few minutes before the documented one-hour token lifetime. */
	private static final Duration ACCESS_TOKEN_LIFETIME = Duration.ofMinutes(50);

	/** Refresh before the documented 24-hour refresh-token lifetime. */
	private static final Duration REFRESH_TOKEN_LIFETIME = Duration.ofHours(23);

	private final ScaniaHttp http;
	private final String baseUrl;
	private final String clientId;
	private final String secretKey;
	private final Object lock = new Object();

	private String accessToken;
	private String refreshToken;
	private Instant accessTokenExpiresAt = Instant.EPOCH;
	private Instant refreshTokenExpiresAt = Instant.EPOCH;

	public ScaniaAuthClient(ScaniaHttp http, String baseUrl, String clientId, String secretKey) {
		this.http = http;
		this.baseUrl = trimTrailingSlash(baseUrl);
		this.clientId = clientId;
		this.secretKey = secretKey;
	}

	public String getClientId() {
		return clientId;
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	public String getAccessToken() throws ScaniaRfmsException {
		synchronized (lock) {
			Instant now = Instant.now();
			if (accessToken != null && now.isBefore(accessTokenExpiresAt)) {
				return accessToken;
			}
			if (refreshToken != null && now.isBefore(refreshTokenExpiresAt)) {
				try {
					refresh();
					return accessToken;
				} catch (ScaniaRfmsException e) {
					refreshToken = null;
					if (e.isRateLimited()) {
						throw e;
					}
					logger.info("Scania refresh token was rejected. Requesting a new token.");
				}
			}
			login();
			return accessToken;
		}
	}

	/**
	 * Drops the access token so the next call refreshes it. The refresh token
	 * is kept.
	 */
	public void invalidateAccessToken() {
		synchronized (lock) {
			accessToken = null;
			accessTokenExpiresAt = Instant.EPOCH;
		}
	}

	/**
	 * Drops both tokens so the next call repeats the challenge handshake.
	 */
	public void invalidate() {
		synchronized (lock) {
			accessToken = null;
			refreshToken = null;
			accessTokenExpiresAt = Instant.EPOCH;
			refreshTokenExpiresAt = Instant.EPOCH;
		}
	}

	/**
	 * Rotates the client secret. The previous secret stays valid for 24 hours.
	 * This does not change the secret stored in this object. Update the
	 * TransitClock configuration with {@link ClientSecrets#getCurrentSecret()}
	 * and restart.
	 */
	public ClientSecrets rotateKey() throws ScaniaRfmsException {
		return postKeyChange("/auth/rotatekey");
	}

	/**
	 * Replaces the current and previous secrets immediately. Update the
	 * TransitClock configuration with the returned secret and restart.
	 */
	public ClientSecrets resetKey() throws ScaniaRfmsException {
		return postKeyChange("/auth/resetkey");
	}

	private ClientSecrets postKeyChange(String path) throws ScaniaRfmsException {
		String body = http.postForm(baseUrl + path,
				ScaniaHttp.form("clientId", clientId),
				Map.of(
						"Accept", "application/json",
						"Authorization", "Bearer " + getAccessToken()));
		JSONObject json = parseObject(body, "key change");
		return new ClientSecrets(
				text(json, "clientId", clientId),
				text(json, "currentSecret", null),
				text(json, "rotatedSecret", null));
	}

	private void login() throws ScaniaRfmsException {
		String challengeBody = http.postForm(baseUrl + "/auth/clientid2challenge",
				ScaniaHttp.form("clientId", clientId));
		String challenge = text(parseObject(challengeBody, "challenge"), "challenge", null);
		if (challenge == null || challenge.isBlank()) {
			throw new ScaniaRfmsException("Scania challenge response did not contain a challenge");
		}
		String challengeResponse = challengeResponse(secretKey, challenge);
		// The form field name is "Response" with a capital R. Scania matches it
		// case-sensitively.
		String tokenBody = http.postForm(baseUrl + "/auth/response2token",
				ScaniaHttp.form("clientId", clientId, "Response", challengeResponse));
		storeToken(parseObject(tokenBody, "token"));
		logger.info("Scania access token created.");
	}

	private void refresh() throws ScaniaRfmsException {
		// The form field name is "RefreshToken". Each successful call invalidates
		// the refresh token that was just used.
		String tokenBody = http.postForm(baseUrl + "/auth/refreshtoken",
				ScaniaHttp.form("clientId", clientId, "RefreshToken", refreshToken));
		storeToken(parseObject(tokenBody, "refresh"));
		logger.info("Scania access token refreshed.");
	}

	private void storeToken(JSONObject json) throws ScaniaRfmsException {
		String token = text(json, "token", null);
		if (token == null || token.isBlank()) {
			token = text(json, "Token", null);
		}
		String nextRefresh = text(json, "refreshToken", null);
		if (nextRefresh == null || nextRefresh.isBlank()) {
			nextRefresh = text(json, "RefreshToken", null);
		}
		if (token == null || token.isBlank()) {
			throw new ScaniaRfmsException("Scania token response did not contain a token");
		}
		Instant now = Instant.now();
		this.accessToken = token;
		this.accessTokenExpiresAt = now.plus(ACCESS_TOKEN_LIFETIME);
		if (nextRefresh != null && !nextRefresh.isBlank()) {
			this.refreshToken = nextRefresh;
			this.refreshTokenExpiresAt = now.plus(REFRESH_TOKEN_LIFETIME);
		}
	}

	/**
	 * HMAC-SHA256 of the base64url-decoded challenge, keyed by the
	 * base64url-decoded secret, then base64url-encoded without padding.
	 */
	public static String challengeResponse(String secretKey, String challenge) throws ScaniaRfmsException {
		try {
			byte[] secret = Base64Url.decode(secretKey);
			byte[] message = Base64Url.decode(challenge);
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			return Base64Url.encode(mac.doFinal(message));
		} catch (IllegalArgumentException e) {
			throw new ScaniaRfmsException(
					"Scania client id challenge could not be signed. Check that the secret key is the base64url value from the developer portal.",
					e);
		} catch (Exception e) {
			throw new ScaniaRfmsException("Could not create the Scania challenge response", e);
		}
	}

	private static JSONObject parseObject(String body, String what) throws ScaniaRfmsException {
		try {
			return new JSONObject(body);
		} catch (JSONException e) {
			throw new ScaniaRfmsException("Scania " + what + " response was not JSON", e);
		}
	}

	private static String text(JSONObject json, String key, String fallback) {
		if (!json.has(key) || json.isNull(key)) {
			return fallback;
		}
		String value = json.optString(key, null);
		return value == null || value.isEmpty() ? fallback : value;
	}

	static String trimTrailingSlash(String url) {
		if (url == null) {
			return "";
		}
		String trimmed = url.trim();
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed;
	}

	/**
	 * Secrets returned by rotate and reset. {@code currentSecret} is the secret
	 * to configure from now on.
	 */
	public static final class ClientSecrets {
		private final String clientId;
		private final String currentSecret;
		private final String rotatedSecret;

		ClientSecrets(String clientId, String currentSecret, String rotatedSecret) {
			this.clientId = clientId;
			this.currentSecret = currentSecret;
			this.rotatedSecret = rotatedSecret;
		}

		public String getClientId() {
			return clientId;
		}

		public String getCurrentSecret() {
			return currentSecret;
		}

		public String getRotatedSecret() {
			return rotatedSecret;
		}
	}
}
