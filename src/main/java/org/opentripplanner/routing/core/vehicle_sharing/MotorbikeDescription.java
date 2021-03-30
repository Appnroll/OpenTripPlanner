package org.opentripplanner.routing.core.vehicle_sharing;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.edgetype.StreetEdge;

@EqualsAndHashCode(callSuper = true)
public class MotorbikeDescription extends VehicleDescription {

    /*
     Motorbikes are not allowed on streets with speed limit >80km/h
     */
    public static final double MAX_EDGE_TRAVERSE_SPEED_LOWER_BOUND = 80. * (10. / 36.);

    private static final double DEFAULT_RANGE_IN_METERS = 50 * 1000;
    private static final double MAX_SPEED_IN_METERS_PER_SECOND = 12;

    private static final TraverseMode TRAVERSE_MODE = TraverseMode.CAR;

    private static final VehicleType VEHICLE_TYPE = VehicleType.MOTORBIKE;

    public MotorbikeDescription(String providerVehicleId, double longitude, double latitude, FuelType fuelType,
                                Gearbox gearbox, Provider provider, Double rangeInMeters) {
        super(providerVehicleId, longitude, latitude, fuelType, gearbox, provider, rangeInMeters, false, new VehiclePricingPackage());
    }

    public MotorbikeDescription(String providerVehicleId, double longitude, double latitude, FuelType fuelType,
                                Gearbox gearbox, Provider provider, Double rangeInMeters, VehiclePricingPackage pricingPackage) {
        super(providerVehicleId, longitude, latitude, fuelType, gearbox, provider, rangeInMeters, pricingPackage);
    }

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public MotorbikeDescription(@JsonProperty("providerVehicleId") String providerVehicleId, @JsonProperty("longitude") double longitude,
                                @JsonProperty("latitude") double latitude, @JsonProperty("fuelType") FuelType fuelType,
                                @JsonProperty("gearbox") Gearbox gearbox, @JsonProperty("providerId") int providerId,
                                @JsonProperty("providerName") String providerName, @JsonProperty("rangeInMeters") Double rangeInMeters) {
        super(providerVehicleId, longitude, latitude, fuelType, gearbox, new Provider(providerId, providerName), rangeInMeters);
    }

    public MotorbikeDescription(String providerVehicleId, double longitude, double latitude, FuelType fuelType,
                                Gearbox gearbox, Provider provider) {
        this(providerVehicleId, longitude, latitude, fuelType, gearbox, provider, DEFAULT_RANGE_IN_METERS);
    }

    @Override
    public double getMaxSpeedInMetersPerSecond(StreetEdge streetEdge) {
        return MAX_SPEED_IN_METERS_PER_SECOND;
    }

    @Override
    public TraverseMode getTraverseMode() {
        return TRAVERSE_MODE;
    }

    @Override
    public VehicleType getVehicleType() {
        return VEHICLE_TYPE;
    }

    @Override
    protected double getDefaultRangeInMeters() {
        return DEFAULT_RANGE_IN_METERS;
    }

    @JsonIgnore
    public static double getMaxPossibleSpeed() {
        return MAX_SPEED_IN_METERS_PER_SECOND;
    }
}
