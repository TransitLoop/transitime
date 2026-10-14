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
 * One rFMS 4.0 vehicle position. Speed from Scania is kilometres per hour.
 * TransitClock stores metres per second, so {@link #speedMetersPerSecond()}
 * converts with the same factor as {@code Geo.KPH_TO_MPS}.
 */
public final class VehiclePosition {

	static final double KPH_TO_MPS = 0.277778d;

	private final String vin;
	private final String triggerType;
	private final Instant positionTime;
	private final Instant createdDateTime;
	private final Instant receivedDateTime;
	private final Double latitude;
	private final Double longitude;
	private final Double speedKph;
	private final Double headingDegrees;
	private final String driverId;

	VehiclePosition(String vin, String triggerType, Instant positionTime, Instant createdDateTime,
			Instant receivedDateTime, Double latitude, Double longitude, Double speedKph,
			Double headingDegrees, String driverId) {
		this.vin = vin;
		this.triggerType = triggerType;
		this.positionTime = positionTime;
		this.createdDateTime = createdDateTime;
		this.receivedDateTime = receivedDateTime;
		this.latitude = latitude;
		this.longitude = longitude;
		this.speedKph = speedKph;
		this.headingDegrees = headingDegrees;
		this.driverId = driverId;
	}

	public String getVin() {
		return vin;
	}

	public String getTriggerType() {
		return triggerType;
	}

	public Instant getPositionTime() {
		return positionTime;
	}

	public Instant getCreatedDateTime() {
		return createdDateTime;
	}

	public Instant getReceivedDateTime() {
		return receivedDateTime;
	}

	public Double getLatitude() {
		return latitude;
	}

	public Double getLongitude() {
		return longitude;
	}

	public Double getSpeedKph() {
		return speedKph;
	}

	public String getDriverId() {
		return driverId;
	}

	public boolean hasFix() {
		return vin != null && !vin.isBlank()
				&& latitude != null && longitude != null
				&& positionTime != null;
	}

	public long epochMillis() {
		return positionTime.toEpochMilli();
	}

	public float speedMetersPerSecond() {
		if (speedKph == null || speedKph < 0d) {
			return Float.NaN;
		}
		return (float) (speedKph * KPH_TO_MPS);
	}

	/**
	 * Degrees clockwise from north, or {@link Float#NaN} when missing or
	 * outside 0 inclusive to 360 exclusive. A reported heading of 360 is north.
	 */
	public float headingDegrees() {
		if (headingDegrees == null) {
			return Float.NaN;
		}
		double heading = headingDegrees;
		if (heading == 360d) {
			heading = 0d;
		}
		if (heading < 0d || heading >= 360d) {
			return Float.NaN;
		}
		return (float) heading;
	}
}
