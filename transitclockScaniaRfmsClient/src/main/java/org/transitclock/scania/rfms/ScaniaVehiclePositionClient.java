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

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads {@code GET /rfms4/vehiclepositions} and follows
 * {@code moreDataAvailable} until the result is complete or {@code maxPages}
 * is reached.
 */
public final class ScaniaVehiclePositionClient {

	static final String ACCEPT = "application/json; rfms=vehiclepositions.v4.0";

	private static final Logger logger = LoggerFactory.getLogger(ScaniaVehiclePositionClient.class);

	private final ScaniaHttp http;
	private final ScaniaAuthClient auth;
	private final String baseUrl;
	private final int maxPages;

	public ScaniaVehiclePositionClient(ScaniaHttp http, ScaniaAuthClient auth, String baseUrl, int maxPages) {
		this.http = http;
		this.auth = auth;
		this.baseUrl = ScaniaAuthClient.trimTrailingSlash(baseUrl);
		this.maxPages = maxPages;
	}

	public VehiclePositionBatch fetch(VehiclePositionQuery query) throws ScaniaRfmsException {
		List<VehiclePosition> positions = new ArrayList<VehiclePosition>();
		VehiclePositionQuery pageQuery = query;
		Instant requestServerDateTime = null;
		String previousCursor = null;

		for (int page = 1; page <= maxPages; page++) {
			String url = pageQuery.getNextLink() != null
					? resolveLink(baseUrl, pageQuery.getNextLink())
					: buildUrl(baseUrl, pageQuery);
			VehiclePositionPage parsed = VehiclePositionPage.parse(getAuthorized(url));
			positions.addAll(parsed.getPositions());
			// Keep the first page's server time. Later pages are later, and the
			// next poll must overlap this whole read so reports that arrive
			// while pages are being fetched are not skipped.
			if (requestServerDateTime == null) {
				requestServerDateTime = parsed.getRequestServerDateTime();
			}
			if (!parsed.isMoreDataAvailable()) {
				return new VehiclePositionBatch(positions, requestServerDateTime, false,
						query.isLatestOnly(), null, null);
			}

			String cursor = cursorOf(parsed, query.isLatestOnly());
			if (cursor == null || cursor.equals(previousCursor)) {
				logger.warn("Scania reported more vehicle positions but did not advance the cursor. "
						+ "Submitting the positions already read.");
				return new VehiclePositionBatch(positions, requestServerDateTime, false,
						query.isLatestOnly(), null, null);
			}
			previousCursor = cursor;

			if (page == maxPages) {
				logger.warn("Stopped after {} Scania vehicle-position pages with more data still available.",
						maxPages);
				return new VehiclePositionBatch(positions, requestServerDateTime, true,
						query.isLatestOnly(), parsed.getLastVin(), parsed.getLastReceivedDateTime());
			}

			if (parsed.getMoreDataAvailableLink() != null) {
				pageQuery = pageQuery.withNextLink(parsed.getMoreDataAvailableLink());
			} else if (query.isLatestOnly()) {
				pageQuery = query.withLastVin(parsed.getLastVin());
			} else if (parsed.getLastReceivedDateTime() != null) {
				pageQuery = query.withStartTime(parsed.getLastReceivedDateTime());
			} else {
				return new VehiclePositionBatch(positions, requestServerDateTime, true,
						query.isLatestOnly(), null, null);
			}
		}
		return new VehiclePositionBatch(positions, requestServerDateTime, true,
				query.isLatestOnly(), null, null);
	}

	private String getAuthorized(String url) throws ScaniaRfmsException {
		try {
			return getOnce(url);
		} catch (ScaniaRfmsException first) {
			if (!first.isUnauthorized()) {
				throw first;
			}
			logger.info("Scania rejected the access token. Refreshing and retrying once.");
			auth.invalidateAccessToken();
			try {
				return getOnce(url);
			} catch (ScaniaRfmsException second) {
				if (!second.isUnauthorized()) {
					throw second;
				}
				logger.info("Scania rejected the refreshed token. Repeating the challenge handshake.");
				auth.invalidate();
				return getOnce(url);
			}
		}
	}

	private String getOnce(String url) throws ScaniaRfmsException {
		String correlationId = UUID.randomUUID().toString().replace("-", "");
		return http.get(url, Map.of(
				"Accept", ACCEPT,
				"Authorization", "Bearer " + auth.getAccessToken(),
				"X-Correlation-Id", correlationId));
	}

	static String buildUrl(String baseUrl, VehiclePositionQuery query) {
		StringBuilder url = new StringBuilder(ScaniaAuthClient.trimTrailingSlash(baseUrl));
		url.append("/rfms4/vehiclepositions");
		StringBuilder params = new StringBuilder();
		append(params, "latestOnly", query.isLatestOnly() ? "true" : null);
		if (!query.isLatestOnly() && query.getStartTime() != null) {
			append(params, "starttime", RfmsTime.formatQuery(query.getStartTime()));
		}
		if (query.getStopTime() != null) {
			append(params, "stoptime", RfmsTime.formatQuery(query.getStopTime()));
		}
		append(params, "vin", query.getVin());
		append(params, "triggerFilter", query.getTriggerFilter());
		append(params, "datetype", query.getDateType());
		append(params, "lastVin", query.getLastVin());
		if (params.length() > 0) {
			url.append('?').append(params);
		}
		return url.toString();
	}

	/**
	 * Follows {@code moreDataAvailableLink} only when it stays on the same
	 * scheme and host as the configured base URL. The bearer token is not sent
	 * anywhere else.
	 */
	static String resolveLink(String baseUrl, String link) throws ScaniaRfmsException {
		URI base = URI.create(ScaniaAuthClient.trimTrailingSlash(baseUrl));
		URI next;
		try {
			next = URI.create(link.trim());
		} catch (IllegalArgumentException e) {
			throw new ScaniaRfmsException("Scania returned an unusable moreDataAvailableLink", e);
		}
		if (!next.isAbsolute()) {
			next = base.resolve(next);
		}
		if (base.getHost() == null || next.getHost() == null
				|| !base.getHost().equalsIgnoreCase(next.getHost())
				|| base.getScheme() == null
				|| !base.getScheme().equalsIgnoreCase(next.getScheme())) {
			throw new ScaniaRfmsException(
					"Refusing Scania pagination link that leaves " + base.getScheme() + "://" + base.getHost());
		}
		return next.toString();
	}

	private static String cursorOf(VehiclePositionPage page, boolean latestOnly) {
		if (page.getMoreDataAvailableLink() != null) {
			return "link:" + page.getMoreDataAvailableLink();
		}
		if (latestOnly) {
			return page.getLastVin() == null ? null : "vin:" + page.getLastVin();
		}
		return page.getLastReceivedDateTime() == null ? null : "time:" + page.getLastReceivedDateTime();
	}

	private static void append(StringBuilder params, String name, String value) {
		if (value == null || value.isBlank()) {
			return;
		}
		if (params.length() > 0) {
			params.append('&');
		}
		params.append(ScaniaHttp.encode(name)).append('=').append(ScaniaHttp.encode(value));
	}
}
