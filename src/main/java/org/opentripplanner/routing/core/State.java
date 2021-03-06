package org.opentripplanner.routing.core;

import lombok.Getter;
import org.opentripplanner.model.FeedScopedId;
import org.opentripplanner.model.Stop;
import org.opentripplanner.model.Trip;
import org.opentripplanner.routing.algorithm.NegativeWeightException;
import org.opentripplanner.routing.core.vehicle_sharing.VehicleDescription;
import org.opentripplanner.routing.core.vehicle_sharing.VehicleType;
import org.opentripplanner.routing.edgetype.*;
import org.opentripplanner.routing.edgetype.rentedgetype.DropoffVehicleEdge;
import org.opentripplanner.routing.edgetype.rentedgetype.RentVehicleEdge;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.trippattern.TripTimes;
import org.opentripplanner.routing.vertextype.BikeRentalStationVertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Getter
public class State implements Cloneable {

    /* Data which is likely to change at most traversals */

    protected TraversalStatistics traversalStatistics;

    protected double distanceTraversedInCurrentVehicle;

    private int timeTraversedInCurrentVehicleInSeconds;

    private Map<Integer, BigDecimal> distancePricePerPackage;

    private Map<Integer, BigDecimal> timePricePerPackage;

    private Map<Integer, BigDecimal> startPricePerPackage;

    private Integer activePackageIndex;

    // the current time at this state, in milliseconds
    protected long time;

    // accumulated weight up to this state
    public double weight;

    // associate this state with a vertex in the graph
    protected Vertex vertex;

    // allow path reconstruction from states
    protected State backState;

    public Edge backEdge;

    // allow traverse result chaining (multiple results)
    protected State next;

    /* StateData contains data which is unlikely to change as often */
    public StateData stateData;

    // how far have we walked
    public double traverseDistanceInMeters;

    // The time traveled pre-transit, for park and ride or kiss and ride searches
    int preTransitTime;

    // track the states of all path parsers -- probably changes frequently
    protected int[] pathParserStates;

    int callAndRideTime = 0;

    private static final Logger LOG = LoggerFactory.getLogger(State.class);

    public boolean usedNotRecommendedRoute = false;
    /* CONSTRUCTORS */

    /**
     * Create an initial state representing the beginning of a search for the given routing context.
     * Initial "parent-less" states can only be created at the beginning of a trip. elsewhere, all
     * states must be created from a parent and associated with an edge.
     */
    public State(RoutingRequest opt) {
        this(opt.rctx.origin, opt.rctx.originBackEdge, opt.getSecondsSinceEpoch(), opt);
    }

    /**
     * Create an initial state, forcing vertex to the specified value. Useful for reusing a
     * RoutingContext in TransitIndex, tests, etc.
     */
    public State(Vertex vertex, RoutingRequest opt) {
        // Since you explicitly specify, the vertex, we don't set the backEdge.
        this(vertex, opt.getSecondsSinceEpoch(), opt);
    }

    /**
     * Create an initial state, forcing vertex and time to the specified values. Useful for reusing
     * a RoutingContext in TransitIndex, tests, etc.
     */
    public State(Vertex vertex, long timeSeconds, RoutingRequest options) {
        // Since you explicitly specify, the vertex, we don't set the backEdge.
        this(vertex, null, timeSeconds, options);
    }

    /**
     * Create an initial state, forcing vertex, back edge and time to the specified values. Useful for reusing
     * a RoutingContext in TransitIndex, tests, etc.
     */
    public State(Vertex vertex, Edge backEdge, long timeSeconds, RoutingRequest options) {
        this(vertex, backEdge, timeSeconds, timeSeconds, options);
    }

    /**
     * Create an initial state, forcing vertex, back edge, time and start time to the specified values. Useful for starting
     * a multiple initial state search, for example when propagating profile results to the street network in RoundBasedProfileRouter.
     */
    public State(Vertex vertex, Edge backEdge, long timeSeconds, long startTime, RoutingRequest options) {
        this.weight = 0;
        this.vertex = vertex;
        this.backEdge = backEdge;
        this.backState = null;
        this.stateData = new StateData(options);
        // note that here we are breaking the circular reference between rctx and options
        // this should be harmless since reversed clones are only used when routing has finished
        this.stateData.opt = options;
        this.stateData.startTime = startTime;
        this.stateData.usingRentedBike = false;
        /* If the itinerary is to begin with a car that is left for transit, the initial state of arriveBy searches is
           with the car already "parked" and in WALK mode. Otherwise, we are in CAR mode and "unparked". */
        if (options.parkAndRide) {
            this.stateData.carParked = options.arriveBy;
            this.stateData.currentTraverseMode = this.stateData.carParked ? TraverseMode.WALK : TraverseMode.CAR;
        } else if (options.bike.isBikeParkAndRide()) {
            this.stateData.bikeParked = options.arriveBy;
            this.stateData.currentTraverseMode = this.stateData.bikeParked ? TraverseMode.WALK
                    : TraverseMode.BICYCLE;
        }
        this.traverseDistanceInMeters = 0;
        distancePricePerPackage = new HashMap<>();
        timePricePerPackage = new HashMap<>();
        startPricePerPackage = new HashMap<>();
        activePackageIndex = 0;
        this.preTransitTime = 0;
        this.time = timeSeconds * 1000;
        stateData.routeSequence = new FeedScopedId[0];
        this.traversalStatistics = new TraversalStatistics();
    }

    /**
     * Create a state editor to produce a child of this state, which will be the result of
     * traversing the given edge.
     *
     * @param e
     * @return
     */
    public StateEditor edit(Edge e) {
        return new StateEditor(this, e);
    }

    protected State clone() {
        State ret;
        try {
            ret = (State) super.clone();
            ret.startPricePerPackage = new HashMap<>(this.startPricePerPackage);
            ret.distancePricePerPackage = new HashMap<>(this.distancePricePerPackage);
            ret.timePricePerPackage = new HashMap<>(this.timePricePerPackage);
        } catch (CloneNotSupportedException e1) {
            throw new IllegalStateException("This is not happening");
        }
        return ret;
    }

    /*
     * FIELD ACCESSOR METHODS States are immutable, so they have only get methods. The corresponding
     * set methods are in StateEditor.
     */

    /**
     * Retrieve a State extension based on its key.
     *
     * @param key - An Object that is a key in this State's extension map
     * @return - The extension value for the given key, or null if not present
     */
    public Object getExtension(Object key) {
        if (stateData.extensions == null) {
            return null;
        }
        return stateData.extensions.get(key);
    }

    public String toString() {
        return "<State " + new Date(getTimeInMillis()) + " [" + weight + "] "
                + (isBikeRenting() ? "BIKE_RENT " : "") + (isCarParked() ? "CAR_PARKED " : "")
                + vertex + ">";
    }

    public String toStringVerbose() {
        return "<State " + new Date(getTimeInMillis()) +
                " w=" + this.getWeight() +
                " t=" + this.getElapsedTimeSeconds() +
                " d=" + this.getTraverseDistanceInMeters() +
                " p=" + this.getPreTransitTime() +
                " b=" + this.getNumBoardings() +
                " br=" + this.isBikeRenting() +
                " pr=" + this.isCarParked() + ">";
    }

    /**
     * Returns time in seconds since epoch
     */
    public long getTimeSeconds() {
        return time / 1000;
    }

    /**
     * returns the length of the trip in seconds up to this state
     */
    public long getElapsedTimeSeconds() {
        return Math.abs(getTimeSeconds() - stateData.startTime);
    }

    public TripTimes getTripTimes() {
        return stateData.tripTimes;
    }

    /**
     * Returns the length of the trip in seconds up to this time, not including the initial wait.
     * It subtracts out the initial wait, up to a clamp value specified in the request.
     * If the clamp value is set to -1, no clamping will occur.
     * If the clamp value is set to 0, the initial wait time will not be subtracted out
     * (i.e. it will be clamped to zero).
     * This is used in lieu of reverse optimization in Analyst.
     */
    public long getActiveTime() {
        long clampInitialWait = stateData.opt.clampInitialWait;

        long initialWait = stateData.initialWaitTime;

        // only subtract up the clamp value
        if (clampInitialWait >= 0 && initialWait > clampInitialWait)
            initialWait = clampInitialWait;

        long activeTime = getElapsedTimeSeconds() - initialWait;

        // TODO: what should be done here? (Does this ever happen?)
        if (activeTime < 0) {
            LOG.warn("initial wait was greater than elapsed time.");
            activeTime = getElapsedTimeSeconds();
        }

        return activeTime;
    }

    public FeedScopedId getTripId() {
        return stateData.tripId;
    }

    public Trip getPreviousTrip() {
        return stateData.previousTrip;
    }

    public String getZone() {
        return stateData.zone;
    }

    public FeedScopedId getRoute() {
        return stateData.route;
    }

    public int getNumBoardings() {
        return stateData.numBoardings;
    }


    /**
     * Whether this path has ever previously boarded (or alighted from, in a reverse search) a
     * transit vehicle
     */
    public boolean isEverBoarded() {
        return stateData.everBoarded;
    }

    public boolean isBikeRenting() {
        return stateData.usingRentedBike;
    }

    public boolean isCarParked() {
        return stateData.carParked;
    }

    public boolean isBikeParked() {
        return stateData.bikeParked;
    }

    /**
     * @return True if the state at vertex can be the end of path.
     */
    public boolean isFinal() {
        // When drive-to-transit is enabled, we need to check whether the car has been parked (or whether it has been picked up in reverse).
        boolean parkAndRide = stateData.opt.parkAndRide;
        boolean bikeParkAndRide = stateData.opt.bike.isBikeParkAndRide();
        boolean bikeRentingOk = false;
        boolean bikeParkAndRideOk = false;
        boolean carParkAndRideOk = false;
        if (stateData.opt.arriveBy) {
            bikeRentingOk = !isBikeRenting();
            bikeParkAndRideOk = !bikeParkAndRide || !isBikeParked();
            carParkAndRideOk = !parkAndRide || !isCarParked();
        } else {
            bikeRentingOk = !isBikeRenting();
            bikeParkAndRideOk = !bikeParkAndRide || isBikeParked();
            carParkAndRideOk = !parkAndRide || isCarParked();
        }
        boolean forceTransitTripsOk = !stateData.opt.forceTransitTrips || isEverBoarded();
        return bikeRentingOk && bikeParkAndRideOk && carParkAndRideOk && forceTransitTripsOk && !isCurrentlyRentingVehicle();
    }

    public Stop getPreviousStop() {
        return stateData.previousStop;
    }

    public long getLastAlightedTimeSeconds() {
        return stateData.lastAlightedTime;
    }

    public BigDecimal getTraversalPrice() {
        return traversalStatistics.getPrice();
    }

    public Vertex getVertex() {
        return this.vertex;
    }

    public int getLastNextArrivalDelta() {
        return stateData.lastNextArrivalDelta;
    }

    public double getWeight() {
        return this.weight;
    }

    public int getTimeDeltaSeconds() {
        return backState != null ? (int) (getTimeSeconds() - backState.getTimeSeconds()) : 0;
    }

    public int getAbsTimeDeltaSeconds() {
        return Math.abs(getTimeDeltaSeconds());
    }

    public double getWalkDistanceDelta() {
        if (backState != null)
            return Math.abs(this.traverseDistanceInMeters - backState.traverseDistanceInMeters);
        else
            return 0.0;
    }

    public int getPreTransitTimeDelta() {
        if (backState != null)
            return Math.abs(this.preTransitTime - backState.preTransitTime);
        else
            return 0;
    }

    public double getWeightDelta() {
        return this.weight - backState.weight;
    }

    public void checkNegativeWeight() {
        double dw = this.weight - backState.weight;
        if (dw < 0) {
            throw new NegativeWeightException(String.valueOf(dw) + " on edge " + backEdge);
        }
    }

    public boolean isOnboard() {
        return this.backEdge instanceof OnboardEdge;
    }

    public State getBackState() {
        return this.backState;
    }

    public TraverseMode getBackMode() {
        return stateData.backMode;
    }

    public boolean isBackWalkingBike() {
        return stateData.backWalkingBike;
    }

    /**
     * Get the name of the direction used to get to this state. For transit, it is the headsign,
     * while for other things it is what you would expect.
     */
    public String getBackDirection() {
        // This can happen when stop_headsign says different things at two trips on the same 
        // pattern and at the same stop.
        if (backEdge instanceof TablePatternEdge) {
            return stateData.tripTimes.getHeadsign(((TablePatternEdge) backEdge).getStopIndex());
        } else {
            return backEdge.getDirection();
        }
    }

    /**
     * Get the back trip of the given state. For time dependent transit, State will find the
     * right thing to do.
     */
    public Trip getBackTrip() {
        if (backEdge instanceof TablePatternEdge || backEdge instanceof PatternInterlineDwell) {
            return stateData.tripTimes.trip;
        } else {
            return backEdge.getTrip();
        }
    }

    public Edge getBackEdge() {
        return this.backEdge;
    }

    public boolean exceedsWeightLimit(double maxWeight) {
        return weight > maxWeight;
    }

    public long getStartTimeSeconds() {
        return stateData.startTime;
    }

    /**
     * Optional next result that allows {@link Edge} to return multiple results.
     *
     * @return the next additional result from an edge traversal, or null if no more results
     */
    public State getNextResult() {
        return next;
    }

    /**
     * Extend an exiting result chain by appending this result to the existing chain. The usage
     * model looks like this:
     *
     * <code>
     * TraverseResult result = null;
     * <p>
     * for( ... ) {
     * TraverseResult individualResult = ...;
     * result = individualResult.addToExistingResultChain(result);
     * }
     * <p>
     * return result;
     * </code>
     *
     * @param existingResultChain the tail of an existing result chain, or null if the chain has not
     *                            been started
     * @return
     */
    public State addToExistingResultChain(State existingResultChain) {
        if (this.getNextResult() != null)
            throw new IllegalStateException("this result already has a next result set");
        next = existingResultChain;
        return this;
    }

    public State detachNextResult() {
        State ret = this.next;
        this.next = null;
        return ret;
    }

    public RoutingContext getContext() {
        return stateData.opt.rctx;
    }

    public RoutingRequest getOptions() {
        return stateData.opt;
    }

    /**
     * This method is on State rather than RoutingRequest because we care whether the user is in
     * possession of a rented bike.
     *
     * @return BICYCLE if routing with an owned bicycle, or if at this state the user is holding on
     * to a rented bicycle.
     */
    public TraverseMode getNonTransitMode() {
        return stateData.currentTraverseMode;
    }
    // TODO: There is no documentation about what this means. No one knows precisely.
    // Needs to be replaced with clearly defined fields.

    public State reversedClone() {
        // We no longer compensate for schedule slack (minTransferTime) here.
        // It is distributed symmetrically over all preboard and prealight edges.
        State newState = new State(this.vertex, getTimeSeconds(), stateData.opt.reversedClone());
        newState.stateData.tripTimes = stateData.tripTimes;
        newState.stateData.initialWaitTime = stateData.initialWaitTime;
        // TODO Check if those two lines are needed:
        newState.stateData.usingRentedBike = stateData.usingRentedBike;
        newState.stateData.carParked = stateData.carParked;
        newState.stateData.bikeParked = stateData.bikeParked;
        return newState;
    }

    public void dumpPath() {
        System.out.printf("---- FOLLOWING CHAIN OF STATES ----\n");
        State s = this;
        while (s != null) {
            System.out.printf("%s via %s by %s\n", s, s.backEdge, s.getBackMode());
            s = s.backState;
        }
        System.out.printf("---- END CHAIN OF STATES ----\n");
    }

    public long getTimeInMillis() {
        return time;
    }

    // symmetric prefix check
    public boolean routeSequencePrefix(State that) {
        FeedScopedId[] rs0 = this.stateData.routeSequence;
        FeedScopedId[] rs1 = that.stateData.routeSequence;
        if (rs0 == rs1)
            return true;
        int n = rs0.length < rs1.length ? rs0.length : rs1.length;
        for (int i = 0; i < n; i++)
            if (rs0[i] != rs1[i])
                return false;
        return true;
    }

    // symmetric subset check
    public boolean routeSequenceSubsetSymmetric(State that) {
        FeedScopedId[] rs0 = this.stateData.routeSequence;
        FeedScopedId[] rs1 = that.stateData.routeSequence;
        if (rs0 == rs1)
            return true;
        FeedScopedId[] shorter, longer;
        if (rs0.length < rs1.length) {
            shorter = rs0;
            longer = rs1;
        } else {
            shorter = rs1;
            longer = rs0;
        }
        /* bad complexity, but these are tiny arrays */
        for (FeedScopedId ais : shorter) {
            boolean match = false;
            for (FeedScopedId ail : longer) {
                if (ais == ail) {
                    match = true;
                    break;
                }
            }
            if (!match) return false;
        }
        return true;
    }

    // subset check: is this a subset of that?
    public boolean routeSequenceSubset(State that) {
        FeedScopedId[] rs0 = this.stateData.routeSequence;
        FeedScopedId[] rs1 = that.stateData.routeSequence;
        if (rs0 == rs1) return true;
        if (rs0.length > rs1.length) return false;
        /* bad complexity, but these are tiny arrays */
        for (FeedScopedId r0 : rs0) {
            boolean match = false;
            for (FeedScopedId r1 : rs1) {
                if (r0 == r1) {
                    match = true;
                    break;
                }
            }
            if (!match) return false;
        }
        return true;
    }

    public boolean routeSequenceSuperset(State that) {
        return that.routeSequenceSubset(this);
    }

    public double getWalkSinceLastTransit() {
        return traverseDistanceInMeters - stateData.lastTransitWalk;
    }

    public double getWalkAtLastTransit() {
        return stateData.lastTransitWalk;
    }

    public boolean multipleOptionsBefore() {
        boolean foundAlternatePaths = false;
        TraverseMode requestedMode = getNonTransitMode();
        for (Edge out : backState.vertex.getOutgoing()) {
            if (out == backEdge) {
                continue;
            }
            if (!(out instanceof StreetEdge)) {
                continue;
            }
            State outState = out.traverse(backState);
            if (outState == null) {
                continue;
            }
            if (!outState.getBackMode().equals(requestedMode)) {
                //walking a bike, so, not really an exit
                continue;
            }
            // this section handles the case of an option which is only an option if you walk your
            // bike. It is complicated because you will not need to walk your bike until one
            // edge after the current edge.

            //now, from here, try a continuing path.
            Vertex tov = outState.getVertex();
            boolean found = false;
            for (Edge out2 : tov.getOutgoing()) {
                State outState2 = out2.traverse(outState);
                if (outState2 != null && !outState2.getBackMode().equals(requestedMode)) {
                    // walking a bike, so, not really an exit
                    continue;
                }
                found = true;
                break;
            }
            if (!found) {
                continue;
            }

            // there were paths we didn't take.
            foundAlternatePaths = true;
            break;
        }
        return foundAlternatePaths;
    }

    public String getPathParserStates() {
        StringBuilder sb = new StringBuilder();
        sb.append("( ");
        for (int i : pathParserStates) {
            sb.append(String.format("%02d ", i));
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * @return the last TripPattern used in this path (which is set when leaving the vehicle).
     */
    public TripPattern getLastPattern() {
        return stateData.lastPattern;
    }

    public boolean isLastBoardAlightDeviated() {
        return stateData.isLastBoardAlightDeviated;
    }

    public ServiceDay getServiceDay() {
        return stateData.serviceDay;
    }

    public Set<String> getBikeRentalNetworks() {
        return stateData.bikeRentalNetworks;
    }

    private boolean firstBoardOrLastAlight(State orig, Edge edge) {
        if (edge instanceof TransitBoardAlight && orig.getNumBoardings() == 1) {
            TransitBoardAlight e = (TransitBoardAlight) edge;
            // boarding in a forward main search or alighting in a reverse main search
            return (e.boarding && !stateData.opt.arriveBy) || (!e.boarding && stateData.opt.arriveBy);
        } else {
            return false;
        }
    }

    /**
     * Reverse the path implicit in the given state, re-traversing all edges in the opposite
     * direction so as to remove any unnecessary waiting in the resulting itinerary. This produces a
     * path that passes through all the same edges, but which may have a shorter overall duration
     * due to different weights on time-dependent (e.g. transit boarding) edges. This is the
     * result of combining the functions from GraphPath optimize and reverse.
     *
     * @param forward Is this an on-the-fly reverse search in the midst of a forward search?
     * @return a state at the other end (or this end, in the case of a forward search)
     * of a reversed, optimized path
     */
    public State reverseAndOptimize(boolean forward) {
        State orig = this;
        State ret = orig.reversedClone();
        long newInitialWaitTime = this.stateData.initialWaitTime;

        while (orig.getBackState() != null) {
            Edge edge = orig.getBackEdge();

            // first board/last alight: figure in wait time in on the fly optimization
            if (forward && firstBoardOrLastAlight(orig, edge)) {
                ret = ((TransitBoardAlight) edge).traverse(ret, orig.getBackState().getTimeSeconds());
                newInitialWaitTime = ret.stateData.initialWaitTime;
            } else if (edge instanceof DropoffVehicleEdge && ret.stateData.opt.reverseOptimizing) {
                ret = ((DropoffVehicleEdge) edge).reversedTraverseDoneRenting(ret, orig.getBackState().getCurrentVehicle());
            } else if (edge instanceof RentVehicleEdge && ret.stateData.opt.reverseOptimizing) {
                ret = reverseOptimizeRentVehicleEdge((RentVehicleEdge) edge, ret, orig);
            } else {
                ret = edge.traverse(ret);
            }

//            if (ret != null && ret.getBackMode() != null && orig.getBackMode() != null &&
//                    ret.getBackMode() != orig.getBackMode()) {
//                ret = ret.next; // Keep the mode the same as on the original graph path (in K+R)
//            }

            if (ret == null) {
                LOG.warn("Cannot reverse path at edge: {}, returning unoptimized path. If this edge is a " +
                        "PatternInterlineDwell, or if there is a time-dependent turn restriction here, or if there " +
                        "is no transit leg in a K+R result, this is not totally unexpected. Otherwise, you might " +
                        "want to look into it.", edge);
                return forward ? this : this.reverse();
            }
            orig = orig.getBackState();
        }

        return forward ? forward(ret, newInitialWaitTime) : ret;
    }

    private State reverseOptimizeRentVehicleEdge(RentVehicleEdge edge, State ret, State orig) {
        if (orig.getBackState().isCurrentlyRentingVehicle()) {
            return edge.reversedTraverseSwitchVehicles(ret, orig.getBackState().getCurrentVehicle());
        } else {
            return edge.reversedTraverseBeginRenting(ret);
        }
    }

    /**
     * Reverse the path implicit in the given state. The path will be reversed but will have the same duration.
     *
     * @return a state at the other end of a reversed path
     */
    public State reverse() {
        State orig = this;
        State ret = orig.reversedClone();

        while (orig.getBackState() != null) {
            Edge edge = orig.getBackEdge();

            // Not reverse-optimizing, so we don't re-traverse the edges backward.
            // Instead we just replicate all the states, and replicate the deltas between the state's incremental fields.
            // TODO determine whether this is really necessary, and whether there's a more maintainable way to do this.
            StateEditor editor = ret.edit(edge);
            // note the distinction between setFromState and setBackState
            editor.setFromState(orig);

            editor.incrementTimeInSeconds(orig.getAbsTimeDeltaSeconds());
            editor.incrementWeight(orig.getWeightDelta());
            editor.incrementWalkDistanceInMeters(orig.getWalkDistanceDelta());
            editor.incrementPreTransitTime(orig.getPreTransitTimeDelta());

            // propagate the modes through to the reversed edge
            editor.setBackMode(orig.getBackMode());

            if (orig.isBikeRenting() && !orig.getBackState().isBikeRenting()) {
                editor.doneBikeRenting();
            } else if (!orig.isBikeRenting() && orig.getBackState().isBikeRenting()) {
                editor.beginBikeRenting(((BikeRentalStationVertex) orig.vertex).getVehicleMode());
            }
            if (orig.isCarParked() != orig.getBackState().isCarParked())
                editor.setCarParked(!orig.isCarParked());
            if (orig.isBikeParked() != orig.getBackState().isBikeParked())
                editor.setBikeParked(!orig.isBikeParked());

            editor.setNumBoardings(getNumBoardings() - orig.getNumBoardings());

            ret = editor.makeState();

            //EdgeNarrative origNarrative = orig.getBackEdgeNarrative();
            //EdgeNarrative retNarrative = ret.getBackEdgeNarrative();
            //copyExistingNarrativeToNewNarrativeAsAppropriate(origNarrative, retNarrative);

            orig = orig.getBackState();
        }

        return ret;
    }

    public State forward(State ret, long newInitialWaitTime) {
        State reversed = ret.reverse();
        if (getWeight() <= reversed.getWeight())
            LOG.warn("Optimization did not decrease weight: before " + this.getWeight()
                    + " after " + reversed.getWeight());
        if (getElapsedTimeSeconds() != reversed.getElapsedTimeSeconds())
            LOG.warn("Optimization changed time: before " + this.getElapsedTimeSeconds() + " after "
                    + reversed.getElapsedTimeSeconds());
        if (getActiveTime() <= reversed.getActiveTime())
            // NOTE: this can happen and it isn't always bad (i.e. it doesn't always mean that
            // reverse-opt got called when it shouldn't have). Imagine three lines A, B and C
            // A trip takes line A at 7:00 and arrives at the first transit center at 7:30, where line
            // B is boarded at 7:40 to another transit center with an arrival at 8:00. At 8:30, line C
            // is boarded. Suppose line B runs every ten minutes and the other two run every hour. The
            // optimizer will optimize the B->C connection, moving the trip on line B forward
            // ten minutes. However, it will not be able to move the trip on Line A forward because
            // there is not another possible trip. The waiting time will get pushed towards the
            // the beginning, but not all the way.
            LOG.warn("Optimization did not decrease active time: before "
                    + this.getActiveTime() + " after " + reversed.getActiveTime()
                    + ", boardings: " + this.getNumBoardings());
        if (reversed.getWeight() < this.getBackState().getWeight())
            // This is possible; imagine a trip involving three lines, line A, line B and
            // line C. Lines A and C run hourly while Line B runs every ten minute starting
            // at 8:55. The user boards line A at 7:00 and gets off at the first transfer point
            // (point u) at 8:00. The user then boards the first run of line B at 8:55, an optimal
            // transfer since there is no later trip on line A that could have been taken. The user
            // deboards line B at point v at 10:00, and boards line C at 10:15. This is a
            // non-optimal transfer; the trip on line B can be moved forward 10 minutes. When
            // that happens, the first transfer becomes non-optimal (8:00 to 9:05) and the trip
            // on line A can be moved forward an hour, thus moving 55 minutes of waiting time
            // from a previous state to the beginning of the trip where it is significantly
            // cheaper.
            LOG.warn("Weight has been reduced enough to make it run backwards, now:"
                    + reversed.getWeight() + " backState " + getBackState().getWeight() + ", "
                    + "number of boardings: " + getNumBoardings());
        if (getTimeSeconds() != reversed.getTimeSeconds())
            LOG.warn("Times do not match");
        if (Math.abs(getWeight() - reversed.getWeight()) > 1
                && newInitialWaitTime == stateData.initialWaitTime)
            LOG.warn("Weight is changed (before: " + getWeight() + ", after: "
                    + reversed.getWeight() + "), initial wait times " + "constant at "
                    + newInitialWaitTime);
        if (newInitialWaitTime != reversed.stateData.initialWaitTime)
            LOG.warn("Initial wait time not propagated: is "
                    + reversed.stateData.initialWaitTime + ", should be " + newInitialWaitTime);

        // copy the path parser states so this path is not thrown out going forward
//            reversed.pathParserStates =
//                    Arrays.copyOf(this.pathParserStates, this.pathParserStates.length, newLength);

        // copy things that didn't get copied
        reversed.initializeFieldsFrom(this);
        return reversed;
    }

    /**
     * After reverse-optimizing, many things are not set. Set them from the unoptimized state.
     *
     * @param o The other state to initialize things from.
     */
    private void initializeFieldsFrom(State o) {
        StateData currentStateData = this.stateData;

        // easier to clone and copy back, plus more future proof
        this.stateData = o.stateData.clone();
        this.stateData.initialWaitTime = currentStateData.initialWaitTime;
        // this will get re-set on the next alight (or board in a reverse search)
        this.stateData.lastNextArrivalDelta = -1;
    }

    public boolean getReverseOptimizing() {
        return stateData.opt.reverseOptimizing;
    }

    public double getOptimizedElapsedTimeSeconds() {
        return getElapsedTimeSeconds() - stateData.initialWaitTime;
    }

    public boolean hasEnteredNoThruTrafficArea() {
        return stateData.enteredNoThroughTrafficArea;
    }

    public boolean isCurrentlyRentingVehicle() {
        return stateData.currentVehicle != null;
    }

    public VehicleDescription getCurrentVehicle() {
        return stateData.currentVehicle;
    }

    public VehicleType getCurrentVehicleType() {
        if (stateData.currentVehicle != null)
            return stateData.currentVehicle.getVehicleType();
        else
            return null;
    }

    public double getDistanceInWalk() {
        return traversalStatistics.distanceInWalk;
    }

    public Map<TraverseMode, Double> createDistanceTraversedInModeMap() {
        return traversalStatistics.createDistanceTraversedInModeMap();
    }

    public Map<TraverseMode, Integer> createTimeTraversedInModeMap() {
        return traversalStatistics.createTimeTraversedInModeMap();
    }

    public boolean vehicleHasEnoughRange(double distanceInMeters) {
        if (getCurrentVehicle() != null)
            return distanceTraversedInCurrentVehicle + distanceInMeters <= getCurrentVehicle().getRangeInMeters();
        return true;
    }

    public void setTimeTraversedInCurrentVehicleInSeconds(int timeTraversedInCurrentVehicleInSeconds) {
        this.timeTraversedInCurrentVehicleInSeconds = timeTraversedInCurrentVehicleInSeconds;
    }

    public BigDecimal getDistancePriceForCurrentVehicle(int packageIndex) {
        return distancePricePerPackage.getOrDefault(packageIndex, BigDecimal.ZERO);
    }

    public void setDistancePriceForCurrentVehicle(BigDecimal distancePriceForCurrentVehicle, int packageIndex) {
        this.distancePricePerPackage.put(packageIndex, distancePriceForCurrentVehicle);
    }

    public BigDecimal getTimePriceForCurrentVehicle(int packageIndex) {
        return timePricePerPackage.getOrDefault(packageIndex, BigDecimal.ZERO);
    }

    public void setTimePriceForCurrentVehicle(BigDecimal timePriceForCurrentVehicle, int packageIndex) {
        this.timePricePerPackage.put(packageIndex, timePriceForCurrentVehicle);
    }

    public BigDecimal getStartPriceForCurrentVehicle(int packageIndex) {
        return startPricePerPackage.getOrDefault(packageIndex, BigDecimal.ZERO);
    }

    public void setStartPriceForCurrentVehicle(BigDecimal startPriceForCurrentVehicle, int packageIndex) {
        this.startPricePerPackage.put(packageIndex, startPriceForCurrentVehicle);
    }

    public int getActivePackageIndex() {
        return activePackageIndex;
    }

    public void setActivePackageIndex(int activePackageIndex) {
        this.activePackageIndex = activePackageIndex;
    }

    public void clearCurrentVehiclePrices() {
        this.startPricePerPackage.clear();
        this.timePricePerPackage.clear();
        this.distancePricePerPackage.clear();
    }

    public BigDecimal getTotalPriceForCurrentVehicle(int packageIndex) {
        return this.getTimePriceForCurrentVehicle(packageIndex)
                .add(this.getDistancePriceForCurrentVehicle(packageIndex))
                .add(this.getStartPriceForCurrentVehicle(packageIndex));
    }

    public void setDistanceTraversedInCurrentVehicle(double distanceTraversedInCurrentVehicle) {
        this.distanceTraversedInCurrentVehicle = distanceTraversedInCurrentVehicle;
    }
}