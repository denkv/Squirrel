package org.dice_research.squirrel.queue.domainbased;

import java.io.IOException;
import java.net.URI;
import java.util.*;

import org.aksw.jena_sparql_api.core.QueryExecutionFactory;
import org.aksw.jena_sparql_api.http.QueryExecutionFactoryHttp;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.AbstractHttpClient;
import org.apache.http.protocol.HttpContext;
import org.apache.jena.atlas.web.auth.HttpAuthenticator;
import org.apache.jena.sparql.core.DatasetDescription;
import org.bson.Document;
import org.bson.types.Binary;
import org.dice_research.squirrel.Constants;
import org.dice_research.squirrel.configurator.MongoConfiguration;
import org.dice_research.squirrel.data.uri.CrawleableUri;
import org.dice_research.squirrel.data.uri.serialize.Serializer;
import org.dice_research.squirrel.data.uri.serialize.java.SnappyJavaUriSerializer;
import org.dice_research.squirrel.queue.AbstractDomainBasedQueue;
import org.dice_research.squirrel.queue.scorebasedfilter.IURIKeywiseFilter;
import org.dice_research.squirrel.queue.scorebasedfilter.URIKeywiseFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;

/**
 * DomainBasedQueue implementation for use with MongoDB
 * <p>
 * * @author Geraldo de Souza Junior (gsjunior@mail.uni-paderborn.de)
 */
public class MongoDBDomainBasedQueue extends AbstractDomainBasedQueue {

    private MongoClient client;
    private MongoDatabase mongoDB;
    private Serializer serializer;
    private final String DB_NAME = "squirrel";
    private final String COLLECTION_QUEUE = "queue";
    private final String COLLECTION_URIS = "uris";
    private IURIKeywiseFilter uriKeywiseFilter;
    @Deprecated
    private final String DEFAULT_TYPE = "default";
    private static final boolean PERSIST = System.getenv("QUEUE_FILTER_PERSIST") == null ? false
        : Boolean.parseBoolean(System.getenv("QUEUE_FILTER_PERSIST"));
    public static final String URI_DOMAIN = "domain";
    private float criticalScore = .2f;
    private int minNumberOfUrisToCheck = 5;

    private static final Logger LOGGER = LoggerFactory.getLogger(MongoDBDomainBasedQueue.class);

    public MongoDBDomainBasedQueue(String hostName, Integer port, Serializer serializer, boolean includeDepth) {
        this.serializer = serializer;

        this.includeDepth = includeDepth;
        if (this.includeDepth)
            LOGGER.info("Depth Persistance Enabled.");

        LOGGER.info("Queue Persistance: " + PERSIST);

        MongoClientOptions.Builder optionsBuilder = MongoClientOptions.builder();
        MongoConfiguration mongoConfiguration = MongoConfiguration.getMDBConfiguration();

        if (mongoConfiguration != null && (mongoConfiguration.getConnectionTimeout() != null && mongoConfiguration.getSocketTimeout() != null
            && mongoConfiguration.getServerTimeout() != null)) {
            optionsBuilder.connectTimeout(mongoConfiguration.getConnectionTimeout());
            optionsBuilder.socketTimeout(mongoConfiguration.getSocketTimeout());
            optionsBuilder.serverSelectionTimeout(mongoConfiguration.getServerTimeout());

            MongoClientOptions options = optionsBuilder.build();

            client = new MongoClient(new ServerAddress(hostName, port), options);

        } else {
            client = new MongoClient(hostName, port);
        }
    }

    public MongoDBDomainBasedQueue(String hostName, Integer port, Serializer serializer, boolean includeDepth, QueryExecutionFactory queryExecFactory) {
        this(hostName, port, serializer, includeDepth);
        this.uriKeywiseFilter = new URIKeywiseFilter(queryExecFactory);
    }

    public MongoDBDomainBasedQueue(String hostName, Integer port, Serializer serializer, boolean includeDepth, String sparqlEndpointUrl, String username, String password) {
        this(hostName, port, serializer, includeDepth);

        QueryExecutionFactory qef;
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
            qef = new QueryExecutionFactoryHttp(sparqlEndpointUrl, new DatasetDescription(), authenticator);
        } else {
            qef = new QueryExecutionFactoryHttp(sparqlEndpointUrl);
        }

        this.uriKeywiseFilter = new URIKeywiseFilter(qef);
    }

    public MongoDBDomainBasedQueue(String hostName, Integer port, boolean includeDepth) {
        this(hostName, port, new SnappyJavaUriSerializer(), includeDepth);
    }

    public MongoDBDomainBasedQueue(String hostName, Integer port,boolean includeDepth,QueryExecutionFactory queryExecFactory) {
        this(hostName,port, new SnappyJavaUriSerializer(),includeDepth);
        this.uriKeywiseFilter = new URIKeywiseFilter(queryExecFactory);
    }

    public void purge() {
        mongoDB.getCollection(COLLECTION_QUEUE).drop();
        mongoDB.getCollection(COLLECTION_URIS).drop();
    }

    public long length() {
        return mongoDB.getCollection(COLLECTION_QUEUE).count();
    }

    @Override
    public void close() {
        if (!PERSIST) {
            mongoDB.getCollection(COLLECTION_QUEUE).drop();
            mongoDB.getCollection(COLLECTION_URIS).drop();
        }
        client.close();
    }

    @Override
    protected void addUri(CrawleableUri uri, String domain) {
        addDomain(domain);
        // default score taken as 1
        addCrawleableUri(uri, domain, 1);
    }

    protected void addCrawleableUri(CrawleableUri uri, String domain, float score) {
        try {
            Document uriDoc = getUriDocument(uri, domain, score);
            // If the document does not already exist, add it
            if (mongoDB.getCollection(COLLECTION_URIS).find(uriDoc).first() == null) {
                mongoDB.getCollection(COLLECTION_URIS).insertOne(uriDoc);
            }
        } catch (Exception e) {
            LOGGER.error("Error while adding uri to MongoDBQueue", e);
        }
    }

    protected void addDomain(String domain) {
        try {
            Document domainDoc = getDomainDocument(domain);
            if (!containsDomain(domainDoc)) {
                LOGGER.debug("Domain is not in the queue, creating a new one for {}", domain);
                mongoDB.getCollection(COLLECTION_QUEUE).insertOne(domainDoc);
            } else {
                LOGGER.debug("Domain is already in the queue: {}", domain);
            }
        } catch (MongoWriteException e) {
            LOGGER.error("Domain: " + domain + " couldn't be added to the queue. Ignoring...");
        }
    }

    public Document getUriDocument(CrawleableUri uri, String domain, float score) {
        byte[] suri = null;

        try {
            suri = serializer.serialize(uri);
        } catch (IOException e) {
            LOGGER.error("Couldn't serialize URI. Returning null.", e);
            return null;
        }

        Document docUri = new Document();
        docUri.put("_id", uri.getUri().hashCode());
        docUri.put(URI_DOMAIN, domain);
        docUri.put("type", DEFAULT_TYPE);
        docUri.put("uri", new Binary(suri));
        docUri.put(Constants.URI_SCORE, score);
        return docUri;
    }

    public Document getDomainDocument(String domain) {
        Document docIp = new Document();
        docIp.put(URI_DOMAIN, domain);
        docIp.put("type", DEFAULT_TYPE);
        return docIp;
    }

    @Override
    public boolean isEmpty() {
        return length() == 0L;
    }

    @Override
    public void open() {
        mongoDB = client.getDatabase(DB_NAME);
        if (!queueTableExists()) {
            mongoDB.createCollection(COLLECTION_QUEUE);
            mongoDB.createCollection(COLLECTION_URIS);
            MongoCollection<Document> mongoCollection = mongoDB.getCollection(COLLECTION_QUEUE);
            MongoCollection<Document> mongoCollectionUris = mongoDB.getCollection(COLLECTION_URIS);
            mongoCollection.createIndex(Indexes.compoundIndex(Indexes.ascending(URI_DOMAIN), Indexes.ascending("type")));
            mongoCollectionUris.createIndex(Indexes.compoundIndex(Indexes.ascending("uri"), Indexes.ascending(URI_DOMAIN),
                Indexes.ascending("type")));
        }
    }

    public boolean queueTableExists() {
        for (String collection : mongoDB.listCollectionNames()) {
            if (collection.equalsIgnoreCase(COLLECTION_QUEUE.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Iterator<String> getGroupIterator() {

        MongoCursor<Document> cursor = mongoDB.getCollection(COLLECTION_QUEUE).find().iterator();

        Iterator<String> domainIterator = new Iterator<String>() {
            @Override
            public boolean hasNext() {
                return cursor.hasNext();
            }

            @Override
            public String next() {
                return cursor.next().get(URI_DOMAIN).toString();
            }
        };

        return domainIterator;
    }

    @Override
    public List<CrawleableUri> getUris(String domain) {

        Iterator<Document> uriDocs = mongoDB.getCollection(COLLECTION_URIS)
            .find(new Document(URI_DOMAIN, domain).append("type", DEFAULT_TYPE))
            .sort(Sorts.descending(Constants.URI_SCORE)).iterator();

        List<CrawleableUri> listUris = new ArrayList<CrawleableUri>();

        try {
            while (uriDocs.hasNext()) {

                Document doc = uriDocs.next();

                listUris.add(serializer.deserialize(((Binary) doc.get("uri")).getData()));

            }

        } catch (Exception e) {
            LOGGER.error("Error while retrieving uri from MongoDBQueue", e);
        }

        return listUris;
    }

    @Override
    protected void deleteUris(String domain, List<CrawleableUri> uris) {
        // remove all URIs from the list
        Document query = new Document();
        query.put(URI_DOMAIN, domain);
        query.put("type", DEFAULT_TYPE);
        for (CrawleableUri uri : uris) {
            // replace the old ID with the current ID
            query.put("_id", uri.getUri().hashCode());
            mongoDB.getCollection(COLLECTION_URIS).deleteMany(query);
        }
        // remove the ID field
        query.remove("_id");
        // if there are no more URIs left of the given domain
        if (mongoDB.getCollection(COLLECTION_URIS).find(query).first() == null) {
            // remove the domain from the queue
            mongoDB.getCollection(COLLECTION_QUEUE).deleteMany(query);
        }
    }

    @Override
    protected void addKeywiseUris(Map<String, List<CrawleableUri>> uris) {
        Map<CrawleableUri, Float> uriMap = uriKeywiseFilter.filterUrisKeywise(uris, minNumberOfUrisToCheck, criticalScore);
        for(Map.Entry<CrawleableUri, Float> entry : uriMap.entrySet()) {
            addDomain(entry.getKey().getUri().getHost());
            addCrawleableUri(entry.getKey(), entry.getKey().getUri().getHost(), entry.getValue());
        }
    }

    protected boolean containsDomain(String domain) {
        return containsDomain(getDomainDocument(domain));
    }

    protected boolean containsDomain(Document domainDoc) {
        return mongoDB.getCollection(COLLECTION_QUEUE).find(domainDoc).first() != null;
    }

    public float getCriticalScore() {
        return criticalScore;
    }

    public void setCriticalScore(float criticalScore) {
        this.criticalScore = criticalScore;
    }

    public int getMinNumberOfUrisToCheck() {
        return minNumberOfUrisToCheck;
    }

    public void setMinNumberOfUrisToCheck(int minNumberOfUrisToCheck) {
        this.minNumberOfUrisToCheck = minNumberOfUrisToCheck;
    }
}
