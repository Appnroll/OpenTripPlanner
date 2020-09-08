package org.opentripplanner.routing.core.vehicle_sharing;


import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.edgetype.StreetEdge;

import java.util.ArrayList;
import java.util.List;

import static java.lang.Double.min;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "vehicleType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = KickScooterDescription.class, name = "KICKSCOOTER"),
        @JsonSubTypes.Type(value = CarDescription.class, name = "CAR"),
        @JsonSubTypes.Type(value = MotorbikeDescription.class, name = "MOTORBIKE"),
})
public abstract class VehicleDescription {

    private final String providerVehicleId;
    private final double longitude;
    private final double latitude;
    private final double rangeInMeters;

    private final List<VehicleSharingPackage> vehicleSharingPackages;

    private int activePackageIndex;

    @JsonSerialize
    private final FuelType fuelType;

    @JsonSerialize
    private final Gearbox gearbox;

    @JsonUnwrapped
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private final Provider provider;

    public VehicleDescription(String providerVehicleId, double longitude, double latitude, FuelType fuelType,
                              Gearbox gearbox, Provider provider) {
        this(providerVehicleId, longitude, latitude, fuelType, gearbox, provider, null, new VehicleSharingPackage());
    }

    public VehicleDescription(String providerVehicleId, double longitude, double latitude, FuelType fuelType,
                              Gearbox gearbox, Provider provider, Double rangeInMeters){
        this(providerVehicleId, longitude, latitude, fuelType, gearbox, provider, null, new VehicleSharingPackage());
    }

    public VehicleDescription(String providerVehicleId, double longitude, double latitude, FuelType fuelType,
                              Gearbox gearbox, Provider provider, Double rangeInMeters, VehicleSharingPackage vehicleSharingPackage) {
        if (rangeInMeters == null)
            rangeInMeters = this.getDefaultRangeInMeters();

        rangeInMeters = min(rangeInMeters, getMaximumRangeInMeters());

        this.providerVehicleId = providerVehicleId;
        this.longitude = longitude;
        this.latitude = latitude;
        this.fuelType = fuelType;
        this.gearbox = gearbox;
        this.provider = provider;
        this.rangeInMeters = rangeInMeters;
        this.vehicleSharingPackages = new ArrayList<>();
        this.vehicleSharingPackages.add(vehicleSharingPackage);
        this.activePackageIndex = 0;
    }

    @Override
    public String toString() {
        return "VehicleDescription{" +
                "providerVehicleId=" + providerVehicleId +
                ", longitude=" + longitude +
                ", latitude=" + latitude +
                ", fuelType=" + fuelType +
                ", gearbox=" + gearbox +
                ", providerId=" + provider.getProviderId() +
                ", providerName=" + provider.getProviderName() +
                '}';
    }

    public double getLongitude() {
        return longitude;
    }

    public double getLatitude() {
        return latitude;
    }

    public String getProviderVehicleId() {
        return providerVehicleId;
    }

    public FuelType getFuelType() {
        return fuelType;
    }

    public Gearbox getGearbox() {
        return gearbox;
    }

    public Provider getProvider() {
        return provider;
    }

    public double getRangeInMeters() {
        return rangeInMeters;
    }

    /**
     * Returns maximum speed on given street. Trivial getter for most vehicles.
     */
    @JsonIgnore
    public abstract double getMaxSpeedInMetersPerSecond(StreetEdge streetEdge);

    @JsonIgnore
    public abstract TraverseMode getTraverseMode();

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public abstract VehicleType getVehicleType();

    protected abstract double getDefaultRangeInMeters();

    protected Double getMaximumRangeInMeters() {
        return Double.MAX_VALUE;
    }

    public VehicleSharingPackage getVehicleSharingPackage(int index) {
        return vehicleSharingPackages.get(index);
    }

    public int getActivePackageIndex() {
        return activePackageIndex;
    }

    public void setActivePackageIndex(int activePackageIndex) {
        this.activePackageIndex = activePackageIndex;
    }

    public VehicleSharingPackage getActivePackage(){
        return this.vehicleSharingPackages.get(this.getActivePackageIndex());
    }
}
