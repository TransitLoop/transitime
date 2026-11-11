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
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One page of the rFMS 4.0 vehiclepositions resource.
 *
 * Scania's portal describes {@code vehiclePositionResponse} as the list, while
 * the rFMS document wraps that list in an object that also carries
 * {@code moreDataAvailable}. Both shapes are accepted, in camelCase and
 * PascalCase.
 */
final class VehiclePositionPage {

	private final List<VehiclePosition> positions;
	private final boolean moreDataAvailable;
	private final String moreDataAvailableLink;
	private final Instant requestServerDateTime;

	private VehiclePositionPage(List<VehiclePosition> positions, boolean moreDataAvailable,
			String moreDataAvailableLink, Instant requestServerDateTime) {
		this.positions = positions;
		this.moreDataAvailable = moreDataAvailable;
		this.moreDataAvailableLink = moreDataAvailableLink;
		this.requestServerDateTime = requestServerDateTime;
	}

	List<VehiclePosition> getPositions() {
		return positions;
	}

	boolean isMoreDataAvailable() {
		return moreDataAvailable;
	}

	String getMoreDataAvailableLink() {
		return moreDataAvailableLink;
	}

	Instant getRequestServerDateTime() {
		return requestServerDateTime;
	}

	String getLastVin() {
		for (int i = positions.size() - 1; i >= 0; i--) {
			String vin = positions.get(i).getVin();
			if (vin != null && !vin.isBlank()) {
				return vin;
			}
		}
		return null;
	}

	Instant getLastReceivedDateTime() {
		for (int i = positions.size() - 1; i >= 0; i--) {
			Instant received = positions.get(i).getReceivedDateTime();
			if (received != null) {
				return received;
			}
		}
		return null;
	}

	static VehiclePositionPage parse(String json) throws ScaniaRfmsException {
		JSONObject root;
		try {
			root = new JSONObject(json);
		} catch (JSONException e) {
			throw new ScaniaRfmsException("Vehicle position response was not JSON", e);
		}
		try {
			JSONObject meta = root;
			JSONArray array = null;
			Object wrapped = first(root, "vehiclePositionResponse", "VehiclePositionResponse");
			if (wrapped instanceof JSONArray) {
				array = (JSONArray) wrapped;
			} else if (wrapped instanceof JSONObject) {
				meta = (JSONObject) wrapped;
				array = firstArray(meta, "vehiclePositions", "VehiclePositions");
			}
			if (array == null) {
				array = firstArray(root, "vehiclePositions", "VehiclePositions");
			}
			if (array == null) {
				throw new ScaniaRfmsException(
						"Vehicle position response did not contain vehiclePositions");
			}

			List<VehiclePosition> positions = new ArrayList<VehiclePosition>();
			for (int i = 0; i < array.length(); i++) {
				Object item = array.get(i);
				if (item instanceof JSONObject) {
					positions.add(parsePosition((JSONObject) item));
				}
			}
			return new VehiclePositionPage(
					positions,
					bool(meta, "moreDataAvailable", "MoreDataAvailable")
							|| bool(root, "moreDataAvailable", "MoreDataAvailable"),
					firstString(meta, "moreDataAvailableLink", "MoreDataAvailableLink"),
					firstInstant(meta, root, "requestServerDateTime", "RequestServerDateTime"));
		} catch (JSONException e) {
			throw new ScaniaRfmsException("Vehicle position response could not be read", e);
		}
	}

	private static VehiclePosition parsePosition(JSONObject json) throws JSONException {
		JSONObject trigger = firstObject(json, "triggerType", "TriggerType");
		JSONObject gnss = firstObject(json, "gnssPosition", "GNSSPosition", "GnssPosition");
		Double speed = gnss == null ? null : number(gnss, "speed", "Speed");
		if (speed == null) {
			speed = number(json, "wheelBasedSpeed", "WheelBasedSpeed");
		}
		Instant positionTime = gnss == null ? null
				: RfmsTime.parse(firstString(gnss, "positionDateTime", "PositionDateTime"));
		Instant created = RfmsTime.parse(firstString(json, "createdDateTime", "CreatedDateTime"));
		Instant received = RfmsTime.parse(firstString(json, "receivedDateTime", "ReceivedDateTime"));
		if (positionTime == null) {
			positionTime = created != null ? created : received;
		}
		return new VehiclePosition(
				firstString(json, "vin", "Vin", "VIN"),
				triggerTypeName(trigger),
				positionTime,
				created,
				received,
				gnss == null ? null : number(gnss, "latitude", "Latitude"),
				gnss == null ? null : number(gnss, "longitude", "Longitude"),
				speed,
				gnss == null ? null : number(gnss, "heading", "Heading"),
				driverId(json, trigger));
	}

	private static String triggerTypeName(JSONObject trigger) {
		if (trigger == null) {
			return null;
		}
		return firstString(trigger, "triggerType", "TriggerType");
	}

	private static String driverId(JSONObject position, JSONObject trigger) {
		String fromPosition = driverIdFrom(position);
		if (fromPosition != null) {
			return fromPosition;
		}
		return driverIdFrom(trigger);
	}

	private static String driverIdFrom(JSONObject parent) {
		if (parent == null) {
			return null;
		}
		Object raw = first(parent, "driverId", "DriverId");
		if (raw instanceof String) {
			String text = ((String) raw).trim();
			return text.isEmpty() ? null : text;
		}
		if (!(raw instanceof JSONObject)) {
			return null;
		}
		JSONObject driver = (JSONObject) raw;
		JSONObject tacho = firstObject(driver, "tachoDriverIdentification", "TachoDriverIdentification");
		if (tacho != null) {
			String id = firstString(tacho, "driverIdentification", "DriverIdentification");
			if (id != null && !id.isBlank()) {
				return id;
			}
		}
		Object oem = first(driver, "oemDriverIdentification", "OemDriverIdentification");
		if (oem instanceof String) {
			String text = ((String) oem).trim();
			return text.isEmpty() ? null : text;
		}
		if (oem instanceof JSONObject) {
			String id = firstString((JSONObject) oem, "oemDriverIdentification", "OemDriverIdentification");
			if (id != null && !id.isBlank()) {
				return id;
			}
		}
		return null;
	}

	private static Object first(JSONObject json, String... keys) {
		if (json == null) {
			return null;
		}
		for (String key : keys) {
			if (json.has(key) && !json.isNull(key)) {
				try {
					return json.get(key);
				} catch (JSONException e) {
					return null;
				}
			}
		}
		return null;
	}

	private static JSONObject firstObject(JSONObject json, String... keys) {
		Object value = first(json, keys);
		return value instanceof JSONObject ? (JSONObject) value : null;
	}

	private static JSONArray firstArray(JSONObject json, String... keys) throws JSONException {
		Object value = first(json, keys);
		if (value instanceof JSONArray) {
			return (JSONArray) value;
		}
		if (value instanceof JSONObject) {
			JSONArray array = new JSONArray();
			array.put(value);
			return array;
		}
		return null;
	}

	private static String firstString(JSONObject json, String... keys) {
		Object value = first(json, keys);
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return text.isEmpty() ? null : text;
	}

	private static Instant firstInstant(JSONObject primary, JSONObject fallback, String... keys) {
		Instant parsed = RfmsTime.parse(firstString(primary, keys));
		if (parsed != null || primary == fallback) {
			return parsed;
		}
		return RfmsTime.parse(firstString(fallback, keys));
	}

	private static Double number(JSONObject json, String... keys) {
		Object value = first(json, keys);
		if (value instanceof Number) {
			return ((Number) value).doubleValue();
		}
		if (value instanceof String) {
			String text = ((String) value).trim();
			if (text.isEmpty()) {
				return null;
			}
			try {
				return Double.valueOf(text);
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}

	private static boolean bool(JSONObject json, String... keys) {
		Object value = first(json, keys);
		if (value instanceof Boolean) {
			return (Boolean) value;
		}
		if (value instanceof String) {
			return Boolean.parseBoolean(((String) value).trim());
		}
		return false;
	}
}
