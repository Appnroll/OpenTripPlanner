package org.opentripplanner.routing.core.vehicle_sharing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

public enum VehicleType {

    CAR,
    MOTORBIKE,
    KICKSCOOTER,
    BIKE;

    private static final String _CAR = "car";
    private static final String _BIKE = "bike";
    private static final String _MOTORBIKE = "scooter";
    private static final String _KICKSCOOTER = "un-pedal-scooter";

    private static final Logger LOG = LoggerFactory.getLogger(VehicleType.class);

    public static VehicleType fromString(String vehicleType) {
        if (vehicleType == null) {
            return null;
        }
        try {
            return VehicleType.valueOf(vehicleType.toUpperCase(Locale.US));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Cannot be named `fromString` as it would become default constructor
    // for `@QueryParam("vehicleTypesAllowed")` in `RoutingResource.java`
    public static VehicleType fromDatabaseVehicleType(String vehicleType) {
        if (vehicleType == null) {
            LOG.warn("Cannot create vehicle type enum from null");
            return null;
        }
        switch (vehicleType) {
            case _CAR:
                return VehicleType.CAR;
            case _BIKE:
                return VehicleType.BIKE;
            case _MOTORBIKE:
                return VehicleType.MOTORBIKE;
            case _KICKSCOOTER:
                return VehicleType.KICKSCOOTER;
            default:
                LOG.warn("Cannot create vehicle type enum - unknown vehicle type {}", vehicleType);
                return null;
        }
    }

    public static String getDatabaseVehicleType(VehicleType vehicleType) {
        switch (vehicleType) {
            case CAR:
                return _CAR;
            case BIKE:
                return _BIKE;
            case MOTORBIKE:
                return _MOTORBIKE;
            case KICKSCOOTER:
                return _KICKSCOOTER;
            default:
                LOG.warn("Cannot create vehicle type string - unknown vehicle type {}", vehicleType);
                return null;
        }
    }
}
