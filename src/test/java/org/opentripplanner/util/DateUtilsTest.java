package org.opentripplanner.util;

import org.junit.Test;

import java.util.Date;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;

public class DateUtilsTest {
    @Test
    public final void testToDate() {
        Date date = DateUtils.toDate("1970-01-01", "00:00", TimeZone.getTimeZone("UTC"));
        assertEquals(0, date.getTime());

        date = DateUtils.toDate(null, "00:00", TimeZone.getTimeZone("UTC"));
        assertEquals(0, date.getTime() % DateUtils.ONE_DAY_MILLI);
    }
}
