package org.opentripplanner.graph_builder.linking;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.index.SpatialIndex;
import org.opentripplanner.common.geometry.HashGridSpatialIndex;
import org.opentripplanner.common.model.GenericLocation;
import org.opentripplanner.graph_builder.services.DefaultStreetEdgeFactory;
import org.opentripplanner.graph_builder.services.StreetEdgeFactory;
import org.opentripplanner.routing.bike_rental.BikeRentalStation;
import org.opentripplanner.routing.core.RoutingRequest;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.core.vehicle_sharing.VehicleDescription;
import org.opentripplanner.routing.edgetype.rentedgetype.*;
import org.opentripplanner.routing.error.TrivialPathException;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.location.TemporaryStreetLocation;
import org.opentripplanner.routing.vertextype.TemporaryRentVehicleVertex;
import org.opentripplanner.util.I18NString;
import org.opentripplanner.util.LocalizedString;
import org.opentripplanner.util.NonLocalizedString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * This class links temporary vertices like origin od destination to graph.
 */
public class TemporaryStreetSplitter {

    private static final Logger LOG = LoggerFactory.getLogger(TemporaryStreetSplitter.class);

    private static final LocalizedString ORIGIN = new LocalizedString("origin", new String[]{});

    private static final LocalizedString DESTINATION = new LocalizedString("destination", new String[]{});

    private final Graph graph;

    private final ToStreetEdgeLinker toStreetEdgeLinker;

    private final ToTransitStopLinker toTransitStopLinker;

    private final EdgesToLinkFinder edgesToLinkFinder;

    public TemporaryStreetSplitter(Graph graph, ToStreetEdgeLinker toStreetEdgeLinker,
                                   ToTransitStopLinker toTransitStopLinker, EdgesToLinkFinder edgesToLinkFinder) {
        this.graph = graph;
        this.toStreetEdgeLinker = toStreetEdgeLinker;
        this.toTransitStopLinker = toTransitStopLinker;
        this.edgesToLinkFinder = edgesToLinkFinder;
    }

    /**
     * Construct a new TemporaryStreetSplitter.
     *
     * @param graph
     * @param index            If not null this index is used instead of creating new one
     * @param transitStopIndex Index of all transitStops which is generated in {@link org.opentripplanner.routing.impl.StreetVertexIndexServiceImpl}
     */
    public static TemporaryStreetSplitter createNewDefaultInstance(
            Graph graph, @Nullable HashGridSpatialIndex<Edge> index, @Nullable SpatialIndex transitStopIndex) {
        if (index == null) {
            index = LinkingGeoTools.createHashGridSpatialIndex(graph);
        }
        StreetEdgeFactory streetEdgeFactory = new DefaultStreetEdgeFactory();
        EdgesMaker edgesMaker = new EdgesMaker();
        LinkingGeoTools linkingGeoTools = new LinkingGeoTools();
        BestCandidatesGetter bestCandidatesGetter = new BestCandidatesGetter();
        StreetSplitter splitter = new StreetSplitter(graph, index);
        EdgesToLinkFinder edgesToLinkFinder = new EdgesToLinkFinder(index, linkingGeoTools, bestCandidatesGetter);
        ToEdgeLinker toEdgeLinker = new ToEdgeLinker(streetEdgeFactory, splitter, edgesMaker, linkingGeoTools, false);
        ToStreetEdgeLinker toStreetEdgeLinker = new ToStreetEdgeLinker(toEdgeLinker, edgesToLinkFinder, linkingGeoTools, edgesMaker);
        ToTransitStopLinker toTransitStopLinker = new ToTransitStopLinker(transitStopIndex, linkingGeoTools, edgesMaker, bestCandidatesGetter);
        return new TemporaryStreetSplitter(graph, toStreetEdgeLinker, toTransitStopLinker, edgesToLinkFinder);
    }

    /**
     * Used to link origin and destination points to graph non destructively.
     * <p>
     * Split edges don't replace existing ones and only temporary edges and vertices are created.
     * <p>
     * Will throw TrivialPathException if origin and destination Location are on the same edge
     *
     * @param location
     * @param options
     * @param endVertex true if this is destination vertex
     */
    public TemporaryStreetLocation linkLocationToGraph(GenericLocation location, RoutingRequest options,
                                                       boolean endVertex) throws TrivialPathException {
        TemporaryStreetLocation closest = createTemporaryStreetLocation(location, options, endVertex);
        TraverseMode nonTransitMode = createTraverseMode(options, endVertex);
        if (endVertex) {
            addTemporaryDropoffVehicleEdge(closest);
        }
        if (!toStreetEdgeLinker.linkTemporarily(closest, nonTransitMode, options)) {
            if (!toTransitStopLinker.tryLinkVertexToStop(closest)) {
                LOG.warn("Couldn't link {}", location);
            }
        }
        return closest;
    }

    /**
     * Wraps rentable vehicle in `TemporaryRentVehicleVertex` and links that vertex to graph with temporary edges.
     * Split edges don't replace existing ones, so only temporary edges and vertices are created.
     */
    public Optional<TemporaryRentVehicleVertex> linkRentableVehicleToGraph(VehicleDescription vehicle) {
        TemporaryRentVehicleVertex temporaryVertex = createTemporaryRentVehicleVertex(vehicle);
        if (!toStreetEdgeLinker.linkTemporarilyBothWays(temporaryVertex, vehicle)) {
            LOG.debug("Couldn't link vehicle {} to graph", vehicle);
            return Optional.empty();
        } else {
            return Optional.of(temporaryVertex);
        }
    }

    /**
     * Wraps bike rental station in `TemporaryRentVehicleVertex` and links that vertex to graph with temporary edges.
     * Split edges don't replace existing ones, so only temporary edges and vertices are created.
     */
    public Optional<TemporaryRentVehicleVertex> linkBikeRentalStationToGraph(BikeRentalStation station) {
        TemporaryRentVehicleVertex temporaryVertex = createTemporaryRentBikeVertex(station);
        if (!toStreetEdgeLinker.linkTemporarilyBothWays(temporaryVertex, station.getBikeFromStation())) {
            LOG.debug("Couldn't link station {} to graph", station);
            return Optional.empty();
        } else {
            return Optional.of(temporaryVertex);
        }
    }

    public Optional<I18NString> findNameForVertex(Vertex vertex) {
        return edgesToLinkFinder.findNameForVertex(vertex);
    }

    private TemporaryStreetLocation createTemporaryStreetLocation(GenericLocation location, RoutingRequest options, boolean endVertex) {
        Coordinate coord = location.getCoordinate();
        String name;

        if (location.name == null || location.name.isEmpty()) {
            if (endVertex) {
                name = DESTINATION.toString(options.locale);
            } else {
                name = ORIGIN.toString(options.locale);
            }
        } else {
            name = location.name;
        }
        return new TemporaryStreetLocation(UUID.randomUUID().toString(), coord, new NonLocalizedString(name), endVertex);
    }

    private TraverseMode createTraverseMode(RoutingRequest options, boolean endVertex) {
        if (options.startingMode != null) {
            return options.startingMode;
        } else if (endVertex && options.parkAndRide) {
            return TraverseMode.WALK;
        } else if (options.modes.getCar()) {
            return TraverseMode.CAR;
        } else if (options.modes.getBicycle()) {
            return TraverseMode.BICYCLE;
        } else {
            return TraverseMode.WALK;
        }
    }

    private void addTemporaryDropoffVehicleEdge(Vertex destination) {
        if (graph.parkingZonesCalculator == null) {
            new TemporaryDropoffVehicleEdge(destination);
        } else {
            ParkingZoneInfo parkingZones = graph.parkingZonesCalculator.getParkingZonesForLocation(destination);
            CityGovParkingZoneInfo cityGovParkingZones = graph.parkingZonesCalculator
                    .getCityGovParkingZonesForLocation(destination);
            new TemporaryDropoffVehicleEdge(destination, parkingZones, cityGovParkingZones);
        }
    }

    private TemporaryRentVehicleVertex createTemporaryRentVehicleVertex(VehicleDescription vehicle) {
        TemporaryRentVehicleVertex vertex = new TemporaryRentVehicleVertex(UUID.randomUUID().toString(),
                new CoordinateXY(vehicle.getLongitude(), vehicle.getLatitude()), "Renting vehicle " + vehicle);
        addRentVehicleEdge(vertex, vehicle);
        return vertex;
    }

    private void addRentVehicleEdge(TemporaryRentVehicleVertex vertex, VehicleDescription vehicle) {
        if (graph.parkingZonesCalculator == null) {
            new RentVehicleEdge(vertex, vehicle);
        } else {
            new RentVehicleEdge(vertex, vehicle, graph.parkingZonesCalculator.getParkingZonesForLocation(vertex));
        }
    }

    private TemporaryRentVehicleVertex createTemporaryRentBikeVertex(BikeRentalStation station) {
        TemporaryRentVehicleVertex vertex = new TemporaryRentVehicleVertex(UUID.randomUUID().toString(),
                new CoordinateXY(station.longitude, station.latitude), "Renting station " + station);
        addRentBikeEdge(vertex, station);
        return vertex;
    }

    private void addRentBikeEdge(TemporaryRentVehicleVertex vertex, BikeRentalStation station) {
        if (graph.parkingZonesCalculator == null) {
            new RentBikeEdge(vertex, station);
        } else {
            new RentBikeEdge(vertex, station, graph.parkingZonesCalculator.getParkingZonesForLocation(vertex));
        }
    }
}
