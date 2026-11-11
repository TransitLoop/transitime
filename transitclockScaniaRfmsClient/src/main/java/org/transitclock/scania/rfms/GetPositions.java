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
 * Prints vehicle positions from a Scania rFMS 4.0 client.
 *
 * Credentials come from the environment, not from the command line, so they
 * are less likely to be stored in shell history:
 *
 * <pre>
 *   export SCANIA_CLIENT_ID=...
 *   export SCANIA_SECRET_KEY=...
 *   mvn -pl transitclockScaniaRfmsClient exec:java \
 *     -Dexec.mainClass=org.transitclock.scania.rfms.GetPositions
 * </pre>
 *
 * Optional arguments: {@code --vin}, {@code --start}, {@code --stop},
 * {@code --trigger-filter}, {@code --date-type}, {@code --base-url},
 * {@code --rotate-key}, {@code --reset-key}.
 */
public final class GetPositions {

	private GetPositions() {
	}

	public static void main(String[] args) {
		try {
			Arguments arguments = Arguments.parse(args);
			String clientId = env("SCANIA_CLIENT_ID");
			String secretKey = env("SCANIA_SECRET_KEY");
			if (clientId == null || secretKey == null) {
				System.err.println("Set SCANIA_CLIENT_ID and SCANIA_SECRET_KEY.");
				System.exit(2);
			}
			ScaniaRfmsClient client = new ScaniaRfmsClient(
					arguments.baseUrl, clientId, secretKey, 30000, 6000, 20);
			if (arguments.rotateKey) {
				printSecrets("Rotated", client.rotateKey());
				return;
			}
			if (arguments.resetKey) {
				System.out.println("Reset invalidates the current secret immediately.");
				printSecrets("Reset", client.resetKey());
				return;
			}
			VehiclePositionQuery query = arguments.query();
			VehiclePositionBatch batch = client.fetchPositions(query);
			for (VehiclePosition position : batch.getPositions()) {
				System.out.println(format(position));
			}
			System.out.println("positions=" + batch.getPositions().size()
					+ " moreDataAvailable=" + batch.isMoreDataAvailable()
					+ " requestServerDateTime=" + batch.getRequestServerDateTime());
		} catch (Exception e) {
			System.err.println(e.getMessage());
			System.exit(1);
		}
	}

	private static void printSecrets(String action, ScaniaAuthClient.ClientSecrets secrets) {
		System.out.println(action + " client " + secrets.getClientId());
		System.out.println("currentSecret=" + secrets.getCurrentSecret());
		System.out.println("rotatedSecret=" + secrets.getRotatedSecret());
		System.out.println("Store currentSecret and update transitclock.avl.scania.secretKey, then restart Core.");
	}

	private static String format(VehiclePosition position) {
		return "vin=" + position.getVin()
				+ " time=" + position.getPositionTime()
				+ " lat=" + position.getLatitude()
				+ " lon=" + position.getLongitude()
				+ " speedKph=" + position.getSpeedKph()
				+ " heading=" + (Float.isNaN(position.headingDegrees()) ? "" : position.headingDegrees())
				+ " trigger=" + position.getTriggerType()
				+ " driver=" + (position.getDriverId() == null ? "" : position.getDriverId());
	}

	private static String env(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}

	private static final class Arguments {
		private String baseUrl = ScaniaRfmsClient.DEFAULT_BASE_URL;
		private String vin;
		private String triggerFilter;
		private String dateType;
		private Instant start;
		private Instant stop;
		private boolean rotateKey;
		private boolean resetKey;

		private VehiclePositionQuery query() {
			if (start != null || stop != null) {
				return VehiclePositionQuery.between(start, stop, vin, triggerFilter, dateType);
			}
			return VehiclePositionQuery.latestOnly(vin, triggerFilter, dateType);
		}

		private static Arguments parse(String[] args) {
			Arguments arguments = new Arguments();
			for (int i = 0; i < args.length; i++) {
				String arg = args[i];
				switch (arg) {
				case "--base-url":
					arguments.baseUrl = required(args, ++i, arg);
					break;
				case "--vin":
					arguments.vin = required(args, ++i, arg);
					break;
				case "--trigger-filter":
					arguments.triggerFilter = required(args, ++i, arg);
					break;
				case "--date-type":
					arguments.dateType = required(args, ++i, arg);
					break;
				case "--start":
					arguments.start = time(required(args, ++i, arg), arg);
					break;
				case "--stop":
					arguments.stop = time(required(args, ++i, arg), arg);
					break;
				case "--rotate-key":
					arguments.rotateKey = true;
					break;
				case "--reset-key":
					arguments.resetKey = true;
					break;
				default:
					throw new IllegalArgumentException("Unknown argument " + arg);
				}
			}
			return arguments;
		}

		private static String required(String[] args, int index, String name) {
			if (index >= args.length) {
				throw new IllegalArgumentException("Missing value for " + name);
			}
			return args[index];
		}

		private static Instant time(String value, String name) {
			Instant parsed = RfmsTime.parse(value);
			if (parsed == null) {
				throw new IllegalArgumentException(name + " must be an ISO-8601 UTC time, for example 2024-06-01T10:15:30Z");
			}
			return parsed;
		}
	}
}
