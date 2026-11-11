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
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Optional VIN to TransitClock vehicle id map. Lines are
 * {@code VIN=vehicleId}. Blank lines and {@code #} comments are ignored.
 */
public final class VinMap {

	private VinMap() {
	}

	public static Map<String, String> load(Path path) throws IOException {
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		Map<String, String> map = new HashMap<String, String>();
		for (String key : properties.stringPropertyNames()) {
			String vin = key.trim().toUpperCase(Locale.ROOT);
			String vehicleId = properties.getProperty(key);
			if (!vin.isEmpty() && vehicleId != null && !vehicleId.isBlank()) {
				map.put(vin, vehicleId.trim());
			}
		}
		return Collections.unmodifiableMap(map);
	}

	public static String vehicleId(Map<String, String> vinToVehicleId, String vin) {
		if (vin == null) {
			return null;
		}
		if (vinToVehicleId != null) {
			String mapped = vinToVehicleId.get(vin.trim().toUpperCase(Locale.ROOT));
			if (mapped != null && !mapped.isBlank()) {
				return mapped;
			}
		}
		return vin.trim();
	}
}
