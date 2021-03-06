package org.opentripplanner.pricing.transit;

import org.junit.Test;
import org.opentripplanner.model.Agency;
import org.opentripplanner.model.FeedScopedId;
import org.opentripplanner.model.Route;
import org.opentripplanner.model.Stop;
import org.opentripplanner.pricing.transit.ticket.TransitTicket;
import org.opentripplanner.pricing.transit.ticket.pattern.Pattern;
import org.opentripplanner.pricing.transit.ticket.pattern.StopPattern;
import org.opentripplanner.pricing.transit.trip.model.TransitTripDescription;
import org.opentripplanner.pricing.transit.trip.model.TransitTripStage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class TransitPriceCalculatorTest {

    private final TransitPriceCalculator priceCalculator = new TransitPriceCalculator();

    private final TransitTicket timeLimitedTicket20 = TransitTicket.builder(0, "20-minute", BigDecimal.valueOf(3.4)).setTimeLimit(20).build();
    private final TransitTicket timeLimitedTicket75 = TransitTicket.builder(1, "75-minute", BigDecimal.valueOf(4.4)).setTimeLimit(75).build();
    private final TransitTicket singleFareTicket = TransitTicket.builder(2, "single-fare", BigDecimal.valueOf(3.4)).setFaresNumberLimit(1).build();
    private final TransitTicket timeLimitedTicket90 = TransitTicket.builder(3, "90-minute", BigDecimal.valueOf(7)).setTimeLimit(90).build();
    private final TransitTicket timeLimitedTicketDaily = TransitTicket.builder(4, "daily", BigDecimal.valueOf(15)).setTimeLimit(1440).build();
    private final TransitTicket zoneAOnlyTicket = TransitTicket.builder(5, "zone A only", BigDecimal.valueOf(6)).build();
    private final TransitTicket zone1OnlyTicket = TransitTicket.builder(6, "zone 1 only", BigDecimal.valueOf(6)).build();

    {
        timeLimitedTicket20.addAllowedAgency("ZTM");
        timeLimitedTicket75.addAllowedAgency("ZTM");
        singleFareTicket.addAllowedAgency("ZTM");
        timeLimitedTicket90.addAllowedAgency("ZTM");
        timeLimitedTicketDaily.addAllowedAgency("ZTM");
        zoneAOnlyTicket.addAllowedAgency("ZTM");
        zoneAOnlyTicket.getStopPattern("ZTM").addConstraint(StopPattern.StopAttribute.ZONE, Pattern.TextOperator.IN, "A");
        zone1OnlyTicket.addAllowedAgency("ZTM");
        zone1OnlyTicket.getStopPattern("ZTM").addConstraint(StopPattern.StopAttribute.ZONE, Pattern.TextOperator.IN, "1");
    }

    @Test
    public void shouldReturn75minuteTicketPrice() {
        priceCalculator.getAvailableTickets().add(timeLimitedTicket20);
        priceCalculator.getAvailableTickets().add(timeLimitedTicket75);
        priceCalculator.getAvailableTickets().add(timeLimitedTicket90);
        priceCalculator.getAvailableTickets().add(zoneAOnlyTicket);

        Agency agency = new Agency();
        agency.setId("ZTM");
        Route firstRoute = new Route();
        firstRoute.setId(new FeedScopedId("ZTM", "105"));
        firstRoute.setAgency(agency);
        firstRoute.setShortName("105");
        Route secondRoute = new Route();
        secondRoute.setId(new FeedScopedId("ZTM", "13"));
        secondRoute.setAgency(agency);
        secondRoute.setShortName("13");

        Stop stop1 = new Stop();
        stop1.setZoneId("2");
        stop1.setId(new FeedScopedId());
        Stop stop8 = new Stop();
        stop8.setZoneId("2");
        stop8.setId(new FeedScopedId());
        Stop stop11 = new Stop();
        stop11.setZoneId("2");
        stop11.setId(new FeedScopedId());
        Stop stop16 = new Stop();
        stop16.setZoneId("2");
        stop16.setId(new FeedScopedId());
        Stop stop41 = new Stop();
        stop41.setZoneId("1/2");
        stop41.setId(new FeedScopedId());
        Stop stop47 = new Stop();
        stop47.setZoneId("1");
        stop47.setId(new FeedScopedId());
        Stop stop51 = new Stop();
        stop51.setZoneId("1");
        stop51.setId(new FeedScopedId());

        List<TransitTripStage> tripStages = new ArrayList<>();

        /*

        Routes used in the tested itinerary:

        |  10 min   |5 min|             35 min                  |     Travel time
        |<--------->|<--->|<----------------------------------->|
        0       7   10    15                        40     46   50    Arrive at stop time (minutes)
        |-------|---|-----|-------------------------|------|----|
        |           |     |                                     |
        |   105     |walk |               13                    |     Mean of transport

         */
        tripStages.add(new TransitTripStage(firstRoute, stop1, 1, 0));
        tripStages.add(new TransitTripStage(firstRoute, stop8, 8, 0));
        tripStages.add(new TransitTripStage(firstRoute, stop11, 11, 0));
        tripStages.add(new TransitTripStage(secondRoute, stop16, 16, 0));
        tripStages.add(new TransitTripStage(secondRoute, stop41, 41, 0));
        tripStages.add(new TransitTripStage(secondRoute, stop47, 47, 0));
        tripStages.add(new TransitTripStage(secondRoute, stop51, 51, 0));

        TransitTripDescription tripDescription = new TransitTripDescription(tripStages);

        TransitTripCost transitCost = priceCalculator.computePrice(tripDescription);
        assertEquals(BigDecimal.valueOf(4.4), transitCost.getPrice());
    }

    @Test
    public void shouldReturn3x20minuteTicketPrice() {
        priceCalculator.getAvailableTickets().add(timeLimitedTicket20);
        priceCalculator.getAvailableTickets().add(timeLimitedTicketDaily);

        Agency agency = new Agency();
        agency.setId("ZTM");
        Route firstRoute = new Route();
        firstRoute.setId(new FeedScopedId("ZTM", "105"));
        firstRoute.setShortName("105");
        firstRoute.setAgency(agency);
        Route secondRoute = new Route();
        secondRoute.setId(new FeedScopedId("ZTM", "13"));
        secondRoute.setAgency(agency);
        secondRoute.setShortName("13");

        Stop stop1 = new Stop();
        stop1.setZoneId("2");
        stop1.setId(new FeedScopedId());
        Stop stop8 = new Stop();
        stop8.setZoneId("2");
        stop8.setId(new FeedScopedId());
        Stop stop11 = new Stop();
        stop11.setZoneId("2");
        stop11.setId(new FeedScopedId());
        Stop stop16 = new Stop();
        stop16.setZoneId("2");
        stop16.setId(new FeedScopedId());
        Stop stop41 = new Stop();
        stop41.setZoneId("1/2");
        stop41.setId(new FeedScopedId());
        Stop stop47 = new Stop();
        stop47.setZoneId("1");
        stop47.setId(new FeedScopedId());
        Stop stop51 = new Stop();
        stop51.setZoneId("1");
        stop51.setId(new FeedScopedId());

        List<TransitTripStage> tripStages = new ArrayList<>();

        /*

        Routes used in the tested itinerary:

        |  10 min   |5 min|             35 min                  |     Travel time
        |<--------->|<--->|<----------------------------------->|
        0       7   10    15                        40     46   50    Arrive at stop time (minutes)
        |-------|---|-----|-------------------------|------|----|
        |           |     |                                     |
        |   105     |walk |               13                    |     Mean of transport

         */
        tripStages.add(new TransitTripStage(firstRoute, stop1, 1, 0));
        tripStages.add(new TransitTripStage(firstRoute, stop8, 8, 0));
        tripStages.add(new TransitTripStage(firstRoute, stop11, 11, 0));
        tripStages.add(new TransitTripStage(secondRoute, stop16, 16, 0));
        tripStages.add(new TransitTripStage(secondRoute, stop41, 41, 0));
        tripStages.add(new TransitTripStage(secondRoute, stop47, 47, 0));
        tripStages.add(new TransitTripStage(secondRoute, stop51, 51, 0));

        TransitTripDescription tripDescription = new TransitTripDescription(tripStages);

        TransitTripCost transitCost = priceCalculator.computePrice(tripDescription);

        assertEquals(BigDecimal.valueOf(10.2), transitCost.getPrice());
    }

    @Test
    public void shouldReturn2xSingleFareTicketPrice() {
        priceCalculator.getAvailableTickets().add(timeLimitedTicket20);
        priceCalculator.getAvailableTickets().add(singleFareTicket);
        priceCalculator.getAvailableTickets().add(timeLimitedTicketDaily);

        Agency agency = new Agency();
        agency.setId("ZTM");
        Route firstRoute = new Route();
        firstRoute.setId(new FeedScopedId("ZTM", "105"));
        firstRoute.setShortName("105");
        firstRoute.setAgency(agency);
        Route secondRoute = new Route();
        secondRoute.setId(new FeedScopedId("ZTM", "13"));
        secondRoute.setShortName("13");
        secondRoute.setAgency(agency);

        Stop stop1 = new Stop();
        stop1.setZoneId("2");
        stop1.setId(new FeedScopedId());
        Stop stop8 = new Stop();
        stop8.setZoneId("2");
        stop8.setId(new FeedScopedId());
        Stop stop11 = new Stop();
        stop11.setZoneId("2");
        stop11.setId(new FeedScopedId());
        Stop stop16 = new Stop();
        stop16.setZoneId("2");
        stop16.setId(new FeedScopedId());
        Stop stop41 = new Stop();
        stop41.setZoneId("1/2");
        stop41.setId(new FeedScopedId());
        Stop stop47 = new Stop();
        stop47.setZoneId("1");
        stop47.setId(new FeedScopedId());
        Stop stop51 = new Stop();
        stop51.setZoneId("1");
        stop51.setId(new FeedScopedId());

        List<TransitTripStage> tripStages = new ArrayList<>();

        /*

        Routes used in the tested itinerary:

        |  10 min   |5 min|             35 min                  |     Travel time
        |<--------->|<--->|<----------------------------------->|
        0       7   10    15                        40     46   50    Arrive at stop time (minutes)
        |-------|---|-----|-------------------------|------|----|
        |           |     |                                     |
        |   105     |walk |               13                    |     Mean of transport

         */
        tripStages.add(new TransitTripStage(firstRoute, stop1, 1, 0));
        tripStages.add(new TransitTripStage(firstRoute, stop8, 8, 0));
        tripStages.add(new TransitTripStage(firstRoute, stop11, 11, 0));
        tripStages.add(new TransitTripStage(secondRoute, stop16, 16, 0));
        tripStages.add(new TransitTripStage(secondRoute, stop41, 41, 0));
        tripStages.add(new TransitTripStage(secondRoute, stop47, 47, 0));
        tripStages.add(new TransitTripStage(secondRoute, stop51, 51, 0));

        TransitTripDescription tripDescription = new TransitTripDescription(tripStages);

        TransitTripCost transitCost = priceCalculator.computePrice(tripDescription);

        assertEquals(BigDecimal.valueOf(6.8), transitCost.getPrice());
    }

    @Test
    public void shouldReturn0PriceWhenNoTicketMatchesNoFare() {
        priceCalculator.getAvailableTickets().add(zoneAOnlyTicket);

        Agency agency = new Agency();
        agency.setId("ZTM");
        Route firstRoute = new Route();
        firstRoute.setId(new FeedScopedId("ZTM", "105"));
        firstRoute.setShortName("105");
        firstRoute.setAgency(agency);
        Route secondRoute = new Route();
        secondRoute.setId(new FeedScopedId("ZTM", "13"));
        secondRoute.setShortName("13");
        secondRoute.setAgency(agency);

        Stop stop1 = new Stop();
        stop1.setZoneId("2");
        stop1.setId(new FeedScopedId());
        Stop stop8 = new Stop();
        stop8.setZoneId("2");
        stop8.setId(new FeedScopedId());
        Stop stop11 = new Stop();
        stop11.setZoneId("2");
        stop11.setId(new FeedScopedId());
        Stop stop16 = new Stop();
        stop16.setZoneId("2");
        stop16.setId(new FeedScopedId());
        Stop stop41 = new Stop();
        stop41.setZoneId("1/2");
        stop41.setId(new FeedScopedId());
        Stop stop47 = new Stop();
        stop47.setZoneId("1");
        stop47.setId(new FeedScopedId());
        Stop stop51 = new Stop();
        stop51.setZoneId("1");
        stop51.setId(new FeedScopedId());

        List<TransitTripStage> tripStages = new ArrayList<>();

        /*

        Routes used in the tested itinerary:

        |  10 min   |5 min|             35 min                  |     Travel time
        |<--------->|<--->|<----------------------------------->|
        0       7   10    15                        40     46   50    Arrive at stop time (minutes)
        |-------|---|-----|-------------------------|------|----|
        |           |     |                                     |
        |   105     |walk |               13                    |     Mean of transport

         */
        tripStages.add(new TransitTripStage(firstRoute, stop1, 1, 0));
        tripStages.add(new TransitTripStage(firstRoute, stop8, 8, 0));
        tripStages.add(new TransitTripStage(firstRoute, stop11, 11, 0));
        tripStages.add(new TransitTripStage(secondRoute, stop16, 16, 0));
        tripStages.add(new TransitTripStage(secondRoute, stop41, 41, 0));
        tripStages.add(new TransitTripStage(secondRoute, stop47, 47, 0));
        tripStages.add(new TransitTripStage(secondRoute, stop51, 51, 0));

        TransitTripDescription tripDescription = new TransitTripDescription(tripStages);

        TransitTripCost transitCost = priceCalculator.computePrice(tripDescription);

        assertEquals(BigDecimal.valueOf(-1), transitCost.getPrice());
    }

    @Test
    public void shouldReturn6PriceWhenNoTicketMatchesSomeFare() {
        priceCalculator.getAvailableTickets().add(zone1OnlyTicket);

        Agency agency = new Agency();
        agency.setId("ZTM");
        Route firstRoute = new Route();
        firstRoute.setId(new FeedScopedId("ZTM", "105"));
        firstRoute.setShortName("105");
        firstRoute.setAgency(agency);
        Route secondRoute = new Route();
        secondRoute.setId(new FeedScopedId("ZTM", "13"));
        secondRoute.setShortName("13");
        secondRoute.setAgency(agency);

        Stop stop1 = new Stop();
        stop1.setZoneId("2");
        stop1.setId(new FeedScopedId());
        Stop stop8 = new Stop();
        stop8.setZoneId("2");
        stop8.setId(new FeedScopedId());
        Stop stop11 = new Stop();
        stop11.setZoneId("2");
        stop11.setId(new FeedScopedId());
        Stop stop16 = new Stop();
        stop16.setZoneId("2");
        stop16.setId(new FeedScopedId());
        Stop stop41 = new Stop();
        stop41.setZoneId("1/2");
        stop41.setId(new FeedScopedId());
        Stop stop47 = new Stop();
        stop47.setZoneId("1");
        stop47.setId(new FeedScopedId());
        Stop stop51 = new Stop();
        stop51.setZoneId("1");
        stop51.setId(new FeedScopedId());

        List<TransitTripStage> tripStages = new ArrayList<>();

        /*

        Routes used in the tested itinerary:

        |  10 min   |5 min|             35 min                  |     Travel time
        |<--------->|<--->|<----------------------------------->|
        0       7   10    15                        40     46   50    Arrive at stop time (minutes)
        |-------|---|-----|-------------------------|------|----|
        |           |     |                                     |
        |   105     |walk |               13                    |     Mean of transport

         */
        tripStages.add(new TransitTripStage(firstRoute, stop1, 1, 0));
        tripStages.add(new TransitTripStage(firstRoute, stop8, 8, 0));
        tripStages.add(new TransitTripStage(firstRoute, stop11, 11, 0));
        tripStages.add(new TransitTripStage(secondRoute, stop16, 16, 0));
        tripStages.add(new TransitTripStage(secondRoute, stop41, 41, 0));
        tripStages.add(new TransitTripStage(secondRoute, stop47, 47, 0));
        tripStages.add(new TransitTripStage(secondRoute, stop51, 51, 0));

        TransitTripDescription tripDescription = new TransitTripDescription(tripStages);

        TransitTripCost transitCost = priceCalculator.computePrice(tripDescription);

        assertEquals(BigDecimal.valueOf(-1), transitCost.getPrice());
    }
    @Test
    public void shouldReturnZone1AndGlobalTicket() {
        TransitTicket timeLimitedTicket20 = TransitTicket.builder(0, "20-minute zone 1", BigDecimal.valueOf(1.4)).setTimeLimit(20).build();
        TransitTicket timeLimitedTicket20B = TransitTicket.builder(1, "20-minute global", BigDecimal.valueOf(2)).setTimeLimit(20).build();

        TransitTicket timeLimitedTicket60 = TransitTicket.builder(2, "60-minute", BigDecimal.valueOf(2)).setTimeLimit(60).build();

        timeLimitedTicket20.addAllowedAgency("ZTM");
        timeLimitedTicket20B.addAllowedAgency("ZTM");
        timeLimitedTicket60.addAllowedAgency("ZTM");
        timeLimitedTicket20.getStopPattern("ZTM").addConstraint(StopPattern.StopAttribute.ZONE, Pattern.TextOperator.IN, "1");
        timeLimitedTicket20.getStopPattern("ZTM").addConstraint(StopPattern.StopAttribute.ZONE, Pattern.TextOperator.IN, "1/2");
        timeLimitedTicket60.getStopPattern("ZTM").addConstraint(StopPattern.StopAttribute.ZONE, Pattern.TextOperator.IN, "1");


        priceCalculator.getAvailableTickets().add(timeLimitedTicket60);
        priceCalculator.getAvailableTickets().add(timeLimitedTicket20);
        priceCalculator.getAvailableTickets().add(timeLimitedTicket20B);

        Agency agency = new Agency();
        agency.setId("ZTM");
        Route firstRoute = new Route();
        firstRoute.setId(new FeedScopedId("ZTM", "105"));
        firstRoute.setShortName("105");
        firstRoute.setAgency(agency);

        Stop stop1 = new Stop();
        stop1.setZoneId("2");
        stop1.setId(new FeedScopedId());
        Stop stop11 = new Stop();
        stop11.setZoneId("2");
        stop11.setId(new FeedScopedId());

        Stop stop19 = new Stop();
        stop19.setZoneId("1/2");
        stop19.setId(new FeedScopedId());

        Stop stop28 = new Stop();
        stop28.setZoneId("1");
        stop28.setId(new FeedScopedId());

        List<TransitTripStage> tripStages = new ArrayList<>();

        /*
        Routes used in the tested itinerary:

        |             28 min                    |     Travel time
        |<------------------------------------->|
        0             10      18                27    Arrive at stop time (minutes)
        |-------------|-------|-----------------|
        |                                       |
        |               105                     |     Mean of transport

         */
        tripStages.add(new TransitTripStage(firstRoute, stop1, 1, 0));
        tripStages.add(new TransitTripStage(firstRoute, stop11, 11, 0));
        tripStages.add(new TransitTripStage(firstRoute, stop19, 19, 0));
        tripStages.add(new TransitTripStage(firstRoute, stop28, 28, 0));

        TransitTripDescription tripDescription = new TransitTripDescription(tripStages);

        TransitTripCost transitCost = priceCalculator.computePrice(tripDescription);

        assertEquals(BigDecimal.valueOf(3.4), transitCost.getPrice());
    }
    @Test
    public void shouldReturn60minTicket() {
        TransitTicket timeLimitedTicket20 = TransitTicket.builder(0, "20-minute", BigDecimal.valueOf(1.4)).setTimeLimit(20).build();
        TransitTicket timeLimitedTicket60 = TransitTicket.builder(1, "60-minute", BigDecimal.valueOf(4)).setTimeLimit(60).build();
        timeLimitedTicket20.addAllowedAgency("ZTM");
        timeLimitedTicket60.addAllowedAgency("ZTM");
        priceCalculator.getAvailableTickets().add(timeLimitedTicket60);
        priceCalculator.getAvailableTickets().add(timeLimitedTicket20);

        Agency agency = new Agency();
        agency.setId("ZTM");
        Route firstRoute = new Route();
        firstRoute.setId(new FeedScopedId("ZTM", "105"));
        firstRoute.setShortName("105");
        firstRoute.setAgency(agency);
        Stop stop1 = new Stop();
        stop1.setZoneId("2");
        stop1.setId(new FeedScopedId());
        Stop stop41 = new Stop();
        stop41.setZoneId("2");
        stop41.setId(new FeedScopedId());
        List<TransitTripStage> tripStages = new ArrayList<>();
        /*
        Routes used in the tested itinerary:
        |             41 min                  |     Travel time
        |<----------------------------------->|
        0                                     40    Arrive at stop time (minutes)
        |-------------------------------------|
        |                                     |
        |               13                    |     Mean of transport
         */
        tripStages.add(new TransitTripStage(firstRoute, stop1, 1, 0));
        tripStages.add(new TransitTripStage(firstRoute, stop41, 41, 0));
        TransitTripDescription tripDescription = new TransitTripDescription(tripStages);
        TransitTripCost transitCost = priceCalculator.computePrice(tripDescription);
        assertEquals(BigDecimal.valueOf(4), transitCost.getPrice());
    }

}
