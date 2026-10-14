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
import java.util.Collections;
import java.util.List;

/**
 * Positions collected across one or more pages, plus the cursor for the next
 * poll.
 */
public final class VehiclePositionBatch {

	private final List<VehiclePosition> positions;
	private final Instant requestServerDateTime;
	private final boolean moreDataAvailable;
	private final boolean latestOnly;
	private final String resumeLastVin;
	private final Instant resumeStartTime;

	VehiclePositionBatch(List<VehiclePosition> positions, Instant requestServerDateTime,
			boolean moreDataAvailable, boolean latestOnly, String resumeLastVin, Instant resumeStartTime) {
		this.positions = Collections.unmodifiableList(positions);
		this.requestServerDateTime = requestServerDateTime;
		this.moreDataAvailable = moreDataAvailable;
		this.latestOnly = latestOnly;
		this.resumeLastVin = resumeLastVin;
		this.resumeStartTime = resumeStartTime;
	}

	public List<VehiclePosition> getPositions() {
		return positions;
	}

	/**
	 * Server time of the first page in this read. Use it as {@code starttime}
	 * on the next completed poll so a later page cannot move the cursor past
	 * reports that arrived while this read was still paging.
	 */
	public Instant getRequestServerDateTime() {
		return requestServerDateTime;
	}

	/**
	 * True when Scania still has data that this call did not read, because the
	 * page limit was reached.
	 */
	public boolean isMoreDataAvailable() {
		return moreDataAvailable;
	}

	public boolean isResumeLatestOnly() {
		return latestOnly;
	}

	public String getResumeLastVin() {
		return resumeLastVin;
	}

	public Instant getResumeStartTime() {
		return resumeStartTime;
	}
}
