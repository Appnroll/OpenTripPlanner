package org.opentripplanner.api.resource;

import com.google.common.annotations.VisibleForTesting;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.opentripplanner.api.model.*;
import org.opentripplanner.common.geometry.DirectionUtils;
import org.opentripplanner.common.geometry.GeometryUtils;
import org.opentripplanner.common.geometry.PackedCoordinateSequence;
import org.opentripplanner.common.model.P2;
import org.opentripplanner.model.Agency;
import org.opentripplanner.model.Route;
import org.opentripplanner.model.Stop;
import org.opentripplanner.model.Trip;
import org.opentripplanner.pricing.transit.TransitPriceCalculator;
import org.opentripplanner.pricing.transit.TransitTripCost;
import org.opentripplanner.pricing.transit.ticket.TransitTicket;
import org.opentripplanner.pricing.transit.trip.model.TransitTripDescription;
import org.opentripplanner.pricing.transit.trip.model.TransitTripStage;
import org.opentripplanner.profile.BikeRentalStationInfo;
import org.opentripplanner.routing.alertpatch.Alert;
import org.opentripplanner.routing.alertpatch.AlertPatch;
import org.opentripplanner.routing.core.*;
import org.opentripplanner.routing.core.vehicle_sharing.VehicleDescription;
import org.opentripplanner.routing.edgetype.*;
import org.opentripplanner.routing.edgetype.flex.PartialPatternHop;
import org.opentripplanner.routing.edgetype.flex.TemporaryDirectPatternHop;
import org.opentripplanner.routing.error.TrivialPathException;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.location.TemporaryStreetLocation;
import org.opentripplanner.routing.services.FareService;
import org.opentripplanner.routing.services.StreetVertexIndexService;
import org.opentripplanner.routing.spt.GraphPath;
import org.opentripplanner.routing.trippattern.TripTimes;
import org.opentripplanner.routing.vertextype.*;
import org.opentripplanner.util.PolylineEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * A library class with only static methods used in converting internal GraphPaths to TripPlans, which are
 * returned by the OTP "planner" web service. TripPlans are made up of Itineraries, so the functions to produce them
 * are also bundled together here.
 */
public abstract class GraphPathToTripPlanConverter {

    private static final Logger LOG = LoggerFactory.getLogger(GraphPathToTripPlanConverter.class);
    private static final double MAX_ZAG_DISTANCE = 30; // TODO add documentation, what is a "zag"?

    /**
     * Generates a TripPlan from a set of paths
     */
    public static TripPlan generatePlan(List<GraphPath> paths, RoutingRequest request, StreetVertexIndexService streetIndex) {

        Locale requestedLocale = request.locale;

        GraphPath exemplar = paths.get(0);
        Vertex tripStartVertex = exemplar.getStartVertex();
        Vertex tripEndVertex = exemplar.getEndVertex();
        String startName = tripStartVertex.getName(requestedLocale);
        String endName = tripEndVertex.getName(requestedLocale);

        // Use vertex labels if they don't have names
        if (startName == null) {
            startName = tripStartVertex.getLabel();
        }
        if (endName == null) {
            endName = tripEndVertex.getLabel();
        }
        Place from = new Place(tripStartVertex.getX(), tripStartVertex.getY(), startName);
        Place to = new Place(tripEndVertex.getX(), tripEndVertex.getY(), endName);

        from.orig = request.from.name;
        to.orig = request.to.name;

        TripPlan plan = new TripPlan(from, to, request.getDateTime());

        // Convert GraphPaths to Itineraries, keeping track of the best non-transit (e.g. walk/bike-only) option time
        long bestNonTransitTime = Long.MAX_VALUE;
        List<Itinerary> itineraries = new LinkedList<>();
        for (GraphPath path : paths) {
            Itinerary itinerary = generateItinerary(path, request.showIntermediateStops, request.disableAlertFiltering, requestedLocale, streetIndex);
            itinerary = adjustItinerary(request, itinerary);
            if (itinerary.transitTime == 0 && itinerary.walkTime < bestNonTransitTime) {
                bestNonTransitTime = itinerary.walkTime;
            }
            itineraries.add(itinerary);
        }

        // Filter and add itineraries to plan
        for (Itinerary itinerary : itineraries) {
            // If this is a transit option whose walk/bike time is greater than that of the walk/bike-only option,
            // do not include in plan
            if (itinerary.transitTime > 0 && itinerary.walkTime > bestNonTransitTime) continue;

            plan.addItinerary(itinerary);
        }

        if (plan != null) {
            for (Itinerary i : plan.itinerary) {
                /* Communicate the fact that the only way we were able to get a response was by removing a slope limit. */
                i.tooSloped = request.rctx.slopeRestrictionRemoved;
                /* fix up from/to on first/last legs */
                if (i.legs.size() == 0) {
                    LOG.warn("itinerary has no legs");
                    continue;
                }
                Leg firstLeg = i.legs.get(0);
                firstLeg.from.orig = plan.from.orig;
                Leg lastLeg = i.legs.get(i.legs.size() - 1);
                lastLeg.to.orig = plan.to.orig;

                for (Leg leg : i.legs) {
                    if (leg.mode != TraverseMode.WALK && !leg.isTransitLeg() && Objects.isNull(leg.vehicleDescription)) {
                        LOG.warn("Returning leg without vehicle description for leg: from {} to {}, mode: {} (request: {})",
                                leg.from.name, leg.to.name, leg.mode, request);
                    }
                }
            }
        }
        request.rctx.debugOutput.finishedRendering();
        return plan;
    }

    /**
     * Check whether itinerary needs adjustments based on the request.
     *
     * @param itinerary is the itinerary
     * @param request   is the request containing the original trip planning options
     * @return the (adjusted) itinerary
     */
    private static Itinerary adjustItinerary(RoutingRequest request, Itinerary itinerary) {
        // Check walk limit distance
        if (itinerary.traverseDistance > request.maxWalkDistance) {
            itinerary.walkLimitExceeded = true;
        }
        // Return itinerary
        return itinerary;
    }

    /**
     * Generate an itinerary from a {@link GraphPath}. This method first slices the list of states
     * at the leg boundaries. These smaller state arrays are then used to generate legs. Finally the
     * rest of the itinerary is generated based on the complete state array.
     *
     * @param path                  The graph path to base the itinerary on
     * @param showIntermediateStops Whether to include intermediate stops in the itinerary or not
     * @return The generated itinerary
     */
    public static Itinerary generateItinerary(GraphPath path, boolean showIntermediateStops,
                                              boolean disableAlertFiltering, Locale requestedLocale,
                                              StreetVertexIndexService streetIndex) {
        Itinerary itinerary = new Itinerary();

        State lastState = path.states.getLast();
        List<State> states = new ArrayList<>(path.states);

        Edge[] edges = new Edge[path.edges.size()];
        edges = path.edges.toArray(edges);

        Graph graph = path.getRoutingContext().graph;

        FareService fareService = graph.getService(FareService.class);

        List<LegStateSplit> legsStates = sliceStates(states);

        if (fareService != null) {
            itinerary.fare = fareService.getCost(path);
        }

        for (LegStateSplit legStates : legsStates) {
            itinerary.addLeg(generateLeg(graph, legStates, showIntermediateStops, disableAlertFiltering, requestedLocale, streetIndex));
        }

        addWalkSteps(graph, itinerary.legs, legsStates, requestedLocale);

        fixupLegs(itinerary.legs, legsStates);

        itinerary.duration = lastState.getElapsedTimeSeconds();
        itinerary.startTime = makeCalendar(states.get(0));
        itinerary.endTime = makeCalendar(lastState);

        calculateTimes(itinerary, states);

        calculateElevations(itinerary, edges);

        itinerary.traverseDistance = lastState.getTraverseDistanceInMeters();
        itinerary.distanceTraversedInMode = lastState.createDistanceTraversedInModeMap();
        itinerary.timeTraversedInMode = lastState.createTimeTraversedInModeMap();
        itinerary.price = lastState.getTraversalPrice();

        Set<TransitTicket> availableTickets = graph.getAvailableTransitTickets();

        if (Objects.nonNull(availableTickets) && !availableTickets.isEmpty()) {
            List<TransitTripStage> tripStages = generateTransitTripStages(states);
            TransitPriceCalculator transitPriceCalculator = new TransitPriceCalculator();
            transitPriceCalculator.getAvailableTickets().addAll(availableTickets);
            TransitTripCost transitCost = transitPriceCalculator.computePrice(new TransitTripDescription(tripStages));
            itinerary.price = itinerary.price.add(transitCost.getPrice());
            itinerary.transitTickets = transitCost.getTicketNames();
        } else {
            LOG.warn("Skipping transit price calculation for trip from to due to the lack of available tickets");
        }

        itinerary.transfers = lastState.getNumBoardings();
        if (itinerary.transfers > 0 && !(states.get(0).getVertex() instanceof OnboardDepartVertex)) {
            itinerary.transfers--;
        }
        itinerary.itineraryType = generateItineraryType(itinerary.legs);
        itinerary.usedNotRecommendedRoute = path.states.stream().anyMatch(s -> s.usedNotRecommendedRoute);

        String googleMapsURL = "https://www.google.pl/maps/dir/";
        List<Place> places = itinerary.legs.stream().map(leg -> leg.from).collect(Collectors.toList());
        places.add(itinerary.legs.get(itinerary.legs.size() - 1).to);

        googleMapsURL = places.stream().map(place -> "'" + place.lat + "," + place.lon + "'/").reduce(googleMapsURL, (s1, s2) -> s1 + s2);

//        TODO this probably should be removed
        System.out.println(googleMapsURL + "     TODO please remove me");

        return itinerary;
    }

    private static String generateItineraryType(List<Leg> legs) {
        List<String> types = new ArrayList<>();

        if (legs.stream().anyMatch(leg -> leg.mode == TraverseMode.WALK)) {
            types.add(TraverseMode.WALK.toString());
        }

        types.addAll(legs.stream()
                .map(leg -> leg.vehicleDescription)
                .filter(Objects::nonNull)
                .map(VehicleDescription::getVehicleType)
                .map(Objects::toString)
                .distinct()
                .sorted()
                .collect(Collectors.toList()));

        if (legs.stream().anyMatch(leg -> leg.mode.isTransit())) {
            types.add(TraverseMode.TRANSIT.toString());
        }

        return String.join("+", types);
    }

    private static Calendar makeCalendar(State state) {
        RoutingContext rctx = state.getContext();
        TimeZone timeZone = rctx.graph.getTimeZone();
        return makeCalendar(timeZone, state.getTimeInMillis());
    }

    private static Calendar makeCalendar(TimeZone timeZone, long timeMillis) {
        Calendar calendar = Calendar.getInstance(timeZone);
        calendar.setTimeInMillis(timeMillis);
        return calendar;
    }

    /**
     * Generate a {@link CoordinateArrayListSequence} based on an {@link Edge} array.
     *
     * @param edges The array of input edges
     * @return The coordinates of the points on the edges
     */
    public static CoordinateArrayListSequence makeCoordinates(List<Edge> edges) {
        CoordinateArrayListSequence coordinates = new CoordinateArrayListSequence();

        for (Edge edge : edges) {
            LineString geometry = edge.getDisplayGeometry();

            if (geometry != null) {
                if (coordinates.size() == 0) {
                    coordinates.extend(geometry.getCoordinates());
                } else {
                    for (int i = 1; i < geometry.getCoordinates().length; ++i) { // Avoid duplications
                        if (!geometry.getCoordinates()[i].equals(coordinates.getCoordinate(coordinates.size() - 1))) {
                            coordinates.extend(geometry.getCoordinates(), i);
                            break;
                        }

                    }
                }
            }
        }

        return coordinates;
    }

    /**
     * Slice a {@link State} array at the leg boundaries. Leg switches occur when:
     * 1. A LEG_SWITCH mode (which itself isn't part of any leg) is seen
     * 2. The mode changes otherwise, for instance from BICYCLE to WALK
     * 3. A PatternInterlineDwell edge (i.e. interlining) is seen
     *
     * @param states The one-dimensional array of input states
     * @return An array of arrays of states belonging to a single leg (i.e. a two-dimensional array)
     */
    private static List<LegStateSplit> sliceStates(List<State> states) {
        boolean trivial = true;

        for (State state : states) {
            TraverseMode traverseMode = state.getBackMode();

            if (traverseMode != null && traverseMode != TraverseMode.LEG_SWITCH) {
                trivial = false;
                break;
            }
        }

        if (trivial) {
            throw new TrivialPathException();
        }

        // interval legIndexPairs[0], legIndexPairs[1] contains valid states such as WALK, CAR, TRAIN
        // interval legIndexPairs[1], legIndexPairs[2] contains LEG_SWITCH states which separate leg from next leg
        int[] legIndexPairs = {0, states.size() - 1};
        List<int[]> legsIndexes = new ArrayList<>();

        for (int i = 1; i < states.size() - 1; i++) {
            TraverseMode backMode = states.get(i).getBackMode();
            TraverseMode forwardMode = states.get(i + 1).getBackMode();

            if (backMode == null || forwardMode == null) continue;

            Edge edge = states.get(i + 1).getBackEdge();

            if (backMode == TraverseMode.LEG_SWITCH || forwardMode == TraverseMode.LEG_SWITCH) {
                if (backMode != TraverseMode.LEG_SWITCH) {              // Start of leg switch
                    legIndexPairs[1] = i;
                } else if (forwardMode != TraverseMode.LEG_SWITCH) {    // End of leg switch
                    if (legIndexPairs[1] != states.size() - 1) {
                        legsIndexes.add(legIndexPairs);
                    }
                    legIndexPairs = new int[]{i, states.size() - 1};
                }
            } else if (backMode != forwardMode) {                       // Mode change => leg switch
                legIndexPairs[1] = i;
                legsIndexes.add(legIndexPairs);
                legIndexPairs = new int[]{i, states.size() - 1};
            } else if (edge instanceof PatternInterlineDwell) {         // Interlining => leg switch
                legIndexPairs[1] = i;
                legsIndexes.add(legIndexPairs);
                legIndexPairs = new int[]{i + 1, states.size() - 1};
            }
        }

        // Final leg
        legsIndexes.add(legIndexPairs);

        List<LegStateSplit> legsStates = new ArrayList<>();
        // Fill the two-dimensional array with states
        for (int i = 0; i < legsIndexes.size(); ++i) {
            legIndexPairs = legsIndexes.get(i);

            State nextState = Optional.of(i)
                    .filter(it -> it < legsIndexes.size() - 1)
                    .map(it -> legsIndexes.get(it + 1))
                    .map(it -> it[0] + 1) // We are interested with 2nd state in next leg, or rather in edge between 1st and 2nd state
                    .filter(it -> it < states.size())
                    .map(states::get).orElse(null);
            Coordinate coordinate = Optional.ofNullable(nextState)
                    .map(State::getBackEdge)
                    .map(Edge::getDisplayGeometry)
                    .map(LineString::getCoordinates)
                    .filter(it -> it.length != 0)
                    .map(it -> it[0])
                    .orElse(null);

            LegStateSplit legStateSplit = new LegStateSplit(states.subList(legIndexPairs[0], legIndexPairs[1] + 1), coordinate);
            legsStates.add(legStateSplit);
        }

        return legsStates;
    }

    /**
     * Generate one leg of an itinerary from a {@link State} array.
     *
     * @param legStateSplit         Split containing list of states the leg is based on
     * @param showIntermediateStops Whether to include intermediate stops in the leg or not
     * @return The generated leg
     */
    private static Leg generateLeg(Graph graph, LegStateSplit legStateSplit, boolean showIntermediateStops,
                                   boolean disableAlertFiltering, Locale requestedLocale,
                                   StreetVertexIndexService streetIndex) {
        Leg leg = new Leg();
        List<Edge> edges = new ArrayList<>(legStateSplit.getStates().size() - 1);
        List<State> states = legStateSplit.getStates();

        leg.startTime = makeCalendar(states.get(0));
        leg.endTime = makeCalendar(states.get(states.size() - 1));

        // Calculate leg distance and fill array of edges
        leg.distance = 0.0;
        for (int i = 1; i < states.size(); i++) {
            edges.add(states.get(i).getBackEdge());
            leg.distance += edges.get(i - 1).getDistanceInMeters();
        }

        TimeZone timeZone = leg.startTime.getTimeZone();
        leg.agencyTimeZoneOffset = timeZone.getOffset(leg.startTime.getTimeInMillis());

        addTripFields(leg, states, requestedLocale);

        addPlaces(leg, states, edges, showIntermediateStops, requestedLocale, streetIndex);

        addLegGeometryToLeg(leg, edges, legStateSplit);

        leg.interlineWithPreviousLeg = states.get(0).getBackEdge() instanceof PatternInterlineDwell;

        addFrequencyFields(states, leg);

        leg.rentedBike = states.get(0).isBikeRenting() && states.get(states.size() - 1).isBikeRenting();

        addModeAndAlerts(graph, leg, states, disableAlertFiltering, requestedLocale);
        if (leg.isTransitLeg()) addRealTimeData(leg, states);


        leg.vehicleDescription = states.get(0).getCurrentVehicle();
        if (leg.vehicleDescription != null) {
            leg.activePackageIndex = states.get(states.size() - 1).getActivePackageIndex();
        }

        return leg;
    }

    @VisibleForTesting
    static void addLegGeometryToLeg(Leg leg, List<Edge> edges, LegStateSplit legStateSplit) {

        CoordinateArrayListSequence coordinates = makeCoordinates(edges);
        if (legStateSplit.getNextSplitBeginning() != null && !coordinates.getCoordinate(coordinates.size() - 1).equals(legStateSplit.getNextSplitBeginning())) {
            coordinates.add(legStateSplit.getNextSplitBeginning());
        }
        Geometry geometry = GeometryUtils.getGeometryFactory().createLineString(coordinates);

        leg.legGeometry = PolylineEncoder.createEncodings(geometry);
    }

    private static void addFrequencyFields(List<State> states, Leg leg) {
        /* TODO adapt to new frequency handling.
        if (states[0].getBackEdge() instanceof FrequencyBoard) {
            State preBoardState = states[0].getBackState();

            FrequencyBoard fb = (FrequencyBoard) states[0].getBackEdge();
            FrequencyBasedTripPattern pt = fb.getPattern();
            int boardTime;
            if (preBoardState.getServiceDay() == null) {
                boardTime = 0; //TODO why is this happening?
            } else {
                boardTime = preBoardState.getServiceDay().secondsSinceMidnight(
                        preBoardState.getTimeSeconds());
            }
            int period = pt.getPeriod(fb.getStopIndex(), boardTime); //TODO fix

            leg.isNonExactFrequency = !pt.isExact();
            leg.headway = period;
        }
        */
    }

    /**
     * Add a {@link WalkStep} {@link List} to a {@link Leg} {@link List}.
     * It's more convenient to process all legs in one go because the previous step should be kept.
     *
     * @param legs       The legs of the itinerary
     * @param legsStates The states that go with the legs
     */
    private static void addWalkSteps(Graph graph, List<Leg> legs, List<LegStateSplit> legsStates, Locale requestedLocale) {
        WalkStep previousStep = null;

        TraverseMode lastMode = null;

        BikeRentalStationVertex onVertex = null, offVertex = null;

        for (int i = 0; i < legsStates.size(); i++) {
            List<WalkStep> walkSteps = generateWalkSteps(graph, legsStates.get(i).getStates(), previousStep, requestedLocale);

            List<PatternHop> stopEdges = legsStates.get(i).getStates().stream().map(State::getBackEdge).filter(e -> e instanceof PatternHop).map(e -> (PatternHop) e).collect(Collectors.toList());

            //There are some transit stops
            if (!stopEdges.isEmpty()) {
                List<Stop> intermediateStops = stopEdges.stream().map(PatternHop::getBeginStop).collect(Collectors.toList());
                intermediateStops.add(stopEdges.get(stopEdges.size() - 1).getEndStop());
                legs.get(i).intermediateTransitStops = intermediateStops;
            }

            TraverseMode legMode = legs.get(i).mode;
            if (legMode != lastMode && !walkSteps.isEmpty()) {
                walkSteps.get(0).newMode = legMode.toString();
                lastMode = legMode;
            }

            legs.get(i).walkSteps = walkSteps;

            if (walkSteps.size() > 0) {
                previousStep = walkSteps.get(walkSteps.size() - 1);
            } else {
                previousStep = null;
            }
        }
    }

    /**
     * This was originally in TransitUtils.handleBoardAlightType.
     * Edges that always block traversal (forbidden pickups/dropoffs) are simply not ever created.
     */
    public static String getBoardAlightMessage(int boardAlightType) {
        switch (boardAlightType) {
            case 1:
                return "impossible";
            case 2:
                return "mustPhone";
            case 3:
                return "coordinateWithDriver";
            default:
                return null;
        }
    }

    /**
     * Fix up a {@link Leg} {@link List} using the information available at the leg boundaries.
     * This method will fill holes in the arrival and departure times associated with a
     * {@link Place} within a leg and add board and alight rules. It will also ensure that stop
     * names propagate correctly to the non-transit legs that connect to them.
     *
     * @param legs       The legs of the itinerary
     * @param legsStates The states that go with the legs
     */
    private static void fixupLegs(List<Leg> legs, List<LegStateSplit> legsStates) {
        for (int i = 0; i < legsStates.size(); i++) {
            boolean toOther = i + 1 < legsStates.size() && legs.get(i + 1).interlineWithPreviousLeg;
            boolean fromOther = legs.get(i).interlineWithPreviousLeg;
            String boardRule = null;
            String alightRule = null;

            for (int j = 1; j < legsStates.get(i).getStates().size(); j++) {
                if (legsStates.get(i).getStates().get(j).getBackEdge() instanceof PatternEdge) {
                    PatternEdge patternEdge = (PatternEdge) legsStates.get(i).getStates().get(j).getBackEdge();
                    TripPattern tripPattern = patternEdge.getPattern();

                    Integer fromIndex = legs.get(i).from.stopIndex;
                    Integer toIndex = legs.get(i).to.stopIndex;

                    int boardType = (fromIndex != null) ? (tripPattern.getBoardType(fromIndex)) : 0;
                    int alightType = (toIndex != null) ? (tripPattern.getAlightType(toIndex)) : 0;

                    boardRule = getBoardAlightMessage(boardType);
                    alightRule = getBoardAlightMessage(alightType);
                }
                if (legsStates.get(i).getStates().get(j).getBackEdge() instanceof PathwayEdge) {
                    legs.get(i).pathway = true;
                }
            }

            if (i + 1 < legsStates.size()) {
                legs.get(i + 1).from.arrival = legs.get(i).to.arrival;
                legs.get(i).to.departure = legs.get(i + 1).from.departure;

                if (legs.get(i).isTransitLeg() && !legs.get(i + 1).isTransitLeg()) {
                    legs.get(i + 1).from = legs.get(i).to;
                }
                if (!legs.get(i).isTransitLeg() && legs.get(i + 1).isTransitLeg()) {
                    legs.get(i).to = legs.get(i + 1).from;
                }
            }

            if (legs.get(i).isTransitLeg()) {
                if (boardRule != null && !fromOther) {      // If boarding in some other leg
                    legs.get(i).boardRule = boardRule;      // (interline), don't board now.
                }
                if (alightRule != null && !toOther) {       // If alighting in some other
                    legs.get(i).alightRule = alightRule;    // leg, don't alight now.
                }
            }
        }
    }

    /**
     * Calculate the walkTime, transitTime and waitingTime of an {@link Itinerary}.
     *
     * @param itinerary The itinerary to calculate the times for
     * @param states    The states that go with the itinerary
     */
    private static void calculateTimes(Itinerary itinerary, List<State> states) {
        for (State state : states) {
            if (state.getBackMode() == null) continue;

            switch (state.getBackMode()) {
                default:
                    itinerary.transitTime += state.getTimeDeltaSeconds();
                    break;

                case LEG_SWITCH:
                    itinerary.waitingTime += state.getTimeDeltaSeconds();
                    break;

                case WALK:
                case BICYCLE:
                case CAR:
                    itinerary.walkTime += state.getTimeDeltaSeconds();
            }
        }
    }

    /**
     * Calculate the elevationGained and elevationLost fields of an {@link Itinerary}.
     *
     * @param itinerary The itinerary to calculate the elevation changes for
     * @param edges     The edges that go with the itinerary
     */
    private static void calculateElevations(Itinerary itinerary, Edge[] edges) {
        for (Edge edge : edges) {
            if (!(edge instanceof StreetEdge)) continue;

            StreetEdge edgeWithElevation = (StreetEdge) edge;
            PackedCoordinateSequence coordinates = edgeWithElevation.getElevationProfile();

            if (coordinates == null) continue;
            // TODO Check the test below, AFAIU current elevation profile has 3 dimensions.
            if (coordinates.getDimension() != 2) continue;

            for (int i = 0; i < coordinates.size() - 1; i++) {
                double change = coordinates.getOrdinate(i + 1, 1) - coordinates.getOrdinate(i, 1);

                if (change > 0) {
                    itinerary.elevationGained += change;
                } else if (change < 0) {
                    itinerary.elevationLost -= change;
                }
            }
        }
    }

    /**
     * Add mode and alerts fields to a {@link Leg}.
     *
     * @param leg    The leg to add the mode and alerts to
     * @param states The states that go with the leg
     */
    private static void addModeAndAlerts(Graph graph, Leg leg, List<State> states, boolean disableAlertFiltering, Locale requestedLocale) {
        for (State state : states) {
            TraverseMode mode = state.getBackMode();
            Set<Alert> alerts = graph.streetNotesService.getNotes(state);
            Edge edge = state.getBackEdge();

            if (mode != null) {
                leg.mode = mode;
            }

            if (alerts != null) {
                for (Alert alert : alerts) {
                    leg.addAlert(alert, requestedLocale);
                }
            }

            for (AlertPatch alertPatch : graph.getAlertPatches(edge)) {
                if (disableAlertFiltering || alertPatch.displayDuring(state)) {
                    if (alertPatch.hasTrip()) {
                        // If the alert patch contains a trip and that trip match this leg only add the alert for
                        // this leg.
                        if (alertPatch.getTrip().equals(leg.tripId)) {
                            leg.addAlert(alertPatch.getAlert(), requestedLocale);
                        }
                    } else {
                        // If we are not matching a particular trip add all known alerts for this trip pattern.
                        leg.addAlert(alertPatch.getAlert(), requestedLocale);
                    }
                }
            }
        }
    }

    /**
     * Add trip-related fields to a {@link Leg}.
     *
     * @param leg    The leg to add the trip-related fields to
     * @param states The states that go with the leg
     */
    private static void addTripFields(Leg leg, List<State> states, Locale requestedLocale) {
        Trip trip = states.get(states.size() - 1).getBackTrip();

        if (trip != null) {
            Route route = trip.getRoute();
            Agency agency = route.getAgency();
            ServiceDay serviceDay = states.get(states.size() - 1).getServiceDay();

            leg.agencyId = agency.getId();
            leg.agencyName = agency.getName();
            leg.agencyUrl = agency.getUrl();
            leg.agencyBrandingUrl = agency.getBrandingUrl();
            leg.headsign = states.get(1).getBackDirection();
            leg.route = states.get(states.size() - 1).getBackEdge().getName(requestedLocale);
            leg.routeColor = route.getColor();
            leg.routeId = route.getId();
            leg.routeLongName = route.getLongName();
            leg.routeShortName = route.getShortName();
            leg.routeTextColor = route.getTextColor();
            leg.routeType = route.getType();
            leg.routeBrandingUrl = route.getBrandingUrl();
            leg.tripId = trip.getId();
            leg.tripShortName = trip.getTripShortName();
            leg.tripBlockId = trip.getBlockId();
            leg.flexDrtAdvanceBookMin = trip.getDrtAdvanceBookMin();
            leg.flexDrtPickupMessage = trip.getDrtPickupMessage();
            leg.flexDrtDropOffMessage = trip.getDrtDropOffMessage();
            leg.flexFlagStopPickupMessage = trip.getContinuousPickupMessage();
            leg.flexFlagStopDropOffMessage = trip.getContinuousDropOffMessage();

            if (serviceDay != null) {
                leg.serviceDate = serviceDay.getServiceDate().getAsString();
            }

            if (leg.headsign == null) {
                leg.headsign = trip.getTripHeadsign();
            }

            Edge edge = states.get(states.size() - 1).backEdge;
            if (edge instanceof TemporaryDirectPatternHop) {
                leg.callAndRide = true;
            }
            if (edge instanceof PartialPatternHop) {
                PartialPatternHop hop = (PartialPatternHop) edge;
                int directTime = hop.getDirectVehicleTime();
                TripTimes tt = states.get(states.size() - 1).getTripTimes();
                int maxTime = tt.getDemandResponseMaxTime(directTime);
                int avgTime = tt.getDemandResponseAvgTime(directTime);
                int delta = maxTime - avgTime;
                if (directTime != 0 && delta > 0) {
                    if (hop.isDeviatedRouteBoard()) {
                        long maxStartTime = leg.startTime.getTimeInMillis() + (delta * 1000);
                        leg.flexCallAndRideMaxStartTime = makeCalendar(leg.startTime.getTimeZone(), maxStartTime);
                    }
                    if (hop.isDeviatedRouteAlight()) {
                        long minEndTime = leg.endTime.getTimeInMillis() - (delta * 1000);
                        leg.flexCallAndRideMinEndTime = makeCalendar(leg.endTime.getTimeZone(), minEndTime);
                    }
                }
            }

        }
    }

    /**
     * Add {@link Place} fields to a {@link Leg}.
     * There is some code duplication because of subtle differences between departure, arrival and
     * intermediate stops.
     *
     * @param leg                   The leg to add the places to
     * @param states                The states that go with the leg
     * @param edges                 The edges that go with the leg
     * @param showIntermediateStops Whether to include intermediate stops in the leg or not
     */
    private static void addPlaces(Leg leg, List<State> states, List<Edge> edges, boolean showIntermediateStops,
                                  Locale requestedLocale, StreetVertexIndexService streetIndex) {
        Vertex firstVertex = states.get(0).getVertex();
        Vertex lastVertex = states.get(states.size() - 1).getVertex();

        Stop firstStop = firstVertex instanceof TransitVertex ?
                ((TransitVertex) firstVertex).getStop() : null;
        Stop lastStop = lastVertex instanceof TransitVertex ?
                ((TransitVertex) lastVertex).getStop() : null;
        TripTimes tripTimes = states.get(states.size() - 1).getTripTimes();

        leg.from = makePlace(states.get(0), firstVertex, edges.get(0), firstStop, tripTimes, requestedLocale, streetIndex);
        leg.from.arrival = null;
        leg.to = makePlace(states.get(states.size() - 1), lastVertex, null, lastStop, tripTimes, requestedLocale, streetIndex);
        leg.to.departure = null;

        if (showIntermediateStops) {
            leg.stop = new ArrayList<>();

            Stop previousStop = null;
            Stop currentStop;

            for (int i = 1; i < edges.size(); i++) {
                Vertex vertex = states.get(i).getVertex();

                if (!(vertex instanceof TransitVertex)) continue;

                currentStop = ((TransitVertex) vertex).getStop();
                if (currentStop == firstStop) continue;

                if (currentStop == previousStop) {                  // Avoid duplication of stops
                    leg.stop.get(leg.stop.size() - 1).departure = makeCalendar(states.get(i));
                    continue;
                }

                previousStop = currentStop;
                if (currentStop == lastStop) break;

                leg.stop.add(makePlace(states.get(i), vertex, edges.get(i), currentStop, tripTimes, requestedLocale, streetIndex));
            }
        }
    }

    private static List<TransitTripStage> generateTransitTripStages(List<State> states) {
        List<TransitTripStage> transitTripStages = new ArrayList<>();

        int firstTransitFareStartsAt = -1;
        Stop currentStop;

        for (State currentState : states) {
            Vertex vertex = currentState.getVertex();
            int currentTripTime = 1;
            double distance;

            if (vertex instanceof PatternArriveVertex || vertex instanceof PatternDepartVertex) {
                OnboardVertex currentVertex = (OnboardVertex) vertex;

                currentStop = currentVertex.getStop();

                if (vertex instanceof PatternDepartVertex) {
                    if (currentState.getBackState().getVertex() instanceof TransitStopDepart) {
                        //This is the first stop of a new fare
                        distance = 0;
                        if (firstTransitFareStartsAt == -1) {
                            //This is the first transit route in this trip
                            firstTransitFareStartsAt = (int) (currentState.getTimeSeconds() / TimeUnit.MINUTES.toSeconds(1));
                        } else {
                            currentTripTime = (int) ((currentState.getTimeSeconds() / TimeUnit.MINUTES.toSeconds(1)
                                    - firstTransitFareStartsAt)) + 1;
                        }
                        transitTripStages.add(new TransitTripStage(currentVertex.getTripPattern().route,
                                currentStop, currentTripTime, distance));
                    }
                } else {
                    //This is one of the intermediate stops or the last stop for this fare
                    currentTripTime = (int) ((currentState.getTimeSeconds() / TimeUnit.MINUTES.toSeconds(1)
                            - firstTransitFareStartsAt)) + 1; /* +1 as it is first minute after departing from stop */
                    distance = currentState.getBackEdge().getDistanceInMeters();
                    transitTripStages.add(new TransitTripStage(currentVertex.getTripPattern().route,
                            currentStop, currentTripTime, distance));
                }
            }
        }

        return transitTripStages;
    }

    /**
     * Make a {@link Place} to add to a {@link Leg}.
     *
     * @param state     The {@link State} that the {@link Place} pertains to.
     * @param vertex    The {@link Vertex} at the {@link State}.
     * @param edge      The {@link Edge} leading out of the {@link Vertex}.
     * @param stop      The {@link Stop} associated with the {@link Vertex}.
     * @param tripTimes The {@link TripTimes} associated with the {@link Leg}.
     * @return The resulting {@link Place} object.
     */
    private static Place makePlace(State state, Vertex vertex, Edge edge, Stop stop, TripTimes tripTimes,
                                   Locale requestedLocale, StreetVertexIndexService streetIndex) {
        // If no edge was given, it means we're at the end of this leg and need to work around that.
        boolean endOfLeg = (edge == null);

        String name = makeName(vertex, requestedLocale, streetIndex);

        Place place = new Place(vertex.getX(), vertex.getY(), name,
                makeCalendar(state), makeCalendar(state));

        if (endOfLeg) edge = state.getBackEdge();

        if (vertex instanceof TransitVertex && edge instanceof OnboardEdge) {
            place.stopId = stop.getId();
            place.stopCode = stop.getCode();
            place.platformCode = stop.getPlatformCode();
            place.zoneId = stop.getZoneId();
            place.stopIndex = ((OnboardEdge) edge).getStopIndex();
            if (endOfLeg) place.stopIndex++;
            if (tripTimes != null) {
                place.stopSequence = tripTimes.getStopSequence(place.stopIndex);
            }
            place.vertexType = VertexType.TRANSIT;
            place.boardAlightType = BoardAlightType.DEFAULT;
            if (edge instanceof PartialPatternHop) {
                PartialPatternHop hop = (PartialPatternHop) edge;
                if (hop.hasBoardArea() && !endOfLeg) {
                    place.flagStopArea = PolylineEncoder.createEncodings(hop.getBoardArea());
                }
                if (hop.hasAlightArea() && endOfLeg) {
                    place.flagStopArea = PolylineEncoder.createEncodings(hop.getAlightArea());
                }
                if ((endOfLeg && hop.isFlagStopAlight()) || (!endOfLeg && hop.isFlagStopBoard())) {
                    place.boardAlightType = BoardAlightType.FLAG_STOP;
                }
                if ((endOfLeg && hop.isDeviatedRouteAlight()) || (!endOfLeg && hop.isDeviatedRouteBoard())) {
                    place.boardAlightType = BoardAlightType.DEVIATED;
                }
            }
        } else if (vertex instanceof BikeRentalStationVertex) {
            place.bikeShareId = ((BikeRentalStationVertex) vertex).getId();
            LOG.trace("Added bike share Id {} to place", place.bikeShareId);
            place.vertexType = VertexType.BIKESHARE;
        } else if (vertex instanceof BikeParkVertex) {
            place.vertexType = VertexType.BIKEPARK;
        } else {
            place.vertexType = VertexType.NORMAL;
        }

        return place;
    }

    /**
     * We try to generate human-readable (non bogus) name for a given vertex. We do it in this way:
     * 1. We return `ORIGIN` and `DESTINATION` vertices' names ignoring proper street naming
     * 2. We try to return non bogus name from a street or street splitting edges
     * 3. We try to find a closest street with non bogus name via `streetIndex`
     * 4. We give up and return bogus name from a street edge (or `UNNAMED_STREET` if there are no street edges connected)
     */
    private static String makeName(Vertex vertex, Locale locale, StreetVertexIndexService streetIndex) {
        //We use name in TemporaryStreetLocation since this name generation already happened when temporary location was generated
        if (!(vertex instanceof StreetVertex) || vertex instanceof TemporaryStreetLocation) {
            return vertex.getName(locale);
        } else {
            return makeStreetVertexName((StreetVertex) vertex, locale, streetIndex);
        }
    }

    private static String makeStreetVertexName(StreetVertex vertex, Locale locale,
                                               StreetVertexIndexService streetIndex) {
        return vertex.getNonBogusName(locale).map(name -> name.toString(locale))
                .orElseGet(() -> streetIndex.findNameForVertex(vertex).map(name -> name.toString(locale))
                        .orElseGet(() -> vertex.getBogusName(locale).toString(locale)));
    }

    /**
     * Add information about real-time data to a {@link Leg}.
     *
     * @param leg    The leg to add the real-time information to
     * @param states The states that go with the leg
     */
    private static void addRealTimeData(Leg leg, List<State> states) {
        TripTimes tripTimes = states.get(states.size() - 1).getTripTimes();

        if (tripTimes != null && !tripTimes.isScheduled()) {
            leg.realTime = true;
            if (leg.from.stopIndex != null) {
                leg.departureDelay = tripTimes.getDepartureDelay(leg.from.stopIndex);
            }
            leg.arrivalDelay = tripTimes.getArrivalDelay(leg.to.stopIndex);
        }
    }

    /**
     * Converts a list of street edges to a list of turn-by-turn directions.
     *
     * @param previous a non-transit leg that immediately precedes this one (bike-walking, say), or null
     * @return
     */
    public static List<WalkStep> generateWalkSteps(Graph graph, List<State> states, WalkStep previous, Locale requestedLocale) {
        List<WalkStep> steps = new ArrayList<WalkStep>();
        WalkStep step = null;
        double lastAngle = 0, distance = 0; // distance used for appending elevation profiles
        int roundaboutExit = 0; // track whether we are in a roundabout, and if so the exit number
        String roundaboutPreviousStreet = null;

        State onBikeRentalState = null, offBikeRentalState = null;

        // Check if this leg is a SimpleTransfer; if so, rebuild state array based on stored transfer edges
        if (states.size() == 2 && states.get(1).getBackEdge() instanceof SimpleTransfer) {
            SimpleTransfer transferEdge = ((SimpleTransfer) states.get(1).getBackEdge());
            List<Edge> transferEdges = transferEdge.getEdges();
            if (transferEdges != null) {
                // Create a new initial state. Some parameters may have change along the way, copy them from the first state
                StateEditor se = new StateEditor(states.get(0).getOptions(), transferEdges.get(0).getFromVertex());
                se.setNonTransitOptionsFromState(states.get(0));
                State s = se.makeState();
                ArrayList<State> transferStates = new ArrayList<>();
                transferStates.add(s);
                for (Edge e : transferEdges) {
                    s = e.traverse(s);
                    transferStates.add(s);
                }
                states = transferStates;
            }
        }

        for (int i = 0; i < states.size() - 1; i++) {
            State backState = states.get(i);
            State forwardState = states.get(i + 1);
            Edge edge = forwardState.getBackEdge();

            if (edge instanceof RentABikeOnEdge) onBikeRentalState = forwardState;
            if (edge instanceof RentABikeOffEdge) offBikeRentalState = forwardState;
            boolean createdNewStep = false, disableZagRemovalForThisStep = false;
            if (edge instanceof FreeEdge) {
                continue;
            }
            if (forwardState.getBackMode() == null || !forwardState.getBackMode().isOnStreetNonTransit()) {
                continue; // ignore STLs and the like
            }
            Geometry geom = edge.getGeometry();
            if (geom == null) {
                continue;
            }

            // generate a step for getting off an elevator (all
            // elevator narrative generation occurs when alighting). We don't need to know what came
            // before or will come after
            if (edge instanceof ElevatorAlightEdge) {
                // don't care what came before or comes after
                step = createWalkStep(graph, forwardState, requestedLocale);
                createdNewStep = true;
                disableZagRemovalForThisStep = true;

                // tell the user where to get off the elevator using the exit notation, so the
                // i18n interface will say 'Elevator to <exit>'
                // what happens is that the webapp sees name == null and ignores that, and it sees
                // exit != null and uses to <exit>
                // the floor name is the AlightEdge name
                // reset to avoid confusion with 'Elevator on floor 1 to floor 1'
                step.streetName = ((ElevatorAlightEdge) edge).getName(requestedLocale);

                step.relativeDirection = RelativeDirection.ELEVATOR;
                steps.add(step);
                continue;
            }

            String streetName = edge.getName(requestedLocale);
            int idx = streetName.indexOf('(');
            String streetNameNoParens;
            if (idx > 0)
                streetNameNoParens = streetName.substring(0, idx - 1);
            else
                streetNameNoParens = streetName;

            if (step == null) {
                // first step
                step = createWalkStep(graph, forwardState, requestedLocale);
                createdNewStep = true;

                steps.add(step);
                double thisAngle = DirectionUtils.getFirstAngle(geom);
                if (previous == null) {
                    step.setAbsoluteDirection(thisAngle);
                    step.relativeDirection = RelativeDirection.DEPART;
                } else {
                    step.setDirections(previous.angle, thisAngle, false);
                }
                // new step, set distance to length of first edge
                distance = edge.getDistanceInMeters();
            } else if (((step.streetName != null && !step.streetNameNoParens().equals(streetNameNoParens))
                    && (!step.bogusName || !edge.hasBogusName())) ||
                    edge.isRoundabout() != (roundaboutExit > 0) || // went on to or off of a roundabout
                    isLink(edge) && !isLink(backState.getBackEdge())) {
                // Street name has changed, or we've gone on to or off of a roundabout.
                if (roundaboutExit > 0) {
                    // if we were just on a roundabout,
                    // make note of which exit was taken in the existing step
                    step.exit = Integer.toString(roundaboutExit); // ordinal numbers from
                    if (streetNameNoParens.equals(roundaboutPreviousStreet)) {
                        step.stayOn = true;
                    }
                    roundaboutExit = 0;
                }
                /* start a new step */
                step = createWalkStep(graph, forwardState, requestedLocale);
                createdNewStep = true;

                steps.add(step);
                if (edge.isRoundabout()) {
                    // indicate that we are now on a roundabout
                    // and use one-based exit numbering
                    roundaboutExit = 1;
                    roundaboutPreviousStreet = backState.getBackEdge().getName(requestedLocale);
                    idx = roundaboutPreviousStreet.indexOf('(');
                    if (idx > 0)
                        roundaboutPreviousStreet = roundaboutPreviousStreet.substring(0, idx - 1);
                }
                double thisAngle = DirectionUtils.getFirstAngle(geom);
                step.setDirections(lastAngle, thisAngle, edge.isRoundabout());
                // new step, set distance to length of first edge
                distance = edge.getDistanceInMeters();
            } else {
                /* street name has not changed */
                double thisAngle = DirectionUtils.getFirstAngle(geom);
                RelativeDirection direction = WalkStep.getRelativeDirection(lastAngle, thisAngle,
                        edge.isRoundabout());
                boolean optionsBefore = backState.multipleOptionsBefore();
                if (edge.isRoundabout()) {
                    // we are on a roundabout, and have already traversed at least one edge of it.
                    if (optionsBefore) {
                        // increment exit count if we passed one.
                        roundaboutExit += 1;
                    }
                }
                if (edge.isRoundabout() || direction == RelativeDirection.CONTINUE) {
                    // we are continuing almost straight, or continuing along a roundabout.
                    // just append elevation info onto the existing step.

                } else {
                    // we are not on a roundabout, and not continuing straight through.

                    // figure out if there were other plausible turn options at the last
                    // intersection
                    // to see if we should generate a "left to continue" instruction.
                    boolean shouldGenerateContinue = false;
                    if (edge instanceof StreetEdge) {
                        // the next edges will be PlainStreetEdges, we hope
                        double angleDiff = getAbsoluteAngleDiff(thisAngle, lastAngle);
                        for (Edge alternative : backState.getVertex().getOutgoingStreetEdges()) {
                            if (alternative.getName(requestedLocale).equals(streetName)) {
                                // alternatives that have the same name
                                // are usually caused by street splits
                                continue;
                            }
                            double altAngle = DirectionUtils.getFirstAngle(alternative
                                    .getGeometry());
                            double altAngleDiff = getAbsoluteAngleDiff(altAngle, lastAngle);
                            if (angleDiff > Math.PI / 4 || altAngleDiff - angleDiff < Math.PI / 16) {
                                shouldGenerateContinue = true;
                                break;
                            }
                        }
                    } else {
                        double angleDiff = getAbsoluteAngleDiff(lastAngle, thisAngle);
                        // FIXME: this code might be wrong with the removal of the edge-based graph
                        State twoStatesBack = backState.getBackState();
                        Vertex backVertex = twoStatesBack.getVertex();
                        for (Edge alternative : backVertex.getOutgoingStreetEdges()) {
                            List<Edge> alternatives = alternative.getToVertex()
                                    .getOutgoingStreetEdges();
                            if (alternatives.size() == 0) {
                                continue; // this is not an alternative
                            }
                            alternative = alternatives.get(0);
                            if (alternative.getName(requestedLocale).equals(streetName)) {
                                // alternatives that have the same name
                                // are usually caused by street splits
                                continue;
                            }
                            double altAngle = DirectionUtils.getFirstAngle(alternative
                                    .getGeometry());
                            double altAngleDiff = getAbsoluteAngleDiff(altAngle, lastAngle);
                            if (angleDiff > Math.PI / 4 || altAngleDiff - angleDiff < Math.PI / 16) {
                                shouldGenerateContinue = true;
                                break;
                            }
                        }
                    }

                    if (shouldGenerateContinue) {
                        // turn to stay on same-named street
                        step = createWalkStep(graph, forwardState, requestedLocale);
                        createdNewStep = true;
                        steps.add(step);
                        step.setDirections(lastAngle, thisAngle, false);
                        step.stayOn = true;
                        // new step, set distance to length of first edge
                        distance = edge.getDistanceInMeters();
                    }
                }
            }

            State exitState = backState;
            Edge exitEdge = exitState.getBackEdge();
            while (exitEdge instanceof FreeEdge) {
                exitState = exitState.getBackState();
                exitEdge = exitState.getBackEdge();
            }
            if (exitState.getVertex() instanceof ExitVertex) {
                step.exit = ((ExitVertex) exitState.getVertex()).getExitName();
            }

            if (createdNewStep && !disableZagRemovalForThisStep && forwardState.getBackMode() == backState.getBackMode()) {
                //check last three steps for zag
                int last = steps.size() - 1;
                if (last >= 2) {
                    WalkStep threeBack = steps.get(last - 2);
                    WalkStep twoBack = steps.get(last - 1);
                    WalkStep lastStep = steps.get(last);

                    if (twoBack.distance < MAX_ZAG_DISTANCE
                            && lastStep.streetNameNoParens().equals(threeBack.streetNameNoParens())) {

                        if (((lastStep.relativeDirection == RelativeDirection.RIGHT ||
                                lastStep.relativeDirection == RelativeDirection.HARD_RIGHT) &&
                                (twoBack.relativeDirection == RelativeDirection.RIGHT ||
                                        twoBack.relativeDirection == RelativeDirection.HARD_RIGHT)) ||
                                ((lastStep.relativeDirection == RelativeDirection.LEFT ||
                                        lastStep.relativeDirection == RelativeDirection.HARD_LEFT) &&
                                        (twoBack.relativeDirection == RelativeDirection.LEFT ||
                                                twoBack.relativeDirection == RelativeDirection.HARD_LEFT))) {
                            // in this case, we have two left turns or two right turns in quick 
                            // succession; this is probably a U-turn.

                            steps.remove(last - 1);

                            lastStep.distance += twoBack.distance;

                            // A U-turn to the left, typical in the US. 
                            if (lastStep.relativeDirection == RelativeDirection.LEFT ||
                                    lastStep.relativeDirection == RelativeDirection.HARD_LEFT)
                                lastStep.relativeDirection = RelativeDirection.UTURN_LEFT;
                            else
                                lastStep.relativeDirection = RelativeDirection.UTURN_RIGHT;

                            // in this case, we're definitely staying on the same street 
                            // (since it's zag removal, the street names are the same)
                            lastStep.stayOn = true;
                        } else {
                            // What is a zag? TODO write meaningful documentation for this.
                            // It appears to mean simplifying out several rapid turns in succession
                            // from the description.
                            // total hack to remove zags.
                            steps.remove(last);
                            steps.remove(last - 1);
                            step = threeBack;
                            step.distance += twoBack.distance;
                            distance += step.distance;
                            if (twoBack.elevation != null) {
                                if (step.elevation == null) {
                                    step.elevation = twoBack.elevation;
                                } else {
                                    for (P2<Double> d : twoBack.elevation) {
                                        step.elevation.add(new P2<Double>(d.first + step.distance, d.second));
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                if (!createdNewStep && step.elevation != null) {
                    List<P2<Double>> s = encodeElevationProfile(edge, distance,
                            backState.getOptions().geoidElevation ? -graph.ellipsoidToGeoidDifference : 0);
                    if (step.elevation != null && step.elevation.size() > 0) {
                        step.elevation.addAll(s);
                    } else {
                        step.elevation = s;
                    }
                }
                distance += edge.getDistanceInMeters();

            }

            // increment the total length for this step
            step.distance += edge.getDistanceInMeters();
            step.addAlerts(graph.streetNotesService.getNotes(forwardState), requestedLocale);
            lastAngle = DirectionUtils.getLastAngle(geom);

            step.edges.add(edge);
        }

        // add bike rental information if applicable
        if (onBikeRentalState != null && !steps.isEmpty()) {
            steps.get(steps.size() - 1).bikeRentalOnStation =
                    new BikeRentalStationInfo((BikeRentalStationVertex) onBikeRentalState.getBackEdge().getToVertex());
        }
        if (offBikeRentalState != null && !steps.isEmpty()) {
            steps.get(0).bikeRentalOffStation =
                    new BikeRentalStationInfo((BikeRentalStationVertex) offBikeRentalState.getBackEdge().getFromVertex());
        }

        return steps;
    }

    private static boolean isLink(Edge edge) {
        return edge instanceof StreetEdge && (((StreetEdge) edge).getStreetClass() & StreetEdge.CLASS_LINK) == StreetEdge.CLASS_LINK;
    }

    private static double getAbsoluteAngleDiff(double thisAngle, double lastAngle) {
        double angleDiff = thisAngle - lastAngle;
        if (angleDiff < 0) {
            angleDiff += Math.PI * 2;
        }
        double ccwAngleDiff = Math.PI * 2 - angleDiff;
        if (ccwAngleDiff < angleDiff) {
            angleDiff = ccwAngleDiff;
        }
        return angleDiff;
    }

    private static WalkStep createWalkStep(Graph graph, State s, Locale wantedLocale) {
        Edge en = s.getBackEdge();
        WalkStep step;
        step = new WalkStep();
        step.streetName = en.getName(wantedLocale);
        step.lon = en.getFromVertex().getX();
        step.lat = en.getFromVertex().getY();
        step.elevation = encodeElevationProfile(s.getBackEdge(), 0,
                s.getOptions().geoidElevation ? -graph.ellipsoidToGeoidDifference : 0);
        step.bogusName = en.hasBogusName();
        step.addAlerts(graph.streetNotesService.getNotes(s), wantedLocale);
        step.angle = DirectionUtils.getFirstAngle(s.getBackEdge().getGeometry());
        if (s.getBackEdge() instanceof AreaEdge) {
            step.area = true;
        }
        return step;
    }

    private static List<P2<Double>> encodeElevationProfile(Edge edge, double distanceOffset, double heightOffset) {
        if (!(edge instanceof StreetEdge)) {
            return new ArrayList<P2<Double>>();
        }
        StreetEdge elevEdge = (StreetEdge) edge;
        if (elevEdge.getElevationProfile() == null) {
            return new ArrayList<P2<Double>>();
        }
        ArrayList<P2<Double>> out = new ArrayList<P2<Double>>();
        Coordinate[] coordArr = elevEdge.getElevationProfile().toCoordinateArray();
        for (int i = 0; i < coordArr.length; i++) {
            out.add(new P2<Double>(coordArr[i].x + distanceOffset, coordArr[i].y + heightOffset));
        }
        return out;
    }

}
