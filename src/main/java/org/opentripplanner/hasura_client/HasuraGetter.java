package org.opentripplanner.hasura_client;

import com.fasterxml.jackson.core.type.TypeReference;
import org.opentripplanner.hasura_client.hasura_objects.HasuraObject;
import org.opentripplanner.hasura_client.mappers.HasuraToOTPMapper;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.util.HttpUtils;
import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;

import static java.util.Collections.emptyList;

public abstract class HasuraGetter<GRAPH_OBJECT, HASURA_OBJECT extends HasuraObject> {

    private final boolean returnNullOnNoResponse;

    protected abstract String query();

    protected abstract Logger getLogger();

    protected abstract HasuraToOTPMapper<HASURA_OBJECT, GRAPH_OBJECT> mapper();

    protected abstract TypeReference<ApiResponse<HASURA_OBJECT>> hasuraType();

    protected boolean addAdditionalArguments() {
        return true;
    }

    private String getGeolocationArguments(Graph graph) {
        double latMin = graph.getOsmEnvelope().getLowerLeftLatitude();
        double lonMin = graph.getOsmEnvelope().getLowerLeftLongitude();
        double latMax = graph.getOsmEnvelope().getUpperRightLatitude();
        double lonMax = graph.getOsmEnvelope().getUpperRightLongitude();
        return "\"variables\": {" +
                "  \"latMin\": " + latMin + "," +
                "  \"lonMin\": " + lonMin + "," +
                "  \"latMax\": " + latMax + "," +
                "  \"lonMax\": " + lonMax +
                "}}";
    }

    public HasuraGetter() {
        this(false);
    }

    public HasuraGetter(boolean returnNullOnNoResponse) {
        this.returnNullOnNoResponse = returnNullOnNoResponse;
    }

    protected String getAdditionalArguments(Graph graph) {
        return getGeolocationArguments(graph);
    }

    public List<GRAPH_OBJECT> postFromHasura(Graph graph, String url) {
        String arguments = getAdditionalArguments(graph);
        String body = addAdditionalArguments() ? query() + arguments : query();
        ApiResponse<HASURA_OBJECT> response = HttpUtils.postData(url, body, hasuraType());
        getLogger().info("Got {} objects from API", response != null ? response.getData().getItems().size() : "null");
        return Objects.isNull(response) && returnNullOnNoResponse ? null :
                mapper().map(response != null ? response.getData().getItems() : emptyList());
    }

    public List<GRAPH_OBJECT> postFromHasuraWithPassword(Graph graph, String url, String password) {
        String arguments = getAdditionalArguments(graph);
        String body = addAdditionalArguments() ? query() + arguments : query();
        ApiResponse<HASURA_OBJECT> response = HttpUtils.postDataWithPassword(url, body, hasuraType(), password);
        getLogger().info("Got {} objects from API", response != null ? response.getData().getItems().size() : "null");
        return Objects.isNull(response) && returnNullOnNoResponse ? null :
                mapper().map(response != null ? response.getData().getItems() : emptyList());
    }

}
