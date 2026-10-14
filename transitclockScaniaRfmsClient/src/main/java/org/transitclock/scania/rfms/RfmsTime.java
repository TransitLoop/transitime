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
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * rFMS timestamps are UTC. Scania documents them as
 * {@code YYYY-MM-DDThh:mm:ss} and also returns values with a {@code Z} suffix.
 */
final class RfmsTime {

	private static final DateTimeFormatter QUERY_TIME =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

	private RfmsTime() {
	}

	static Instant parse(String value) {
		if (value == null) {
			return null;
		}
		String text = value.trim();
		if (text.isEmpty()) {
			return null;
		}
		try {
			return Instant.parse(text);
		} catch (DateTimeParseException ignored) {
			// Fall through. Scania often omits the Z suffix.
		}
		try {
			return OffsetDateTime.parse(text).toInstant();
		} catch (DateTimeParseException ignored) {
			// Fall through.
		}
		try {
			return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
		} catch (DateTimeParseException ignored) {
			return null;
		}
	}

	/**
	 * Format used on {@code starttime} and {@code stoptime}. The value is UTC
	 * and the zone suffix is omitted, as in the Scania parameter description.
	 */
	static String formatQuery(Instant instant) {
		return QUERY_TIME.format(instant);
	}
}
