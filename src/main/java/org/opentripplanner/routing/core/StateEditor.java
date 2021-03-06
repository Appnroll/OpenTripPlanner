package org.opentripplanner.routing.core;

import org.opentripplanner.model.FeedScopedId;
import org.opentripplanner.model.Stop;
import org.opentripplanner.model.Trip;
import org.opentripplanner.routing.algorithm.costs.CostFunction;
import org.opentripplanner.routing.algorithm.profile.OptimizationProfile;
import org.opentripplanner.routing.core.vehicle_sharing.VehicleDescription;
import org.opentripplanner.routing.core.vehicle_sharing.VehiclePricingPackage;
import org.opentripplanner.routing.edgetype.TripPattern;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.trippattern.TripTimes;
import org.opentripplanner.routing.vertextype.TemporaryRentVehicleSplitterVertex;
import org.opentripplanner.routing.vertextype.TemporaryRentVehicleVertex;
import org.opentripplanner.routing.vertextype.TemporaryVertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * This class is a wrapper around a new State that provides it with setter and increment methods,
 * allowing it to be modified before being put to use.
 * <p>
 * By virtue of being in the same package as States, it can modify their package private fields.
 *
 * @author andrewbyrd
 */
public class StateEditor {

    private static final Logger LOG = LoggerFactory.getLogger(StateEditor.class);

    protected State child;

    private boolean extensionsModified = false;

    private boolean spawned = false;

    private boolean defectiveTraversal = false;

    private boolean traversingBackward;

    /* CONSTRUCTORS */

    protected StateEditor() {
    }

    public StateEditor(RoutingRequest options, Vertex v) {
        child = new State(v, options);
    }

    public StateEditor(State parent, Edge e) {
        child = parent.clone();
        child.backState = parent;
        child.backEdge = e;
        child.traversalStatistics = parent.traversalStatistics.copy();
        // We clear child.next here, since it could have already been set in the
        // parent
        child.next = null;
        if (e == null) {
            child.backState = null;
            child.vertex = parent.vertex;
            child.stateData = child.stateData.clone();
        } else {
            // be clever
            // Note that we use equals(), not ==, here to allow for dynamically
            // created vertices
            if (e.getFromVertex().equals(e.getToVertex())
                    && parent.vertex.equals(e.getFromVertex())) {
                // TODO LG: We disable this test: the assumption that
                // the from and to vertex of an edge are not the same
                // is not true anymore: bike rental on/off edges.
                traversingBackward = parent.getOptions().arriveBy;
                child.vertex = e.getToVertex();
            } else if (parent.vertex.equals(e.getFromVertex())) {
                traversingBackward = false;
                child.vertex = e.getToVertex();
            } else if (parent.vertex.equals(e.getToVertex())) {
                traversingBackward = true;
                child.vertex = e.getFromVertex();
            } else {
                // Parent state is not at either end of edge.
                LOG.warn("Edge is not connected to parent state: {}", e);
                LOG.warn("   from   vertex: {}", e.getFromVertex());
                LOG.warn("   to     vertex: {}", e.getToVertex());
                LOG.warn("   parent vertex: {}", parent.vertex);
                defectiveTraversal = true;
            }
            if (traversingBackward != parent.getOptions().arriveBy) {
                LOG.error("Actual traversal direction does not match traversal direction in TraverseOptions.");
                defectiveTraversal = true;
            }
        }
    }

    /* PUBLIC METHODS */

    /**
     * Why can a state editor only be used once? If you modify some component of state with and
     * editor, use the editor to create a new state, and then make more modifications, these
     * modifications will be applied to the previously created state. Reusing the state editor to
     * make several states would modify an existing state somewhere earlier in the search, messing
     * up the shortest path tree.
     */
    public State makeState() {
        // check that this editor has not been used already
        if (spawned)
            throw new IllegalStateException("A StateEditor can only be used once.");

        // if something was flagged incorrect, do not make a new state
        if (defectiveTraversal) {
            LOG.error("Defective traversal flagged on edge " + child.backEdge);
            return null;
        }
        // If too many boardings occured, terminate the search.
        if (child.getNumBoardings() > child.getOptions().maxTransfers + 1)
            return null;

        // Check TemporaryVertex on a different request
        if (isTemporaryVertexFromDifferentRequest()) {
            return null;
        }

        if (child.backState != null) {
            // make it impossible to use a state with lower weight than its
            // parent.
            child.checkNegativeWeight();

            // check that time changes are coherent with edge traversal
            // direction
            if (traversingBackward ? (child.getTimeDeltaSeconds() > 0)
                    : (child.getTimeDeltaSeconds() < 0)) {
                LOG.trace("Time was incremented the wrong direction during state editing. {}",
                        child.backEdge);
                return null;
            }
        }
        spawned = true;
        return child;
    }

    private boolean isTemporaryVertexFromDifferentRequest() {
        Vertex vertex = getVertex();
        return vertex instanceof TemporaryVertex
                && !(vertex instanceof TemporaryRentVehicleSplitterVertex)
                && !(vertex instanceof TemporaryRentVehicleVertex)
                && !child.getOptions().rctx.temporaryVertices.contains(vertex);
    }

    public boolean weHaveWalkedTooFar(RoutingRequest options) {
        return child.traversalStatistics.distanceInWalk >= options.getMaxWalkDistance();
    }

    public boolean isMaxPreTransitTimeExceeded(RoutingRequest options) {
        return child.preTransitTime > options.maxPreTransitTime;
    }

    public String toString() {
        return "<StateEditor " + child + ">";
    }

    /* PUBLIC METHODS TO MODIFY A STATE BEFORE IT IS USED */

    /**
     * Put a new value into the State extensions map. This will always clone the extensions map
     * before it is modified the first time, making sure that other references to the map in earlier
     * States are unaffected.
     */
    @SuppressWarnings("unchecked")
    public void setExtension(Object key, Object value) {
        cloneStateDataAsNeeded();
        if (!extensionsModified) {
            HashMap<Object, Object> newExtensions;
            if (child.stateData.extensions == null)
                newExtensions = new HashMap<Object, Object>(4);
            else
                newExtensions = (HashMap<Object, Object>) child.stateData.extensions.clone();
            child.stateData.extensions = newExtensions;
            extensionsModified = true;
        }
        child.stateData.extensions.put(key, value);
    }

    /**
     * Tell the stateEditor to return null when makeState() is called, no matter what other editing
     * has been done. This allows graph patches to block traversals.
     */
    public void blockTraversal() {
        this.defectiveTraversal = true;
    }

    /* Incrementors */

    /**
     * Increments distance traversed in current traverse mode.
     *
     * @param distance
     */
    public void incrementDistanceTraversedInMode(Double distance) {
        if (distance < 0) {
            LOG.warn("A state's traversed in mode is being incremented by a negative amount while traversing edge ");
            return;
        }
        child.traversalStatistics.increaseDistance(child.stateData.currentTraverseMode, distance);
    }

    /**
     * Increments time spend in current traverse mode.
     *
     * @param timeInSec
     */
    public void incrementTimeTraversedInMode(int timeInSec) {
        if (timeInSec < 0) {
            LOG.warn("A state's traversed in mode is being incremented by a negative amount while traversing edge ");
            return;
        }
        child.traversalStatistics.increaseTime(child.stateData.currentTraverseMode, timeInSec);
    }

    public void incrementWeight(double weight) {
        incrementWeight(CostFunction.CostCategory.ORIGINAL, weight);
    }

    public void incrementWeight(CostFunction.CostCategory category, double weight) {
        OptimizationProfile optimizationProfile = child.getOptions().getOptimizationProfile();
        if (Objects.nonNull(optimizationProfile)) {
            weight *= optimizationProfile.getCostFunction().getCostWeight(category);
        } else {
            //The code below is for backward compatibility with existing tests
            weight *= category == CostFunction.CostCategory.ORIGINAL ? 1 : 0;
        }
        if (Double.isNaN(weight)) {
            LOG.warn("A state's weight is being incremented by NaN while traversing edge "
                    + child.backEdge);
            defectiveTraversal = true;
            return;
        }
        if (weight < 0) {
            LOG.warn("A state's weight is being incremented by a negative amount while traversing edge "
                    + child.backEdge);
            defectiveTraversal = true;
            return;
        }
        child.weight += weight;
    }

    /**
     * Advance or rewind the time of the new state by the given non-negative amount. Direction of
     * time is inferred from the direction of traversal. This is the only element of state that runs
     * backward when traversing backward.
     */
    public void incrementTimeInSeconds(int seconds) {
        incrementTimeInSeconds(seconds, false);
    }

    public void incrementTimeInSeconds(int seconds, boolean beginningVehicleRenting) {
        incrementTimeInMilliseconds(seconds * 1000L);
        incrementTimeTraversedInMode(seconds);
        if (!child.stateData.opt.reverseOptimizing) {
            if (!beginningVehicleRenting && Objects.nonNull(child.getCurrentVehicle())) {
                incrementTimeAssociatedVehiclePrice(seconds);
            } else if (Objects.isNull(child.getCurrentVehicle())) {
                BigDecimal walkPrice = child.getOptions().getWalkPrice().multiply(BigDecimal.valueOf(seconds)
                        .divide(BigDecimal.valueOf(TimeUnit.MINUTES.toSeconds(1)), RoundingMode.UP));
                incrementWeight(CostFunction.CostCategory.PRICE_ASSOCIATED, walkPrice.doubleValue());
            }
        }
    }

    private void incrementTimeAssociatedVehiclePrice(int seconds) {
        child.setTimeTraversedInCurrentVehicleInSeconds(child.getTimeTraversedInCurrentVehicleInSeconds() + seconds);

        int previousActivePackageIndex = child.getActivePackageIndex();
        BigDecimal previousTotalPrice = child.getTotalPriceForCurrentVehicle(previousActivePackageIndex);

        VehiclePricingPackage vehiclePricingPackage = child.getCurrentVehicle().getVehiclePricingPackage(previousActivePackageIndex);
        child.setTimePriceForCurrentVehicle(vehiclePricingPackage.computeTimeAssociatedPrice(
                child.getStartPriceForCurrentVehicle(previousActivePackageIndex),
                child.getTimePriceForCurrentVehicle(previousActivePackageIndex),
                child.getDistancePriceForCurrentVehicle(previousActivePackageIndex),
                child.getTimeTraversedInCurrentVehicleInSeconds()),
                previousActivePackageIndex);

        BigDecimal newLowestTotalPrice = child.getTotalPriceForCurrentVehicle(previousActivePackageIndex);
        int proposedActivePackageIndex = previousActivePackageIndex;
        BigDecimal totalPriceForProposedPackage;

        for (int i = 0; i < child.getCurrentVehicle().getVehiclePricingPackages().size(); i++) {
            if (i != previousActivePackageIndex) {
                vehiclePricingPackage = child.getCurrentVehicle().getVehiclePricingPackage(i);
                child.setTimePriceForCurrentVehicle(vehiclePricingPackage.computeTimeAssociatedPrice(
                        child.getStartPriceForCurrentVehicle(i), child.getTimePriceForCurrentVehicle(i),
                        child.getDistancePriceForCurrentVehicle(i), child.getTimeTraversedInCurrentVehicleInSeconds()),
                        i);
                totalPriceForProposedPackage = child.getTotalPriceForCurrentVehicle(i);
                if (totalPriceForProposedPackage.compareTo(newLowestTotalPrice) < 0) {
                    newLowestTotalPrice = totalPriceForProposedPackage;
                    proposedActivePackageIndex = i;
                }
            }
        }

        assignBestPackage(previousTotalPrice, newLowestTotalPrice, proposedActivePackageIndex);
    }

    private void incrementTimeInMilliseconds(long milliseconds) {
        if (milliseconds < 0) {
            LOG.warn("A state's time is being incremented by a negative amount while traversing edge "
                    + child.getBackEdge());
            defectiveTraversal = true;
            return;
        }
        child.time += (traversingBackward ? -milliseconds : milliseconds);
    }

    public void incrementWalkDistanceInMeters(double length) {
        if (length < 0) {
            LOG.warn("A state's walk distance is being incremented by a negative amount.");
            defectiveTraversal = true;
            return;
        }
        incrementDistanceInCurrentVehicle(length);
        incrementDistanceTraversedInMode(length);
        child.traverseDistanceInMeters += length;
    }

    public void incrementPreTransitTime(int seconds) {
        if (seconds < 0) {
            LOG.warn("A state's pre-transit time is being incremented by a negative amount.");
            defectiveTraversal = true;
            return;
        }
        child.preTransitTime += seconds;
    }

    public void incrementCallAndRideTime(int seconds) {
        if (seconds < 0) {
            LOG.warn("A state's call-n-ride time is being incremented by a negative amount.");
            defectiveTraversal = true;
            return;
        }
        child.callAndRideTime += seconds;
    }

    public void incrementNumBoardings() {
        cloneStateDataAsNeeded();
        child.stateData.numBoardings++;
        setEverBoarded(true);
    }

    /* Basic Setters */

    public void setTripTimes(TripTimes tripTimes) {
        cloneStateDataAsNeeded();
        child.stateData.tripTimes = tripTimes;
    }

    public void setTripId(FeedScopedId tripId) {
        cloneStateDataAsNeeded();
        child.stateData.tripId = tripId;
    }

    public void setPreviousTrip(Trip previousTrip) {
        cloneStateDataAsNeeded();
        child.stateData.previousTrip = previousTrip;
    }

    public void setEnteredNoThroughTrafficArea() {
        child.stateData.enteredNoThroughTrafficArea = true;
    }

    /**
     * Initial wait time is recorded so it can be subtracted out of paths in lieu of "reverse optimization".
     * This happens in Analyst.
     */
    public void setInitialWaitTimeSeconds(long initialWaitTimeSeconds) {
        cloneStateDataAsNeeded();
        child.stateData.initialWaitTime = initialWaitTimeSeconds;
    }

    public void setBackMode(TraverseMode mode) {
        if (mode == child.stateData.backMode)
            return;

        cloneStateDataAsNeeded();
        child.stateData.backMode = mode;
    }

    public void setBackWalkingBike(boolean walkingBike) {
        if (walkingBike == child.stateData.backWalkingBike)
            return;

        cloneStateDataAsNeeded();
        child.stateData.backWalkingBike = walkingBike;
    }

    /**
     * The lastNextArrivalDelta is the amount of time between the arrival of the last trip
     * the planner used and the arrival of the trip after that.
     */
    public void setLastNextArrivalDelta(int lastNextArrivalDelta) {
        cloneStateDataAsNeeded();
        child.stateData.lastNextArrivalDelta = lastNextArrivalDelta;
    }

    public void setTraverseDistance(double traverseDistance) {
        child.traverseDistanceInMeters = traverseDistance;
    }

    public void setPreTransitTime(int preTransitTime) {
        child.preTransitTime = preTransitTime;
    }

    public void setZone(String zone) {
        if (zone == null) {
            if (child.stateData.zone != null) {
                cloneStateDataAsNeeded();
                child.stateData.zone = zone;
            }
        } else if (!zone.equals(child.stateData.zone)) {
            cloneStateDataAsNeeded();
            child.stateData.zone = zone;
        }
    }

    public void setRoute(FeedScopedId routeId) {
        cloneStateDataAsNeeded();
        child.stateData.route = routeId;
        // unlike tripId, routeId is not set to null when alighting
        // but do a null check anyway
        if (routeId != null) {
            FeedScopedId[] oldRouteSequence = child.stateData.routeSequence;
            //LOG.debug("old route seq {}", Arrays.asList(oldRouteSequence));
            int oldLength = oldRouteSequence.length;
            child.stateData.routeSequence = Arrays.copyOf(oldRouteSequence, oldLength + 1);
            child.stateData.routeSequence[oldLength] = routeId;
            //LOG.debug("new route seq {}", Arrays.asList(child.stateData.routeSequence)); // array will be interpreted as varargs
        }
    }

    public void setNumBoardings(int numBoardings) {
        cloneStateDataAsNeeded();
        child.stateData.numBoardings = numBoardings;
    }

    public void setEverBoarded(boolean everBoarded) {
        cloneStateDataAsNeeded();
        child.stateData.everBoarded = true;
    }

    public void beginBikeRenting(TraverseMode vehicleMode) {
        cloneStateDataAsNeeded();
        child.stateData.usingRentedBike = true;
        child.stateData.currentTraverseMode = vehicleMode;
    }

    public void doneBikeRenting() {
        cloneStateDataAsNeeded();
        child.stateData.usingRentedBike = false;
        child.stateData.currentTraverseMode = TraverseMode.WALK;
    }

    public void beginVehicleRenting(VehicleDescription vehicleDescription) {
        cloneStateDataAsNeeded();
//       State::incrementNumBoardings clones state data, that's why ii is not used it here.
        child.stateData.numBoardings++;

        child.stateData.currentTraverseMode = vehicleDescription.getTraverseMode();
        child.stateData.currentVehicle = vehicleDescription;
        child.distanceTraversedInCurrentVehicle = 0;

        int rentingTime = child.getOptions().routingDelays.getRentingTime(vehicleDescription);
        incrementWeight(rentingTime * child.getOptions().routingReluctances.getRentingReluctance()
                + child.getOptions().routingPenalties.getRentingVehiclePenalty());
        incrementTimeInSeconds(rentingTime, true);

        int proposedActivePackageIndex = 0;
        VehiclePricingPackage vehiclePricingPackage = vehicleDescription.getVehiclePricingPackage(proposedActivePackageIndex);
        child.setStartPriceForCurrentVehicle(vehiclePricingPackage.computeStartPrice(), proposedActivePackageIndex);
        BigDecimal newLowestTotalPrice = child.getTotalPriceForCurrentVehicle(proposedActivePackageIndex);
        BigDecimal totalPriceForProposedPackage;
        for (int i = 1; i < vehicleDescription.getVehiclePricingPackages().size(); i++) {
            vehiclePricingPackage = vehicleDescription.getVehiclePricingPackage(i);
            child.setStartPriceForCurrentVehicle(vehiclePricingPackage.computeStartPrice(), i);
            totalPriceForProposedPackage = child.getTotalPriceForCurrentVehicle(i);
            if (totalPriceForProposedPackage.compareTo(newLowestTotalPrice) < 0) {
                newLowestTotalPrice = totalPriceForProposedPackage;
                proposedActivePackageIndex = i;
            }
        }
        this.assignBestPackage(BigDecimal.ZERO, newLowestTotalPrice, proposedActivePackageIndex);
    }

    public void doneVehicleRenting() {
        cloneStateDataAsNeeded();
        int droppingTime = child.getOptions().routingDelays.getDropoffTime(child.getCurrentVehicle());
        incrementTimeInSeconds(droppingTime);
        incrementWeight(droppingTime * child.getOptions().routingReluctances.getRentingReluctance());

        int previousActivePackageIndex = child.getActivePackageIndex();
        VehiclePricingPackage vehiclePricingPackage = child.getCurrentVehicle().getVehiclePricingPackage(previousActivePackageIndex);
        BigDecimal previousTotalPrice = child.getTotalPriceForCurrentVehicle(previousActivePackageIndex);

        BigDecimal newLowestTotalPrice = vehiclePricingPackage.computeFinalPrice(child.getTotalPriceForCurrentVehicle(previousActivePackageIndex));
        int proposedActivePackageIndex = previousActivePackageIndex;
        BigDecimal totalPriceForProposedPackage;

        for (int i = 0; i < child.getCurrentVehicle().getVehiclePricingPackages().size(); i++) {
            if (i != previousActivePackageIndex) {
                vehiclePricingPackage = child.getCurrentVehicle().getVehiclePricingPackage(i);
                totalPriceForProposedPackage = vehiclePricingPackage.computeFinalPrice(child.getTotalPriceForCurrentVehicle(i));
                if (totalPriceForProposedPackage.compareTo(newLowestTotalPrice) < 0) {
                    newLowestTotalPrice = totalPriceForProposedPackage;
                    proposedActivePackageIndex = i;
                }
            }
        }

        assignBestPackage(previousTotalPrice, newLowestTotalPrice, proposedActivePackageIndex);
        child.traversalStatistics.setPrice(child.traversalStatistics.getPrice().add(newLowestTotalPrice));

        child.stateData.currentTraverseMode = TraverseMode.WALK;
        child.stateData.currentVehicle = null;
        child.clearCurrentVehiclePrices();
        child.setTimeTraversedInCurrentVehicleInSeconds(0);
        child.setDistanceTraversedInCurrentVehicle(0);
    }

    public void reversedDoneVehicleRenting(VehicleDescription vehicleDescription) {
        cloneStateDataAsNeeded();
        child.stateData.currentTraverseMode = vehicleDescription.getTraverseMode();
        child.stateData.currentVehicle = vehicleDescription;
        child.distanceTraversedInCurrentVehicle = 0;
        child.setTimeTraversedInCurrentVehicleInSeconds(0);
        int droppingTime = child.getOptions().routingDelays.getDropoffTime(child.getCurrentVehicle());
        incrementTimeInSeconds(droppingTime);
    }

    public void reversedBeginVehicleRenting() {
        cloneStateDataAsNeeded();
        int rentingTime = child.getOptions().routingDelays.getRentingTime(child.stateData.currentVehicle);
        incrementTimeInSeconds(rentingTime);
        child.stateData.currentTraverseMode = TraverseMode.WALK;
        child.stateData.currentVehicle = null;
    }

    /**
     * This has two effects: marks the car as parked, and switches the current mode.
     * Marking the car parked is important for allowing co-dominance of walking and driving states.
     */
    public void setCarParked(boolean carParked) {
        cloneStateDataAsNeeded();
        child.stateData.carParked = carParked;
        if (carParked) {
            // We do not handle mixed-mode P+BIKE...
            child.stateData.currentTraverseMode = TraverseMode.WALK;
        } else {
            child.stateData.currentTraverseMode = TraverseMode.CAR;
        }
    }

    public void setBikeParked(boolean bikeParked) {
        cloneStateDataAsNeeded();
        child.stateData.bikeParked = bikeParked;
        if (bikeParked) {
            child.stateData.currentTraverseMode = TraverseMode.WALK;
        } else {
            child.stateData.currentTraverseMode = TraverseMode.BICYCLE;
        }
    }

    public void setPreviousStop(Stop previousStop) {
        cloneStateDataAsNeeded();
        child.stateData.previousStop = previousStop;
    }

    public void setLastAlightedTimeSeconds(long lastAlightedTimeSeconds) {
        cloneStateDataAsNeeded();
        child.stateData.lastAlightedTime = lastAlightedTimeSeconds;
    }

    public void setTimeSeconds(long seconds) {
        child.time = seconds * 1000;
    }

    public void setStartTimeSeconds(long seconds) {
        cloneStateDataAsNeeded();
        child.stateData.startTime = seconds;
    }

    /**
     * Set non-incremental state values (ex. {@link State#getRoute()}) from an existing state.
     * Incremental values (ex. {@link State#getNumBoardings()}) are not currently set.
     *
     * @param state
     */
    public void setFromState(State state) {
        cloneStateDataAsNeeded();
        child.stateData.route = state.stateData.route;
        child.stateData.tripTimes = state.stateData.tripTimes;
        child.stateData.tripId = state.stateData.tripId;
        child.stateData.serviceDay = state.stateData.serviceDay;
        child.stateData.previousTrip = state.stateData.previousTrip;
        child.stateData.previousStop = state.stateData.previousStop;
        child.stateData.zone = state.stateData.zone;
        child.stateData.extensions = state.stateData.extensions;
        child.stateData.usingRentedBike = state.stateData.usingRentedBike;
        child.stateData.carParked = state.stateData.carParked;
        child.stateData.bikeParked = state.stateData.bikeParked;
    }

    public void setNonTransitOptionsFromState(State state) {
        cloneStateDataAsNeeded();
        child.stateData.currentTraverseMode = state.getNonTransitMode();
        child.stateData.carParked = state.isCarParked();
        child.stateData.bikeParked = state.isBikeParked();
        child.stateData.usingRentedBike = state.isBikeRenting();
    }

    /* PUBLIC GETTER METHODS */

    /*
     * Allow patches to see the State being edited, so they can decide whether to apply their
     * transformations to the traversal result or not.
     */

    public Object getExtension(Object key) {
        return child.getExtension(key);
    }

    public long getTimeSeconds() {
        return child.getTimeSeconds();
    }

    public long getElapsedTimeSeconds() {
        return child.getElapsedTimeSeconds();
    }

    public FeedScopedId getTripId() {
        return child.getTripId();
    }

    public Trip getPreviousTrip() {
        return child.getPreviousTrip();
    }

    public String getZone() {
        return child.getZone();
    }

    public FeedScopedId getRoute() {
        return child.getRoute();
    }

    public int getNumBoardings() {
        return child.getNumBoardings();
    }

    public boolean isEverBoarded() {
        return child.isEverBoarded();
    }

    public boolean isRentingBike() {
        return child.isBikeRenting();
    }

    public long getLastAlightedTimeSeconds() {
        return child.getLastAlightedTimeSeconds();
    }

    public double getTraverseDistance() {
        return child.getTraverseDistanceInMeters();
    }

    public int getPreTransitTime() {
        return child.getPreTransitTime();
    }

    public int getCallAndRideTime() {
        return child.getCallAndRideTime();
    }

    public Vertex getVertex() {
        return child.getVertex();
    }

    /* PRIVATE METHODS */

    /**
     * To be called before modifying anything in the child's StateData. Makes sure that changes are
     * applied to a copy of StateData rather than the same one that is still referenced in existing,
     * older states.
     */
    private void cloneStateDataAsNeeded() {
        if (child.backState != null && child.stateData == child.backState.stateData)
            child.stateData = child.stateData.clone();
    }

    public void alightTransit() {
        cloneStateDataAsNeeded();
        child.stateData.lastTransitWalk = child.getTraverseDistanceInMeters();
    }

    public void setCurrentTraverseMode(TraverseMode mode) {
        cloneStateDataAsNeeded();
        child.stateData.currentTraverseMode = mode;
    }

    public void setLastPattern(TripPattern pattern) {
        cloneStateDataAsNeeded();
        child.stateData.lastPattern = pattern;
    }

    public void setIsLastBoardAlightDeviated(boolean isLastBoardAlightDeviated) {
        cloneStateDataAsNeeded();
        child.stateData.isLastBoardAlightDeviated = isLastBoardAlightDeviated;
    }

    public void setOptions(RoutingRequest options) {
        cloneStateDataAsNeeded();
        child.stateData.opt = options;
    }

    public void setServiceDay(ServiceDay day) {
        cloneStateDataAsNeeded();
        child.stateData.serviceDay = day;
    }

    public void setBikeRentalNetwork(Set<String> networks) {
        cloneStateDataAsNeeded();
        child.stateData.bikeRentalNetworks = networks;
    }

    public boolean hasEnteredNoThroughTrafficArea() {
        return child.hasEnteredNoThruTrafficArea();
    }

    public void setUsedNotRecommendedRoutes() {
        child.usedNotRecommendedRoute = true;
    }

    public boolean usedNotRecommendedRoutes() {
        return child.usedNotRecommendedRoute;
    }

    private void incrementDistanceInCurrentVehicle(double distanceInMeters) {
        if (child.getCurrentVehicle() != null) {
            child.distanceTraversedInCurrentVehicle += distanceInMeters;

            int previousActivePackageIndex = child.getActivePackageIndex();
            BigDecimal previousTotalPrice = child.getTotalPriceForCurrentVehicle(previousActivePackageIndex);

            VehiclePricingPackage vehiclePricingPackage = child.getCurrentVehicle().getVehiclePricingPackage(previousActivePackageIndex);
            child.setDistancePriceForCurrentVehicle(vehiclePricingPackage.computeDistanceAssociatedPrice(
                    child.getStartPriceForCurrentVehicle(previousActivePackageIndex),
                    child.getTimePriceForCurrentVehicle(previousActivePackageIndex),
                    child.getDistancePriceForCurrentVehicle(previousActivePackageIndex),
                    child.distanceTraversedInCurrentVehicle),
                    previousActivePackageIndex);

            BigDecimal newLowestTotalPrice = child.getTotalPriceForCurrentVehicle(previousActivePackageIndex);
            int proposedActivePackageIndex = previousActivePackageIndex;
            BigDecimal totalPriceForProposedPackage;

            for (int i = 0; i < child.getCurrentVehicle().getVehiclePricingPackages().size(); i++) {
                if (i != previousActivePackageIndex) {
                    vehiclePricingPackage = child.getCurrentVehicle().getVehiclePricingPackage(i);
                    child.setDistancePriceForCurrentVehicle(vehiclePricingPackage.computeDistanceAssociatedPrice(
                            child.getStartPriceForCurrentVehicle(i), child.getTimePriceForCurrentVehicle(i),
                            child.getDistancePriceForCurrentVehicle(i), child.distanceTraversedInCurrentVehicle),
                            i);
                    totalPriceForProposedPackage = child.getTotalPriceForCurrentVehicle(i);
                    if (totalPriceForProposedPackage.compareTo(newLowestTotalPrice) < 0) {
                        newLowestTotalPrice = totalPriceForProposedPackage;
                        proposedActivePackageIndex = i;
                    }
                }
            }

            assignBestPackage(previousTotalPrice, newLowestTotalPrice, proposedActivePackageIndex);
        }
    }

    private void assignBestPackage(BigDecimal oldTotalPrice, BigDecimal newTotalPrice, int newActivePackage) {
        if (oldTotalPrice.compareTo(newTotalPrice) > 0) {
            LOG.error("Error while switching between packages due to negative weight increment for " +
                    "request {} and vehicle {}", child.getOptions(), child.getCurrentVehicle());
        }
        child.setActivePackageIndex(newActivePackage);
        incrementWeight(CostFunction.CostCategory.PRICE_ASSOCIATED, newTotalPrice.subtract(oldTotalPrice).doubleValue());
    }
}
