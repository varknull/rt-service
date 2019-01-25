package io.vertx.examples;

import io.vertx.core.CompositeFuture;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sync.SyncVerticle;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.ErrorHandler;
import io.vertx.redis.RedisClient;
import io.vertx.redis.RedisOptions;
import io.vertx.redis.RedisTransaction;
import org.json.simple.parser.JSONParser;

import java.io.FileReader;
import java.time.Instant;
import java.util.logging.Logger;

/*
 */
public class RTServer extends SyncVerticle {
    private final static Logger LOGGER = Logger.getLogger(RTServer.class.getName());
    private int port;

    private DataClient dataClient;
    private RedisClient redisClient;

    public static void main(String[] args) throws Exception {
        DeploymentOptions deploymentOptions = new DeploymentOptions();
        VertxOptions options = new VertxOptions();

        if (args.length == 2 && args[0].equals("-conf")) { // set up json conf file
            Object json = new JSONParser().parse(new FileReader(args[1]));
            deploymentOptions.setConfig(JsonObject.mapFrom(json));
            Runner.runExample(RTServer.class, options, deploymentOptions);
        } else { // run default conf
            Runner.runExample(RTServer.class, options, deploymentOptions);
        }
    }

    @Override
    public void stop() {
        dataClient.close();
    }

    @Override
    public void start(Future<Void> fut) {
        vertx.executeBlocking(future -> {
            try {
                redisClient = initRedis();
                dataClient = new DataClient(config());
                future.complete();
            } catch (Exception ignore) {
                future.fail(ignore);
            }
        }, res -> {
            if (res.succeeded()) {
                startService();
            } else {
                LOGGER.severe("Failed to start: "+res.cause());
            }
        });
    }

    private void startService() {
        // Create a router object.
        port = config().getInteger("http.port", 8082);

        HttpServer server = vertx.createHttpServer();
        Router router = Router.router(vertx);
        router.route().failureHandler(ErrorHandler.create(true));
        router.post("/analytics").handler(this::handlePostRouter);
        router.get("/analytics").handler(this::handleGetRouter);
        server.requestHandler(router::accept);
        server.listen(port);

        LOGGER.info("Service up on port "+port);
    }

    private RedisClient initRedis() {
        // If a config file is set, read the host and port.
        String host = config().getString("redis.host", "127.0.0.1");
        int port = config().getInteger("redis.port", 6379);

        // Create the redis client
        return RedisClient.create(vertx, new RedisOptions().setHost(host).setPort(port));
    }

    // POST /analytics?timestamp={millis_since_epoch}&user={username}&click={click}&impression={impression}
    private void handlePostRouter(RoutingContext rc) {
        String timestamp = rc.request().getParam("timestamp");
        String user = rc.request().getParam("user");
        long click =  Long.parseLong(rc.request().getParam("click"));
        long impression = Long.parseLong(rc.request().getParam("impression"));
        HttpServerResponse response = rc.request().response();

        try {
            long rounded_now = getTimestampRoundedByHour(String.valueOf(Instant.now().toEpochMilli()));
            long rounded_timestamp = getTimestampRoundedByHour(timestamp);

            if (rounded_now == rounded_timestamp) { //
                RedisTransaction redisTransaction = redisClient.transaction();
                redisTransaction.multi(event -> { // commands are pipelined over one round trip and executed in a transaction
                    redisTransaction.sadd("user_" + rounded_timestamp, user, null);
                    redisTransaction.hincrby("click", String.valueOf(rounded_timestamp), click, null);
                    redisTransaction.hincrby("impression", String.valueOf(rounded_timestamp), impression, null);
                    redisTransaction.exec(execEvent -> LOGGER.info(execEvent.result().encodePrettily()));
                });
            }

            vertx.executeBlocking(future -> {// blocking operation executed in background
                try {
                    dataClient.pushToDB(rounded_timestamp, user, click, impression);
                    future.complete();
                } catch (Exception ignore) {
                    future.fail(ignore);
                }
            }, res -> {
                if (!res.succeeded()) {
                    LOGGER.severe("pushToDB failed");
                }
            });

        } catch (NumberFormatException e) { // Bad request
            LOGGER.severe(e.getMessage());
            response.setStatusCode(400).end("");
            return;
        } catch (Exception e) { // Internal server error
            LOGGER.severe(e.getMessage());
            response.setStatusCode(504).end("");
            return;
        }

        // Valid response
        response.setStatusCode(201).end();
    }

    // GET /analytics?timestamp={millis_since_epoch}
    private void handleGetRouter(RoutingContext rc) {
        String timestamp = rc.request().getParam("timestamp");
        HttpServerResponse response = rc.request().response();

        try {
            long rounded_now = getTimestampRoundedByHour(String.valueOf(Instant.now().toEpochMilli()));
            long rounded_timestamp = getTimestampRoundedByHour(timestamp);

            if (rounded_now == rounded_timestamp) { // 95% of the current hour hit the memory with redis
                Future f1 = Future.future();
                redisClient.scard("user_" + rounded_timestamp, f1.completer());
                Future f2 = Future.future();
                redisClient.hget("click", String.valueOf(rounded_timestamp), f2.completer());
                Future f3 = Future.future();
                redisClient.hget("impression", String.valueOf(rounded_timestamp), f3.completer());

                CompositeFuture.all(f1, f2, f3).setHandler(futures -> { // waits for all results
                    if (futures.succeeded()) { // All succeeded
                        long users = futures.result().resultAt(0);
                        String clicks = futures.result().resultAt(1);
                        String impressions = futures.result().resultAt(2);
                        response.setStatusCode(200).end(
                                "unique_users," + users + " \nclicks," + clicks + " \nimpressions," + impressions);
                    } else { // At least one failed
                        response.setStatusCode(504).end();
                    }
                });
            } else { // pull from DB
                dataClient.pullFromDB(rc, rounded_timestamp);
            }
        } catch (NumberFormatException e) { // Bad request
            LOGGER.severe(e.getMessage());
            e.printStackTrace();
            response.setStatusCode(400).end();
        } catch (Exception e) {
            response.setStatusCode(504).end();
            e.printStackTrace();
        }
    }

    /**
     * Round timestamp in millis to nearest hour
     * @param timestamp
     * @return rounded timestamp
     * @throws NumberFormatException
     */
    private long getTimestampRoundedByHour(String timestamp) throws NumberFormatException {
        long t = Long.parseLong(timestamp);
        return t - (t % 3600);
    }
}
