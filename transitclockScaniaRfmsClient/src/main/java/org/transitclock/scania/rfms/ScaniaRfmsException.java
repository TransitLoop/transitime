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
 * Failure talking to the Scania Data Access APIs.
 */
public class ScaniaRfmsException extends Exception {

	private static final long serialVersionUID = 1L;

	private final int statusCode;

	public ScaniaRfmsException(String message) {
		this(message, 0, null);
	}

	public ScaniaRfmsException(String message, Throwable cause) {
		this(message, 0, cause);
	}

	public ScaniaRfmsException(String message, int statusCode) {
		this(message, statusCode, null);
	}

	public ScaniaRfmsException(String message, int statusCode, Throwable cause) {
		super(message, cause);
		this.statusCode = statusCode;
	}

	/**
	 * HTTP status, or 0 when the call never received an HTTP response.
	 */
	public int getStatusCode() {
		return statusCode;
	}

	public boolean isUnauthorized() {
		return statusCode == 401;
	}

	public boolean isRateLimited() {
		return statusCode == 429;
	}
}
