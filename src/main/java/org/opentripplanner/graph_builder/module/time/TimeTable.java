package org.opentripplanner.graph_builder.module.time;


public class TimeTable implements Comparable<QueryData> {

    private int id;
    private long clusterid;
    private int currentspeed;
    private int starttime;
    private int endtime;
    private int daynumber;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public long getClusterid() {
        return clusterid;
    }

    public void setClusterid(long clusterid) {
        this.clusterid = clusterid;
    }

    public int getCurrentspeed() {
        return currentspeed;
    }

    public void setCurrentspeed(int currentspeed) {
        this.currentspeed = currentspeed;
    }

    public double getMetrpersecundSpeed() {
        return this.getCurrentspeed() / 3.6;
    }

    public int getStarttime() {
        return starttime;
    }

    public void setStarttime(int starttime) {
        this.starttime = starttime;
    }

    public int getEndtime() {
        return endtime;
    }

    public void setEndtime(int endtime) {
        this.endtime = endtime;
    }

    public int getDaynumber() {
        return daynumber;
    }

    public void setDaynumber(int daynuiber) {
        this.daynumber = daynuiber;
    }
vv
    public int compareTo(TimeTable o) {
        if (this.getDaynumber() != o.getDaynumber())
            return this.getDaynumber() - o.getDaynumber();
        if (this.starttime != o.starttime)
            return this.starttime - o.starttime;
        return this.endtime - o.endtime;
    }

    @Override
    public int compareTo(QueryData o) {
        if (o.getDay() != this.getDaynumber())
            return this.getDaynumber() - o.getDay();
        if (o.getTime() < this.getStarttime())
            return 1;
        if (o.getTime() < this.getEndtime())
            return 0;
        return -1;
    }
}

