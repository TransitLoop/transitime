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

/**
 * Scania rFMS 4.0 client used by TransitClock and by {@link GetPositions}.
 *
 * The production host is {@code https://dataaccess.scania.com}. Authentication
 * is the portal challenge handshake. Vehicle positions are read from
 * {@code /rfms4/vehiclepositions}.
 */
public final class ScaniaRfmsClient {

	public static final String DEFAULT_BASE_URL = "https://dataaccess.scania.com";

	private final ScaniaAuthClient auth;
	private final ScaniaVehiclePositionClient positions;

	public ScaniaRfmsClient(String baseUrl, String clientId, String secretKey, int timeoutMillis,
			int minIntervalMillis, int maxPages) {
		if (clientId == null || clientId.isBlank()) {
			throw new IllegalArgumentException("Scania client id is required");
		}
		if (secretKey == null || secretKey.isBlank()) {
			throw new IllegalArgumentException("Scania secret key is required");
		}
		String resolvedBase = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
		ScaniaHttp http = new ScaniaHttp(timeoutMillis, minIntervalMillis);
		this.auth = new ScaniaAuthClient(http, resolvedBase, clientId.trim(), secretKey.trim());
		this.positions = new ScaniaVehiclePositionClient(http, auth, resolvedBase, maxPages);
	}

	public VehiclePositionBatch fetchPositions(VehiclePositionQuery query) throws ScaniaRfmsException {
		return positions.fetch(query);
	}

	public ScaniaAuthClient.ClientSecrets rotateKey() throws ScaniaRfmsException {
		return auth.rotateKey();
	}

	public ScaniaAuthClient.ClientSecrets resetKey() throws ScaniaRfmsException {
		return auth.resetKey();
	}
}
