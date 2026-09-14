package com.aisolutions.jobtaskmanagement.repository;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Raw SqlClient repository for m07SystemParameters.
 *
 * Every method is parameterized on a {@link SqlClient}: the caller resolves
 * the company-routed pool once and passes it in.
 *
 * Loads all requested parameters in a single query to avoid multiple
 * round-trips.
 */
@ApplicationScoped
public class SystemParameterRepository {

    private static final Logger LOG = Logger.getLogger(SystemParameterRepository.class);

    /**
     * Fetch multiple parameters at once and return them as a name→value map.
     * Missing keys are absent from the returned map (caller must handle nulls).
     */
    public Uni<Map<String, String>> getParameterMap(SqlClient client, List<String> parameters) {
        String placeholders = parameters.stream().map(p -> "?").collect(Collectors.joining(","));
        Tuple params = Tuple.tuple();
        parameters.forEach(params::addValue);

        return client.preparedQuery(
                "SELECT Parameter, ParameterValue FROM m07SystemParameters WHERE Parameter IN (" + placeholders + ")")
            .execute(params)
            .map(rows -> {
                Map<String, String> map = new HashMap<>();
                rows.forEach(row -> map.put(
                    row.getString("Parameter"),
                    row.getString("ParameterValue") != null ? row.getString("ParameterValue") : ""));
                return map;
            })
            .onFailure().invoke(e -> LOG.errorf(e, "[SystemParameter] getParameterMap error: %s", e.getMessage()));
    }
}
