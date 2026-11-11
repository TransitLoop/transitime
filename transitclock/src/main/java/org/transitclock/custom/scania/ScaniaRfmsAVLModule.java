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
package org.transitclock.custom.scania;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transitclock.avl.PollUrlAvlModule;
import org.transitclock.config.BooleanConfigValue;
import org.transitclock.config.IntegerConfigValue;
import org.transitclock.config.StringConfigValue;
import org.transitclock.db.structs.AvlReport;
import org.transitclock.scania.rfms.ScaniaRfmsClient;
import org.transitclock.scania.rfms.VehiclePosition;
import org.transitclock.scania.rfms.VehiclePositionBatch;
import org.transitclock.scania.rfms.VehiclePositionQuery;
import org.transitclock.scania.rfms.VinMap;

/**
 * Polls the Scania rFMS 4.0 Vehicle Position API and feeds each fix to
 * TransitClock as an {@link AvlReport}.
 *
 * <p>
 * The first poll asks for the latest position of each subscribed vehicle.
 * Later polls ask for positions received since the previous Scania server
 * time. See transitclockScaniaRfmsClient/README.md for credentials, the rate
 * limit, vehicle-id mapping, and secret rotation.
 */
public class ScaniaRfmsAVLModule extends PollUrlAvlModule {

	private static final int VEHICLE_ID_LENGTH = 60;
	private static final int DRIVER_ID_LENGTH = 60;

	private static final StringConfigValue clientId =
			new StringConfigValue("transitclock.avl.scania.clientId",
					"",
					"Client id from the Scania Developer Portal. Not written to the log.",
					false);

	private static final StringConfigValue secretKey =
			new StringConfigValue("transitclock.avl.scania.secretKey",
					"",
					"Secret key from the Scania Developer Portal. Not written to the log.",
					false);

	private static final StringConfigValue baseUrl =
			new StringConfigValue("transitclock.avl.scania.baseUrl",
					ScaniaRfmsClient.DEFAULT_BASE_URL,
					"Scania Data Access host. Production is https://dataaccess.scania.com.");

	private static final BooleanConfigValue incremental =
			new BooleanConfigValue("transitclock.avl.scania.incremental",
					true,
					"After the first snapshot, request only positions received since "
							+ "the previous Scania server time.");

	private static final StringConfigValue triggerFilter =
			new StringConfigValue("transitclock.avl.scania.triggerFilter",
					"",
					"Optional rFMS triggerFilter, for example TIMER. Empty returns every trigger.");

	private static final StringConfigValue dateType =
			new StringConfigValue("transitclock.avl.scania.dateType",
					"received",
					"Compare starttime to created or received position time.");

	private static final StringConfigValue vins =
			new StringConfigValue("transitclock.avl.scania.vins",
					"",
					"Optional VIN list, separated by commas or semicolons. "
							+ "Empty reads every subscribed vehicle.");

	private static final StringConfigValue vinMapFile =
			new StringConfigValue("transitclock.avl.scania.vinMapFile",
					"",
					"Optional VIN=vehicleId properties file. Unlisted vehicles use the VIN "
							+ "as the TransitClock vehicle id.");

	private static final StringConfigValue source =
			new StringConfigValue("transitclock.avl.scania.source",
					"Scania",
					"AvlReport source. The database column is 10 characters.");

	private static final IntegerConfigValue timeoutMillis =
			new IntegerConfigValue("transitclock.avl.scania.timeoutMillis",
					30000,
					"HTTP connect and read timeout for Scania, in milliseconds.");

	private static final IntegerConfigValue minIntervalMillis =
			new IntegerConfigValue("transitclock.avl.scania.minIntervalMillis",
					6000,
					"Minimum gap between Scania HTTP calls. The API allows 10 requests per minute.");

	private static final IntegerConfigValue maxPages =
			new IntegerConfigValue("transitclock.avl.scania.maxPages",
					20,
					"Maximum vehiclepositions pages to read in one poll.");

	private static final Logger logger = LoggerFactory.getLogger(ScaniaRfmsAVLModule.class);

	private final ScaniaRfmsClient client;
	private final Map<String, Long> lastTimeByVehicle = new HashMap<String, Long>();

	private Map<String, String> vinToVehicleId = Map.of();
	private long vinMapModified = Long.MIN_VALUE;
	private Instant nextStartTime;
	private Instant readStartedAt;
	private boolean resume;
	private boolean resumeLatestOnly;
	private String resumeLastVin;
	private Instant resumeStartTime;

	public ScaniaRfmsAVLModule(String agencyId) {
		super(agencyId);
		if (clientId.getValue() == null || clientId.getValue().isBlank()
				|| secretKey.getValue() == null || secretKey.getValue().isBlank()) {
			throw new IllegalStateException(
					"Set transitclock.avl.scania.clientId and transitclock.avl.scania.secretKey. "
							+ "Create the client in the Scania Developer Portal.");
		}
		client = new ScaniaRfmsClient(baseUrl.getValue(), clientId.getValue(), secretKey.getValue(),
				timeoutMillis.getValue(), minIntervalMillis.getValue(), maxPages.getValue());
	}

	@Override
	protected String getUrl() {
		return baseUrl.getValue() + "/rfms4/vehiclepositions";
	}

	@Override
	protected void getAndProcessData() throws Exception {
		reloadVinMap();
		Set<String> vinFilter = vinFilter();
		VehiclePositionQuery query = nextQuery(vinFilter);
		VehiclePositionBatch batch = client.fetchPositions(query);
		rememberCursor(batch);

		List<VehiclePosition> ordered = new ArrayList<VehiclePosition>(batch.getPositions());
		ordered.sort(Comparator.comparing(VehiclePosition::getPositionTime,
				Comparator.nullsLast(Comparator.naturalOrder())));

		Collection<AvlReport> reports = new ArrayList<AvlReport>();
		int skipped = 0;
		for (VehiclePosition position : ordered) {
			if (!position.hasFix()) {
				skipped++;
				continue;
			}
			if (!vinFilter.isEmpty()
					&& !vinFilter.contains(position.getVin().toUpperCase(Locale.ROOT))) {
				continue;
			}
			String vehicleId = sized(VinMap.vehicleId(vinToVehicleId, position.getVin()), VEHICLE_ID_LENGTH);
			long time = position.epochMillis();
			Long previous = lastTimeByVehicle.get(vehicleId);
			if (previous != null && time <= previous.longValue()) {
				skipped++;
				continue;
			}
			lastTimeByVehicle.put(vehicleId, Long.valueOf(time));
			reports.add(new AvlReport(vehicleId, time, position.getLatitude().doubleValue(),
					position.getLongitude().doubleValue(), position.speedMetersPerSecond(),
					position.headingDegrees(), source.getValue(), null,
					sized(position.getDriverId(), DRIVER_ID_LENGTH), null, null, Float.NaN));
		}
		logger.info("Scania rFMS returned {} positions, submitting {}, skipped {}.",
				batch.getPositions().size(), reports.size(), skipped);
		processAvlReports(reports);
	}

	private VehiclePositionQuery nextQuery(Set<String> vinFilter) {
		String vin = vinFilter.size() == 1 ? vinFilter.iterator().next() : null;
		String trigger = triggerFilter.getValue();
		String dates = dateType.getValue();
		if (resume) {
			if (resumeLatestOnly && resumeLastVin != null) {
				return VehiclePositionQuery.latestOnly(vin, trigger, dates).withLastVin(resumeLastVin);
			}
			if (!resumeLatestOnly && resumeStartTime != null) {
				return VehiclePositionQuery.between(resumeStartTime, null, vin, trigger, dates);
			}
			resume = false;
		}
		if (incremental.getValue() && nextStartTime != null) {
			return VehiclePositionQuery.between(nextStartTime, null, vin, trigger, dates);
		}
		return VehiclePositionQuery.latestOnly(vin, trigger, dates);
	}

	private void rememberCursor(VehiclePositionBatch batch) {
		if (readStartedAt == null && batch.getRequestServerDateTime() != null) {
			readStartedAt = batch.getRequestServerDateTime();
		}
		if (batch.isMoreDataAvailable()) {
			resume = true;
			resumeLatestOnly = batch.isResumeLatestOnly();
			resumeLastVin = batch.getResumeLastVin();
			resumeStartTime = batch.getResumeStartTime();
			return;
		}
		resume = false;
		resumeLastVin = null;
		resumeStartTime = null;
		if (incremental.getValue() && readStartedAt != null) {
			nextStartTime = readStartedAt;
		}
		readStartedAt = null;
	}

	private void reloadVinMap() {
		String configured = vinMapFile.getValue();
		if (configured == null || configured.isBlank()) {
			vinToVehicleId = Map.of();
			vinMapModified = Long.MIN_VALUE;
			return;
		}
		Path path = Path.of(configured.trim());
		if (!Files.isRegularFile(path)) {
			logger.error("Scania VIN map file does not exist: {}", path);
			vinToVehicleId = Map.of();
			return;
		}
		long modified = path.toFile().lastModified();
		if (modified == vinMapModified) {
			return;
		}
		try {
			vinToVehicleId = VinMap.load(path);
			vinMapModified = modified;
			logger.info("Loaded {} Scania VIN mappings from {}", vinToVehicleId.size(), path);
		} catch (IOException e) {
			logger.error("Could not read Scania VIN map {}", path, e);
			vinToVehicleId = Map.of();
		}
	}

	private static Set<String> vinFilter() {
		String raw = vins.getValue();
		if (raw == null || raw.isBlank()) {
			return Set.of();
		}
		Set<String> filter = new HashSet<String>();
		for (String part : raw.split("[;,]")) {
			if (!part.isBlank()) {
				filter.add(part.trim().toUpperCase(Locale.ROOT));
			}
		}
		return filter;
	}

	private static String sized(String value, int max) {
		if (value == null || value.length() <= max) {
			return value;
		}
		return value.substring(0, max);
	}

	@Override
	protected Collection<AvlReport> processData(InputStream in) throws Exception {
		return null;
	}
}
