package org.opentripplanner.prices;

import org.junit.Test;
import org.opentripplanner.IntegrationTest;
import org.opentripplanner.api.model.Itinerary;
import org.opentripplanner.api.resource.Response;

import java.math.BigDecimal;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

public class TransitPricesTest extends IntegrationTest {

    @Test
    public void testTransitPriceComputation() {
        javax.ws.rs.core.Response response = target("/routers/bydgoszcz/plan")
                .queryParam("fromPlace", "53.134802,17.991995")
                .queryParam("toPlace", "53.122338,18.0098715")
                .queryParam("locale", "pl")
                .queryParam("mode", "WALK,TRANSIT")
                .queryParam("startingMode", "WALK")
                .queryParam("softWalkLimit", "false")
                .queryParam("rentingAllowed", "false")
                .queryParam("date", "2020-10-23")
                .request().get();

        Response body = response.readEntity(Response.class);

        assertThat(response.getStatus(), equalTo(200));
        assertThat(body.getPlan().itinerary.size(), equalTo(3));
        Itinerary itinerary = body.getPlan().itinerary.get(0);
        assertEquals(itinerary.price, BigDecimal.valueOf(3));
    }

    @Test
    public void testTransitPriceComputationForMultipleFares() {
        javax.ws.rs.core.Response response = target("/routers/bydgoszcz/plan")
                .queryParam("fromPlace", "53.141000,17.960887")
                .queryParam("toPlace", "53.105895,18.052157")
                .queryParam("locale", "pl")
                .queryParam("mode", "WALK,TRANSIT")
                .queryParam("startingMode", "WALK")
                .queryParam("softWalkLimit", "false")
                .queryParam("rentingAllowed", "false")
                .queryParam("date", "2020-10-23")
                .request().get();

        Response body = response.readEntity(Response.class);

        assertThat(response.getStatus(), equalTo(200));
        assertThat(body.getPlan().itinerary.size(), equalTo(3));
        Itinerary itinerary = body.getPlan().itinerary.get(0);
        assertEquals(itinerary.price, BigDecimal.valueOf(4.2));
    }

}
