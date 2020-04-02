package org.opentripplanner.routing.edgetype;

import org.locationtech.jts.geom.LineString;
import org.opentripplanner.routing.core.State;
import org.opentripplanner.routing.core.StateEditor;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Vertex;

import java.util.Locale;

/**
 * An edge represents what GTFS calls a timed transfer. This could also be referred to as a
 * synchronized transfer: vehicles at one stop will wait for passengers alighting from another stop,
 * so there are no walking, minimum transfer time, or schedule slack considerations.
 * <p>
 * In fact, our schedule slack and minimum transfer time implementation requires these special edges
 * to allow 'instantaneous' synchronized transfers.
 * <p>
 * A TimedTransferEdge should connect a stop_arrive vertex to a stop_depart vertex, bypassing
 * the preboard and prealight edges that handle the transfer table and schedule slack. The cost of
 * boarding a vehicle should is added in TransitBoardAlight edges, so it is still taken into account.
 *
 * @author andrewbyrd
 */
public class TimedTransferEdge extends Edge {

    private static final long serialVersionUID = 20110730L; // MMMMDDYY

    public TimedTransferEdge(Vertex from, Vertex to) {
        super(from, to);
    }

    @Override
    public State traverse(State s0) {
        StateEditor s1 = s0.edit(this);
        s1.incrementWeight(1);
        s1.setBackMode(TraverseMode.WALK);
        return s1.makeState();
    }

    @Override
    public double getDistanceInMeters() {
        return 0;
    }

    @Override
    public LineString getGeometry() {
        return null;
    }

    @Override
    public String getName() {
        return null;
    }

    @Override
    public String getName(Locale locale) {
        return null;
    }

    public String toString() {
        return "Timed transfer from " + fromv + " to " + tov;
    }
}
