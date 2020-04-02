package org.opentripplanner.api.parameter;

import org.geotools.referencing.CRS;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

public class CRSParameter {

    public CoordinateReferenceSystem crs = null;

    public CRSParameter(String name) {
        try {
            crs = CRS.decode(name, true);
        } catch (Exception e) {
            throw new WebApplicationException(onError(name, e));
        }
    }

    protected Response onError(String param, Throwable e) {
        return Response.status(Status.BAD_REQUEST).entity(getErrorMessage(param, e)).build();
    }

    protected String getErrorMessage(String param, Throwable e) {
        return String.format("<H1>400 Bad Request</H1> " +
                        "While parsing parameter %s as %s: <BR> %s",
                param, CoordinateReferenceSystem.class, e.getMessage());
    }

}
