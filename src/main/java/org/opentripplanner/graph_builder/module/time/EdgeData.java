package org.opentripplanner.graph_builder.module.time;

public class EdgeData {

    private long id;
    private int clusterid;

    private long startnodeid;
    private double startlatitude;
    private double startlongitude;

    private long endnodeid;
    private double endlatitude;
    private double endlongitude;

    private long wayid;

    public EdgeData(long id, int clusterid, long startnodeid, double startlatitude, double startlongitude, long endnodeid, double endlatitude, double endlongitude, long wayid) {
        this.id = id;
        this.clusterid = clusterid;
        this.startnodeid = startnodeid;
        this.startlatitude = startlatitude;
        this.startlongitude = startlongitude;
        this.endnodeid = endnodeid;
        this.endlatitude = endlatitude;
        this.endlongitude = endlongitude;
        this.wayid = wayid;
    }

    public void setid(long id) {
        this.id = id;
    }

    public int getclusterid() {
        return clusterid;
    }

    public void setclusterid(int clusterid) {
        this.clusterid = clusterid;
    }

    public long  getstartnodeid() {
        return startnodeid;
    }

    public void setstartnodeid(long startnodeid) {
        this.startnodeid = startnodeid;
    }

    public double getstartlatitude() {
        return startlatitude;
    }

    public long getid() {
        return id;
    }

    public void setstartlatitude(double startlatitude) {
        this.startlatitude = startlatitude;
    }

    public double getstartlongitude() {
        return startlongitude;
    }

    public void setstartlongitude(double startlongitude) {
        this.startlongitude = startlongitude;
    }

    public long getendnodeid() {
        return endnodeid;
    }

    public void setendnodeid(long endnodeid) {
        this.endnodeid = endnodeid;
    }

    public double getendlatitude() {
        return endlatitude;
    }

    public void setendlatitude(double endlatitude) {
        this.endlatitude = endlatitude;
    }

    public double getendlongitude() {
        return endlongitude;
    }

    public void setendlongitude(double endlongitude) {
        this.endlongitude = endlongitude;
    }

    public long getwayid() {
        return wayid;
    }

    public void setwayid(long wayid) {
        this.wayid = wayid;
    }
}
