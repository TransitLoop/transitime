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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP calls to Scania. Requests are spaced so a single client stays inside
 * the published limit of 10 requests per minute.
 */
final class ScaniaHttp {

	private static final Logger logger = LoggerFactory.getLogger(ScaniaHttp.class);

	private final HttpClient httpClient;
	private final int timeoutMillis;
	private final int minIntervalMillis;
	private final Object throttleLock = new Object();
	private long nextAllowedAtMillis;

	ScaniaHttp(int timeoutMillis, int minIntervalMillis) {
		this.timeoutMillis = timeoutMillis;
		this.minIntervalMillis = minIntervalMillis;
		this.httpClient = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(Duration.ofMillis(timeoutMillis))
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
	}

	String postForm(String url, Map<String, String> form) throws ScaniaRfmsException {
		return send("POST", url, form, Map.of("Accept", "application/json"));
	}

	String postForm(String url, Map<String, String> form, Map<String, String> headers)
			throws ScaniaRfmsException {
		return send("POST", url, form, headers);
	}

	String get(String url, Map<String, String> headers) throws ScaniaRfmsException {
		return send("GET", url, null, headers);
	}

	private String send(String method, String url, Map<String, String> form, Map<String, String> headers)
			throws ScaniaRfmsException {
		throttle();
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(Duration.ofMillis(timeoutMillis));
		if (headers != null) {
			for (Map.Entry<String, String> header : headers.entrySet()) {
				builder.header(header.getKey(), header.getValue());
			}
		}
		if ("POST".equals(method)) {
			builder.header("Content-Type", "application/x-www-form-urlencoded");
			builder.POST(HttpRequest.BodyPublishers.ofString(formBody(form)));
		} else {
			builder.GET();
		}

		HttpResponse<String> response;
		try {
			response = httpClient.send(builder.build(),
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new ScaniaRfmsException("Scania request failed for " + safeUrl(url), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ScaniaRfmsException("Scania request interrupted for " + safeUrl(url), e);
		}

		int status = response.statusCode();
		logger.debug("Scania {} {} -> {}", method, safeUrl(url), status);
		if (status < 200 || status >= 300) {
			throw new ScaniaRfmsException(
					"Scania " + method + " " + safeUrl(url) + " returned " + status
							+ describe(status) + ": " + snippet(response.body()),
					status);
		}
		return response.body() == null ? "" : response.body();
	}

	private void throttle() throws ScaniaRfmsException {
		if (minIntervalMillis <= 0) {
			return;
		}
		synchronized (throttleLock) {
			long now = System.currentTimeMillis();
			long wait = nextAllowedAtMillis - now;
			if (wait > 0) {
				try {
					Thread.sleep(wait);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new ScaniaRfmsException("Interrupted while waiting for the Scania rate limit", e);
				}
			}
			nextAllowedAtMillis = System.currentTimeMillis() + minIntervalMillis;
		}
	}

	private static String formBody(Map<String, String> form) {
		StringBuilder body = new StringBuilder();
		for (Map.Entry<String, String> entry : form.entrySet()) {
			if (body.length() > 0) {
				body.append('&');
			}
			body.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
		}
		return body.toString();
	}

	static String encode(String value) {
		// URLEncoder encodes ':' as %3A. Scania's examples keep the colon in
		// the ISO timestamp, and it is legal in a query value.
		return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)
				.replace("+", "%20")
				.replace("%3A", ":");
	}

	static Map<String, String> form(String... keyValues) {
		Map<String, String> form = new LinkedHashMap<String, String>();
		for (int i = 0; i < keyValues.length; i += 2) {
			form.put(keyValues[i], keyValues[i + 1]);
		}
		return form;
	}

	private static String safeUrl(String url) {
		int query = url.indexOf('?');
		return query < 0 ? url : url.substring(0, query);
	}

	private static String snippet(String body) {
		if (body == null || body.isBlank()) {
			return "(empty body)";
		}
		String compact = body.replaceAll("\\s+", " ").trim();
		if (compact.length() > 300) {
			return compact.substring(0, 300) + "...";
		}
		return compact;
	}

	private static String describe(int status) {
		switch (status) {
		case 400:
			return " (bad request)";
		case 401:
			return " (unauthorized)";
		case 403:
			return " (forbidden; the client is missing the Data Access Location or Performance subscription)";
		case 404:
			return " (not found; the VIN is not on this customer)";
		case 406:
			return " (not acceptable; the rfms Accept header was rejected)";
		case 429:
			return " (rate limit; Scania allows 10 requests per minute)";
		case 500:
			return " (Scania internal error)";
		default:
			return "";
		}
	}
}
