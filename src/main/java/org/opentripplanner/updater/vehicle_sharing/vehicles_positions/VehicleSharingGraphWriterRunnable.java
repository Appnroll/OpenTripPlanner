package org.opentripplanner.updater.vehicle_sharing.vehicles_positions;

import org.opentripplanner.graph_builder.linking.TemporaryStreetSplitter;
import org.opentripplanner.routing.core.vehicle_sharing.Provider;
import org.opentripplanner.routing.core.vehicle_sharing.VehicleDescription;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.vertextype.TemporaryRentVehicleVertex;
import org.opentripplanner.routing.vertextype.TemporaryVertex;
import org.opentripplanner.updater.GraphWriterRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.util.*;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

class VehicleSharingGraphWriterRunnable implements GraphWriterRunnable {

    private static final Logger LOG = LoggerFactory.getLogger(VehicleSharingGraphWriterRunnable.class);

    private final TemporaryStreetSplitter temporaryStreetSplitter;

    private final List<VehicleDescription> vehiclesFetchedFromApi;

    private final Set<Provider> responsiveProvidersFetchedFromApi;

    private final LocalTime updateTimestamp;

    VehicleSharingGraphWriterRunnable(TemporaryStreetSplitter temporaryStreetSplitter,
                                      List<VehicleDescription> vehiclesFetchedFromApi,
                                      Set<Provider> responsiveProvidersFetchedFromApi) {
        this.temporaryStreetSplitter = temporaryStreetSplitter;
        this.vehiclesFetchedFromApi = vehiclesFetchedFromApi;
        this.responsiveProvidersFetchedFromApi = responsiveProvidersFetchedFromApi;
        this.updateTimestamp = LocalTime.now();
    }

    @Override
    public void run(Graph graph) {
        for (Provider responsiveProvider : responsiveProvidersFetchedFromApi) {
            graph.getLastProviderVehiclesUpdateTimestamps().put(responsiveProvider, updateTimestamp);
        }
        removeDisappearedRentableVehicles(graph);
        addAppearedRentableVehicles(graph);
        graph.getLastProviderVehiclesUpdateTimestamps().entrySet().removeIf(entry -> graph.isUnresponsiveGracePeriodExceeded(entry.getKey(), updateTimestamp));
        graph.routerHealth.setVehiclePosition(true);
    }

    private void removeDisappearedRentableVehicles(Graph graph) {
        Map<VehicleDescription, Optional<TemporaryRentVehicleVertex>> disappearedVehicles = getDisappearedVehicles(graph);
        List<Vertex> properlyLinkedVertices = getProperlyLinkedVertices(disappearedVehicles.values());
        TemporaryVertex.disposeAll(properlyLinkedVertices);
        disappearedVehicles.forEach(graph.vehiclesTriedToLink::remove);
        LOG.info("Removed {} rentable vehicles from graph", disappearedVehicles.size());
        LOG.debug("Removed {} properly linked rentable vehicles from graph", properlyLinkedVertices.size());
    }

    private Map<VehicleDescription, Optional<TemporaryRentVehicleVertex>> getDisappearedVehicles(Graph graph) {
        return graph.vehiclesTriedToLink.entrySet().stream()
                .filter(entry ->
                        !vehiclesFetchedFromApi.contains(entry.getKey()) &&
                        (responsiveProvidersFetchedFromApi.contains(entry.getKey().getProvider()) ||
                        graph.isUnresponsiveGracePeriodExceeded(entry.getKey().getProvider(), updateTimestamp))

                )
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private List<Vertex> getProperlyLinkedVertices(Collection<Optional<TemporaryRentVehicleVertex>> disappearedVehicles) {
        return disappearedVehicles.stream()
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(toList());
    }

    private void addAppearedRentableVehicles(Graph graph) {
        getAppearedVehicles(graph)
                .forEach(v -> graph.vehiclesTriedToLink.put(v, temporaryStreetSplitter.linkRentableVehicleToGraph(v)));
        long properlyLinked = graph.vehiclesTriedToLink.values().stream().filter(Optional::isPresent).count();
        LOG.info("Currently there are {} properly linked rentable vehicles in graph", properlyLinked);
        LOG.info("There are {} rentable vehicles which we failed to link to graph", graph.vehiclesTriedToLink.size() - properlyLinked);
    }

    private List<VehicleDescription> getAppearedVehicles(Graph graph) {
        return vehiclesFetchedFromApi.stream()
                .filter(v -> !graph.vehiclesTriedToLink.containsKey(v))
                .collect(toList());
    }
}
