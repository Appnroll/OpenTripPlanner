package org.opentripplanner.updater.vehicle_sharing.parking_zones;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.locationtech.jts.geom.Geometry;
import org.opentripplanner.routing.core.vehicle_sharing.VehicleType;

import java.io.Serializable;
import java.util.List;

@Getter
@AllArgsConstructor
public class GeometryParkingZone implements Serializable {

    private final int providerId;

    private final VehicleType vehicleType;

    private final List<Geometry> geometriesAllowed;

    private final List<Geometry> geometriesDisallowed;
}
