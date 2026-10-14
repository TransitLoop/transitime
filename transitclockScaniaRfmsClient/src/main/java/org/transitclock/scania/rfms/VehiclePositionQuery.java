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

import java.time.Instant;

/**
 * Query for {@code GET /rfms4/vehiclepositions}.
 */
public final class VehiclePositionQuery {

	private final boolean latestOnly;
	private final Instant startTime;
	private final Instant stopTime;
	private final String vin;
	private final String triggerFilter;
	private final String dateType;
	private final String lastVin;
	private final String nextLink;

	private VehiclePositionQuery(boolean latestOnly, Instant startTime, Instant stopTime, String vin,
			String triggerFilter, String dateType, String lastVin, String nextLink) {
		this.latestOnly = latestOnly;
		this.startTime = startTime;
		this.stopTime = stopTime;
		this.vin = vin;
		this.triggerFilter = triggerFilter;
		this.dateType = dateType;
		this.lastVin = lastVin;
		this.nextLink = nextLink;
	}

	public static VehiclePositionQuery latestOnly(String vin, String triggerFilter, String dateType) {
		return new VehiclePositionQuery(true, null, null, blankToNull(vin), blankToNull(triggerFilter),
				blankToNull(dateType), null, null);
	}

	public static VehiclePositionQuery between(Instant startTime, Instant stopTime, String vin,
			String triggerFilter, String dateType) {
		return new VehiclePositionQuery(false, startTime, stopTime, blankToNull(vin),
				blankToNull(triggerFilter), blankToNull(dateType), null, null);
	}

	public VehiclePositionQuery withLastVin(String lastVin) {
		return new VehiclePositionQuery(latestOnly, startTime, stopTime, vin, triggerFilter, dateType,
				blankToNull(lastVin), null);
	}

	public VehiclePositionQuery withStartTime(Instant startTime) {
		return new VehiclePositionQuery(latestOnly, startTime, stopTime, vin, triggerFilter, dateType,
				null, null);
	}

	public boolean isLatestOnly() {
		return latestOnly;
	}

	public Instant getStartTime() {
		return startTime;
	}

	public Instant getStopTime() {
		return stopTime;
	}

	public String getVin() {
		return vin;
	}

	public String getTriggerFilter() {
		return triggerFilter;
	}

	public String getDateType() {
		return dateType;
	}

	String getLastVin() {
		return lastVin;
	}

	String getNextLink() {
		return nextLink;
	}

	VehiclePositionQuery withNextLink(String nextLink) {
		return new VehiclePositionQuery(latestOnly, startTime, stopTime, vin, triggerFilter, dateType,
				lastVin, nextLink);
	}

	private static String blankToNull(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}
}
