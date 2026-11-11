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

import java.util.Base64;

/**
 * Base64url encode and decode without padding, matching the Scania
 * authentication samples.
 */
final class Base64Url {

	private Base64Url() {
	}

	static byte[] decode(String value) {
		String s = value.replace('-', '+').replace('_', '/');
		int remainder = s.length() % 4;
		if (remainder == 2) {
			s = s + "==";
		} else if (remainder == 3) {
			s = s + "=";
		} else if (remainder == 1) {
			throw new IllegalArgumentException("Illegal base64url string");
		}
		return Base64.getDecoder().decode(s);
	}

	static String encode(byte[] value) {
		String s = Base64.getEncoder().encodeToString(value);
		int pad = s.indexOf('=');
		if (pad >= 0) {
			s = s.substring(0, pad);
		}
		return s.replace('+', '-').replace('/', '_');
	}
}
