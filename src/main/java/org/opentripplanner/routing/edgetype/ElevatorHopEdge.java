package org.opentripplanner.routing.edgetype;

import org.opentripplanner.routing.core.State;
import org.opentripplanner.routing.core.StateEditor;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.core.RoutingRequest;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Vertex;

import com.vividsolutions.jts.geom.LineString;
import java.util.Locale;

/**
 * A relatively low cost edge for travelling one level in an elevator.
 * @author mattwigway
 *
 */
public class ElevatorHopEdge extends Edge implements ElevatorEdge {

    private static final long serialVersionUID = 3925814840369402222L;

    private StreetTraversalPermission permission;

    public boolean wheelchairAccessible = true;

    public ElevatorHopEdge(Vertex from, Vertex to, StreetTraversalPermission permission) {
        super(from, to);
        this.permission = permission;
    }
    
    @Override
    public State traverse(State s0) {
        RoutingRequest options = s0.getOptions();

        if (options.wheelchairAccessible && !wheelchairAccessible) {
            return null;
        }
        
        TraverseMode mode = s0.getNonTransitMode();

        if (mode == TraverseMode.WALK && 
            !permission.allows(StreetTraversalPermission.PEDESTRIAN)) {
            return null;
        }

        if (mode == TraverseMode.BICYCLE && 
            !permission.allows(StreetTraversalPermission.BICYCLE)) {
            return null;
        }
        // there are elevators which allow cars
        if (mode == TraverseMode.CAR
            && !permission.allows(StreetTraversalPermission.CAR)) {
            return null;
        }

        StateEditor s1 = s0.edit(this);
        s1.setBackMode(TraverseMode.WALK);
        s1.incrementWeight(options.elevatorHopCost);
        s1.incrementTimeInSeconds(options.elevatorHopTime);
        return s1.makeState();
    }

    @Override
    public double getDistance() {
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
    
    public String toString() {
        return "ElevatorHopEdge(" + fromv + " -> " + tov + ")";
    }

    @Override
    public String getName(Locale locale) {
        return this.getName();
    }
}
