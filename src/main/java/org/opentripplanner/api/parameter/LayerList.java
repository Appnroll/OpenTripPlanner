package org.opentripplanner.api.parameter;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import java.util.ArrayList;
import java.util.List;

/**
 * WMS allows several layers and styles to be specified. We parse these parameters as if they
 * might contain a comma-separated list, but only use the first one in the WMS resource.
 * This class also uppercases the query parameters to make sure they match enum constants.
 * <p>
 * Type erasure makes a genericized EnumList impractical, so StyleList contains duplicate code.
 */
public class LayerList {
    List<Layer> layers = new ArrayList<Layer>();

    public LayerList(String v) {
        for (String s : v.split(",")) {
            try {
                layers.add(Layer.valueOf(s.toUpperCase()));
            } catch (Exception e) {
                throw new WebApplicationException(Response
                        .status(Status.BAD_REQUEST)
                        .entity("unknown layer name: " + s)
                        .build());
            }
        }
    }

    public Layer get(int index) {
        return layers.get(index);
    }
}

