package org.dice_research.squirrel.queue.scorecalculator;

import org.aksw.jena_sparql_api.core.QueryExecutionFactory;
import org.aksw.jena_sparql_api.http.QueryExecutionFactoryHttp;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.AbstractHttpClient;
import org.apache.http.protocol.HttpContext;
import org.apache.jena.atlas.web.auth.HttpAuthenticator;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.sparql.core.DatasetDescription;
import org.dice_research.squirrel.data.uri.CrawleableUri;
import org.dice_research.squirrel.queue.scorebasedfilter.ScoreBasedUriKeywiseFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

/**
 * Interface for getting score of a Uri.
 * The score is calculated for a {@link CrawleableUri} as 1 / (number of times the Uri occurs as a subject in graphs
 */
public class UriDuplicityScoreCalculator implements IUriScoreCalculator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScoreBasedUriKeywiseFilter.class);

    protected QueryExecutionFactory queryExecFactory = null;

    public UriDuplicityScoreCalculator(QueryExecutionFactory qe) {
        this.queryExecFactory = qe;
    }

    public UriDuplicityScoreCalculator(String sparqlEndpointUrl, String username, String password) {
        if (username != null && password != null) {
            // Create the factory with the credentials
            final Credentials credentials = new UsernamePasswordCredentials(username, password);
            HttpAuthenticator authenticator = new HttpAuthenticator() {
                @Override
                public void invalidate() {
                    // unused method in this implementation
                }

                @Override
                public void apply(AbstractHttpClient client, HttpContext httpContext, URI target) {
                    client.setCredentialsProvider(new CredentialsProvider() {
                        @Override
                        public void clear() {
                            // unused method in this implementation
                        }

                        @Override
                        public Credentials getCredentials(AuthScope scope) {
                            return credentials;
                        }

                        @Override
                        public void setCredentials(AuthScope arg0, Credentials arg1) {
                            LOGGER.error("I am a read-only credential provider but got a call to set credentials.");
                        }
                    });
                }
            };
            this.queryExecFactory = new QueryExecutionFactoryHttp(sparqlEndpointUrl, new DatasetDescription(), authenticator);
        } else {
            this.queryExecFactory = new QueryExecutionFactoryHttp(sparqlEndpointUrl);
        }

    }

    /**
     * This method calculates the duplicity score for a {@link CrawleableUri} which is 1 / (number of times the Uri
     * occurs as a subject in graphs).
     *
     * @param uri the {@link CrawleableUri} for which the duplicity score has to be calculated
     * @return duplicity score
     */
    public float getURIScore(CrawleableUri uri) {
        int uriScore = getSubjectTripleCount(uri.getUri().toString());
        if (uriScore == 0) {
            return 1;
        }
        return 1 / (float) uriScore;
    }

    /**
     * This method return the number of times the Uri occurs as a subject in the graphs.
     *
     * @param uri the Uri for which the score has to calculated
     * @return number of times the Uri occurs as a subject in the graphs
     */
    private int getSubjectTripleCount(String uri) {
        String query = "SELECT (COUNT(*) AS ?C) WHERE { GRAPH ?g { <" + uri + "> ?p ?o } }";
        try (QueryExecution execution = queryExecFactory.createQueryExecution(query)) {
            ResultSet resultSet = execution.execSelect();
            if (resultSet.hasNext()) {
                QuerySolution solution = resultSet.next();
                int count = solution.getLiteral("C").getInt();
                LOGGER.info("getGraphSize(<{}>) = {}", uri, count);
                return count;
            }
        } catch (Exception e) {
            LOGGER.error("Exception occurred while querying Sparql for duplicity of URL", e);
        }
        return 0;
    }
}