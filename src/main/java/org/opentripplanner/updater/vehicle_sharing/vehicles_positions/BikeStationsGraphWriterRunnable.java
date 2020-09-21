package org.opentripplanner.updater.vehicle_sharing.vehicles_positions;

import org.opentripplanner.graph_builder.linking.TemporaryStreetSplitter;
import org.opentripplanner.routing.bike_rental.BikeRentalStation;
import org.opentripplanner.routing.edgetype.rentedgetype.DropBikeEdge;
import org.opentripplanner.routing.edgetype.rentedgetype.RentBikeEdge;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.vertextype.TemporaryRentVehicleVertex;
import org.opentripplanner.updater.GraphWriterRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * This edge allows us to rent bike from station (or leave current vehicle and rent bike).
 * This edge is a loop on {@link TemporaryRentVehicleVertex} which, when traversed, changes our current traverse mode,
 * but leaves us in the same location. Edge is dependent on {@link BikeRentalStation}. Renting a bike is impossible
 * if station is empty.
 */
public class BikeStationsGraphWriterRunnable implements GraphWriterRunnable {

    private static final Logger LOG = LoggerFactory.getLogger(BikeStationsGraphWriterRunnable.class);
    private final TemporaryStreetSplitter temporaryStreetSplitter;
    private final List<BikeRentalStation> bikeRentalStationsFetchedFromApi;

    public BikeStationsGraphWriterRunnable(TemporaryStreetSplitter temporaryStreetSplitter, List<BikeRentalStation> bikeRentalStations) {
        this.temporaryStreetSplitter = temporaryStreetSplitter;
        this.bikeRentalStationsFetchedFromApi = bikeRentalStations;
    }

    private boolean addBikeStationToGraph(BikeRentalStation station, Graph graph) {
        Optional<TemporaryRentVehicleVertex> vertex = temporaryStreetSplitter.linkBikeRentalStationToGraph(station);
        if (!vertex.isPresent()) {
            return false;
        }
        new DropBikeEdge(vertex.get(), station);
        RentBikeEdge edge = vertex.get().getOutgoing().stream()
                .filter(RentBikeEdge.class::isInstance)
                .map(RentBikeEdge.class::cast)
                .findFirst().get();
        graph.bikeRentalStationsInGraph.put(station, edge);
        return true;
    }

    private boolean updateBikeStationInfo(BikeRentalStation station, Graph graph) {
        RentBikeEdge rentVehicleEdge = graph.bikeRentalStationsInGraph.getOrDefault(station, null);
        if (rentVehicleEdge != null) {
            rentVehicleEdge.getBikeRentalStation().bikesAvailable = station.bikesAvailable;
            rentVehicleEdge.getBikeRentalStation().spacesAvailable = station.spacesAvailable;
        } else {
            return addBikeStationToGraph(station, graph);
        }
        return true;
    }


    @Override
    public void run(Graph graph) {
        int count = (int) bikeRentalStationsFetchedFromApi.stream().filter(station -> updateBikeStationInfo(station, graph)).count();
        LOG.info("Placed {} bike stations on a map", count);
    }
}
