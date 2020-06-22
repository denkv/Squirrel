package org.dice_research.squirrel.queue.ipbased;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;

import org.aksw.jena_sparql_api.core.QueryExecutionFactory;
import org.bson.Document;
import org.bson.types.Binary;
import org.dice_research.squirrel.Constants;
import org.dice_research.squirrel.configurator.MongoConfiguration;
import org.dice_research.squirrel.data.uri.CrawleableUri;
import org.dice_research.squirrel.data.uri.serialize.Serializer;
import org.dice_research.squirrel.data.uri.serialize.java.SnappyJavaUriSerializer;
import org.dice_research.squirrel.queue.AbstractIpAddressBasedQueue;
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
 * IpBasedQueue implementation for use with MongoDB
 *
 * @author Geraldo de Souza Junior (gsjunior@mail.uni-paderborn.de)
 */
public class MongoDBIpBasedQueue extends AbstractIpAddressBasedQueue {

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
    public static final String URI_IP_ADRESS = "ipAddress";
    private float criticalScore = .2f;
    private int minNumberOfUrisToCheck = 5;

    private static final Logger LOGGER = LoggerFactory.getLogger(MongoDBIpBasedQueue.class);

    public MongoDBIpBasedQueue(String hostName, Integer port, boolean includeDepth) {
        this(hostName, port, new SnappyJavaUriSerializer(), includeDepth);

    }

    public MongoDBIpBasedQueue(String hostName, Integer port, boolean includeDepth, QueryExecutionFactory queryExecFactory) {
        this(hostName,port,new SnappyJavaUriSerializer(), includeDepth);
        this.uriKeywiseFilter = new URIKeywiseFilter(queryExecFactory);
    }

    public MongoDBIpBasedQueue(String hostName, Integer port, Serializer serializer, boolean includeDepth) {

        LOGGER.info("Queue Persistance: " + PERSIST);

        this.includeDepth = includeDepth;
        if (this.includeDepth)
            LOGGER.info("Depth Persistance Enabled.");

        this.serializer = serializer;

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

    public MongoDBIpBasedQueue(String hostName, Integer port, Serializer serializer, boolean includeDepth, QueryExecutionFactory queryExecFactory) {
        this(hostName, port, serializer, includeDepth);
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
    public void open() {
        mongoDB = client.getDatabase(DB_NAME);
        if (!queueTableExists()) {
            mongoDB.createCollection(COLLECTION_QUEUE);
            mongoDB.createCollection(COLLECTION_URIS);
            MongoCollection<Document> mongoCollection = mongoDB.getCollection(COLLECTION_QUEUE);
            MongoCollection<Document> mongoCollectionUris = mongoDB.getCollection(COLLECTION_URIS);
            mongoCollection
                .createIndex(Indexes.compoundIndex(Indexes.ascending(URI_IP_ADRESS), Indexes.ascending("type")));
            mongoCollectionUris.createIndex(Indexes.compoundIndex(Indexes.ascending("uri"),
                Indexes.ascending(URI_IP_ADRESS), Indexes.ascending("type")));
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
    protected void addUri(CrawleableUri uri, InetAddress address) {
        addIp(address);
        // default score taken as 1
        addCrawleableUri(uri, 1);
    }

    @Override
    protected Iterator<InetAddress> getGroupIterator() {

        MongoCursor<Document> cursor = mongoDB.getCollection(COLLECTION_QUEUE).find().iterator();

        Iterator<InetAddress> ipUriTypePairIterator = new Iterator<InetAddress>() {
            @Override
            public boolean hasNext() {
                return cursor.hasNext();
            }

            @Override
            public InetAddress next() {
                Document doc = (Document) cursor.next();
                try {
                    return InetAddress.getByName(doc.get(URI_IP_ADRESS).toString());
                } catch (UnknownHostException e) {
                    LOGGER.error("Got an exception when creating the InetAddress of \""
                        + doc.get(URI_IP_ADRESS).toString() + "\". Returning null.", e);
                    e.printStackTrace();
                }
                return null;
            }
        };

        return ipUriTypePairIterator;
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<CrawleableUri> getUris(InetAddress address) {

        Document query = getIpDocument(address);
        Iterator<Document> uriDocs = mongoDB.getCollection(COLLECTION_URIS).find(query)
            .sort(Sorts.descending(Constants.URI_SCORE)).iterator();

        List<CrawleableUri> listUris = new ArrayList<CrawleableUri>();

        try {
            while (uriDocs.hasNext()) {
                Document doc = uriDocs.next();
                listUris.add(serializer.deserialize(((Binary) doc.get("uri")).getData()));
            }

        } catch (Exception e) {
            LOGGER.error("Error while retrieving uri from MongoDBQueue. Returning emtpy list.", e);
            return Collections.EMPTY_LIST;
        }

        return listUris;
    }

    protected void addCrawleableUri(CrawleableUri uri, float score) {
        try {
            Document uriDoc = getUriDocument(uri, score);
            // If the document does not already exist, add it
            if (mongoDB.getCollection(COLLECTION_URIS).find(uriDoc).first() == null) {
                mongoDB.getCollection(COLLECTION_URIS).insertOne(uriDoc);
            }
        } catch (Exception e) {
            LOGGER.error("Error while adding uri to MongoDBQueue", e);
        }
    }

    protected void addIp(InetAddress address) {
        try {
            Document ipDoc = getIpDocument(address);
            // If the document does not already exist, add it
            if (!containsIpAddress(ipDoc)) {
                LOGGER.debug("Address is not in the queue, creating a new one");
                mongoDB.getCollection(COLLECTION_QUEUE).insertOne(ipDoc);
            } else {
                LOGGER.debug("Address is in the queue already");
            }
        } catch (MongoWriteException e) {
            LOGGER.info("Uri: " + address.toString() + " already in queue. Ignoring...");
        }

        LOGGER.debug("Inserted new UriTypePair");
    }

    public Document getUriDocument(CrawleableUri uri, float score) {
        byte[] suri = null;

        try {
            suri = serializer.serialize(uri);
        } catch (IOException e) {
            LOGGER.error("Couldn't serialize URI. Returning null.", e);
            return null;
        }

        InetAddress ipAddress = uri.getIpAddress();

        Document docUri = new Document();
        docUri.put("_id", uri.getUri().hashCode());
        docUri.put(URI_IP_ADRESS, ipAddress.getHostAddress());
        docUri.put("type", DEFAULT_TYPE);
        docUri.put(Constants.URI_SCORE, score);
        if (includeDepth)
            docUri.put("depth", uri.getData(Constants.URI_DEPTH));

        docUri.put("uri", new Binary(suri));
        return docUri;
    }

    public Document getIpDocument(InetAddress address) {
        Document docIp = new Document();
        docIp.put(URI_IP_ADRESS, address.getHostAddress());
        docIp.put("type", DEFAULT_TYPE);
        return docIp;
    }

    @Deprecated
    public List<String> getIpAddressTypeKey(CrawleableUri uri) {
        return packTuple(uri.getIpAddress().getHostAddress(), uri.getType().toString());
    }

    @Deprecated
    public List<String> packTuple(String str_1, String str_2) {
        List<String> pack = new ArrayList<String>();
        pack.add(str_1);
        pack.add(str_2);
        return pack;
    }

    @SuppressWarnings("unused")
    private List<CrawleableUri> createCrawleableUriList(List<Object> uris) {
        List<CrawleableUri> resultUris = new ArrayList<>();

        for (Object uriString : uris) {
            try {
                resultUris.add(serializer.deserialize((byte[]) uriString));
            } catch (Exception e) {
                LOGGER.error("Couldn't deserialize uri", e);
            }
        }

        return resultUris;
    }

    @Override
    public boolean isEmpty() {
        return length() == 0L;
    }

    @Override
    protected void deleteUris(InetAddress ipAddress, List<CrawleableUri> uris) {
        // remove all URIs from the list
        Document query = new Document();
        query.put(URI_IP_ADRESS, ipAddress.getHostAddress());
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
            mongoDB.getCollection(COLLECTION_QUEUE)
                .deleteMany(query);
        }
    }

    @Override
    protected void addKeywiseUris(Map<InetAddress, List<CrawleableUri>> uris) {
        Map<CrawleableUri, Float> uriMap = uriKeywiseFilter.filterUrisKeywise(uris, minNumberOfUrisToCheck, criticalScore);
        for(Map.Entry<CrawleableUri, Float> entry : uriMap.entrySet()) {
            addIp(entry.getKey().getIpAddress());
            addCrawleableUri(entry.getKey(), entry.getValue());
        }
    }

    protected boolean containsIpAddress(InetAddress address) {
        return containsIpAddress(getIpDocument(address));
    }

    protected boolean containsIpAddress(Document ipDoc) {
        return mongoDB.getCollection(COLLECTION_QUEUE).find(ipDoc).first() != null;
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
