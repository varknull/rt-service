package io.vertx.examples;

import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.CouchbaseCluster;
import com.couchbase.client.java.bucket.BucketType;
import com.couchbase.client.java.cluster.BucketSettings;
import com.couchbase.client.java.cluster.ClusterManager;
import com.couchbase.client.java.cluster.DefaultBucketSettings;
import com.couchbase.client.java.document.JsonArrayDocument;
import com.couchbase.client.java.document.JsonLongDocument;
import com.couchbase.client.java.document.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

import java.util.logging.Logger;

public class DataClient {
    private final static Logger LOGGER = Logger.getLogger(DataClient.class.getName());

    public final static String USER = "user_";
    public final static String CLICKS = "clicks_";
    public final static String IMPRESSIONS = "impressions_";

    private CouchbaseCluster cluster;
    private Bucket bucket;

    public DataClient(JsonObject conf) {
        cluster = CouchbaseCluster.create(conf.getString("couchbase.host"))
                .authenticate(conf.getString("couchbase.user"), conf.getString("couchbase.password"));

        ClusterManager clusterManager = cluster.clusterManager(
                conf.getString("couchbase.user"), conf.getString("couchbase.password"));
        BucketSettings bucketSettings = new DefaultBucketSettings.Builder()
                .type(BucketType.COUCHBASE)
                .name(conf.getString("couchbase.bucket"))
                .quota(conf.getInteger("couchbase.bucket.quota"))
                .replicas(1)
                .enableFlush(true)
                .indexReplicas(true)
                .build();

        if (!clusterManager.hasBucket(conf.getString("couchbase.bucket"))) {
            clusterManager.insertBucket(bucketSettings);
        }

        // Connect to the bucket and open it
        bucket = cluster.openBucket(conf.getString("couchbase.bucket"));
    }

    public void pushToDB(long timestamp, String user, long clickIncrement, long impressionIncrement) {
        insertUser(timestamp, user);
        saveClicks(timestamp, clickIncrement);
        saveImpressions(timestamp, impressionIncrement);
    }

    public void pullFromDB(RoutingContext routingContext, long timestamp) {
        long users = countUsers(timestamp);
        long clicks = selectClicks(timestamp);
        long impressions = selectImpressions(timestamp);

        routingContext.response().setStatusCode(200)
                .end("unique_users," + users + " \nclicks," + clicks + " \nimpressions," + impressions);
    }

    private void insertUser(long timestamp, String user) {
        try {
            bucket.insert(JsonArrayDocument.create(USER+timestamp, JsonArray.create()));
        } catch (Exception e) {// ignore if it exist
        }
        bucket.setAdd(USER+timestamp, user);
    }

    private long countUsers(long timestamp) {
        int size = 0;
        try {
            size = bucket.setSize(USER+timestamp);
        } catch (Exception e) {
            // ignore not exist
        }
        return size;
    }

    private long selectClicks(long timestamp) {
        long clicks = 0;
        JsonLongDocument found = bucket.get(CLICKS+timestamp, JsonLongDocument.class);
        if (found != null) {
            clicks = found.content().longValue();
        }
        return clicks;
    }

    private long selectImpressions(long timestamp) {
        long impressions = 0;
        JsonLongDocument found = bucket.get(IMPRESSIONS+timestamp, JsonLongDocument.class);
        if (found != null) {
            impressions = found.content().longValue();
        }
        return impressions;
    }

    private void saveClicks(long timestamp, long clicks) {
        try {
            bucket.insert(JsonLongDocument.create(CLICKS+timestamp, Long.valueOf(0)));
        } catch (Exception e) {// ignore if it exist
        }
        bucket.counter(CLICKS+timestamp, clicks);
    }

    private void saveImpressions(long timestamp, long impressions) {
        try {
            bucket.insert(JsonLongDocument.create(IMPRESSIONS+timestamp, Long.valueOf(0)));
        } catch (Exception e) {// ignore if it exist
        }
        bucket.counter(IMPRESSIONS+timestamp, impressions);
    }

    public void close() {
        // Close all buckets and disconnect
        cluster.disconnect();
    }
}
