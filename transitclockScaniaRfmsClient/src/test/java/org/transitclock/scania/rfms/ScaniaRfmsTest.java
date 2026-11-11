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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import org.junit.Test;

public class ScaniaRfmsTest {

	private static final String SECRET =
			"uGx2pdCQQCuj12Pl4myk40g2fuLQXXHFADCkVfhQrbPAfteRw0-BnN6qVHs5rRtbXURR9Xf_7I31SgDI0mI-sQ";
	private static final String CHALLENGE = "W5x6NvbIovwvmPGG0Q4IvqJyDAdQ3nNgiDy59tUbQpQ";

	@Test
	public void challengeResponseMatchesHmacSha256() throws Exception {
		assertEquals("GFGaoYrC6GpQDX7yczu6KhmERHSgqPmifmJaFss-hqc",
				ScaniaAuthClient.challengeResponse(SECRET, CHALLENGE));
	}

	@Test
	public void parsesWrappedVehiclePositionResponse() throws Exception {
		VehiclePositionPage page = VehiclePositionPage.parse("""
				{
				  "vehiclePositionResponse": {
				    "vehiclePositions": [
				      {
				        "vin": "YS2R4X20002123456",
				        "triggerType": {
				          "triggerType": "TIMER",
				          "context": "RFMS",
				          "driverId": {
				            "tachoDriverIdentification": {
				              "driverIdentification": "D12345678901234",
				              "cardIssuingMemberState": "SE"
				            }
				          }
				        },
				        "createdDateTime": "2024-06-01T10:15:30",
				        "receivedDateTime": "2024-06-01T10:15:31Z",
				        "gnssPosition": {
				          "latitude": 33.5731,
				          "longitude": -7.5898,
				          "heading": "180",
				          "altitude": 50,
				          "speed": 36.0,
				          "positionDateTime": "2024-06-01T10:15:29Z"
				        },
				        "wheelBasedSpeed": 35
				      }
				    ],
				    "moreDataAvailable": true,
				    "moreDataAvailableLink": "/rfms4/vehiclepositions?lastVin=YS2R4X20002123456",
				    "requestServerDateTime": "2024-06-01T10:15:32Z"
				  }
				}
				""");

		assertTrue(page.isMoreDataAvailable());
		assertEquals(Instant.parse("2024-06-01T10:15:32Z"), page.getRequestServerDateTime());
		assertEquals(1, page.getPositions().size());
		VehiclePosition position = page.getPositions().get(0);
		assertEquals("YS2R4X20002123456", position.getVin());
		assertEquals("TIMER", position.getTriggerType());
		assertEquals("D12345678901234", position.getDriverId());
		assertEquals(Instant.parse("2024-06-01T10:15:29Z"), position.getPositionTime());
		assertEquals(33.5731, position.getLatitude(), 0.00001);
		assertEquals(-7.5898, position.getLongitude(), 0.00001);
		assertEquals(36.0, position.getSpeedKph(), 0.00001);
		assertEquals(180f, position.headingDegrees(), 0.001f);
		assertEquals(36.0 * VehiclePosition.KPH_TO_MPS, position.speedMetersPerSecond(), 0.001f);
		assertTrue(position.hasFix());
	}

	@Test
	public void parsesPositionListAndPascalCaseFields() throws Exception {
		VehiclePositionPage page = VehiclePositionPage.parse("""
				{
				  "VehiclePositionResponse": [
				    {
				      "VIN": "YS2R4X20002123456",
				      "CreatedDateTime": "2024-06-01T10:15:30Z",
				      "ReceivedDateTime": "2024-06-01T10:15:31Z",
				      "GNSSPosition": {
				        "Latitude": 59.3,
				        "Longitude": 18.0,
				        "Heading": 360,
				        "PositionDateTime": "2024-06-01T10:15:30Z"
				      },
				      "WheelBasedSpeed": 18
				    }
				  ],
				  "MoreDataAvailable": false,
				  "RequestServerDateTime": "2024-06-01T10:15:32Z"
				}
				""");

		assertFalse(page.isMoreDataAvailable());
		VehiclePosition position = page.getPositions().get(0);
		assertEquals(0f, position.headingDegrees(), 0.001f);
		assertEquals(18.0, position.getSpeedKph(), 0.00001);
		assertEquals(Instant.parse("2024-06-01T10:15:32Z"), page.getRequestServerDateTime());
	}

	@Test
	public void buildsLatestOnlyUrl() {
		String url = ScaniaVehiclePositionClient.buildUrl(
				"https://dataaccess.scania.com/",
				VehiclePositionQuery.latestOnly("YS2R4X20002123456", "TIMER", "received"));
		assertEquals("https://dataaccess.scania.com/rfms4/vehiclepositions"
				+ "?latestOnly=true&vin=YS2R4X20002123456&triggerFilter=TIMER&datetype=received",
				url);
	}

	@Test
	public void buildsIncrementalUrlWithUnencodedTimestamp() {
		String url = ScaniaVehiclePositionClient.buildUrl(
				"https://dataaccess.scania.com",
				VehiclePositionQuery.between(Instant.parse("2024-06-01T10:15:32Z"), null, null, null, null));
		assertEquals("https://dataaccess.scania.com/rfms4/vehiclepositions?starttime=2024-06-01T10:15:32",
				url);
	}

	@Test
	public void refusesPaginationLinkOnAnotherHost() {
		try {
			ScaniaVehiclePositionClient.resolveLink("https://dataaccess.scania.com",
					"https://example.com/rfms4/vehiclepositions");
			assertTrue("link should have been refused", false);
		} catch (ScaniaRfmsException e) {
			assertTrue(e.getMessage().contains("dataaccess.scania.com"));
		}
	}

	@Test
	public void resolvesRelativePaginationLink() throws Exception {
		assertEquals("https://dataaccess.scania.com/rfms4/vehiclepositions?lastVin=ABC",
				ScaniaVehiclePositionClient.resolveLink("https://dataaccess.scania.com",
						"/rfms4/vehiclepositions?lastVin=ABC"));
	}

	@Test
	public void vinMapOverridesVehicleId() throws Exception {
		Path file = Files.createTempFile("scania-vins", ".properties");
		Files.writeString(file, """
				# fleet numbers
				ys2r4x20002123456=1201
				""");
		Map<String, String> map = VinMap.load(file);
		assertEquals("1201", VinMap.vehicleId(map, "YS2R4X20002123456"));
		assertEquals("OTHERVIN000000000", VinMap.vehicleId(map, "OTHERVIN000000000"));
		assertNull(VinMap.vehicleId(map, null));
	}

	@Test
	public void parsesTimestampsWithoutZone() {
		assertEquals(Instant.parse("2024-06-01T10:15:30Z"), RfmsTime.parse("2024-06-01T10:15:30"));
		assertNull(RfmsTime.parse("not-a-time"));
	}
}
