package org.opentripplanner.hasura_client.hasura_objects;

import org.opentripplanner.routing.core.vehicle_sharing.Provider;

public class BikeStationHasura extends HasuraObject {
    private int bikesAvailable;
    private int spacesAvailable;
    private Provider provider;
    private double longitude;
    private double latitude;
    private long id;
    private String providerStationId;
    private String name;

    public int getBikesAvailable() {
        return bikesAvailable;
    }

    public void setBikesAvailable(int bikesAvailable) {
        this.bikesAvailable = bikesAvailable;
    }

    public int getSpacesAvailable() {
        return spacesAvailable;
    }

    public void setSpacesAvailable(int spacesAvailable) {
        this.spacesAvailable = spacesAvailable;
    }

    public Provider getProvider() {
        return provider;
    }

    public void setProvider(Provider provider) {
        this.provider = provider;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    public double getLatitude() {
        return latitude;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getProviderStationId() {
        return providerStationId;
    }

    public void setProviderStationId(String providerStationId) {
        this.providerStationId = providerStationId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
