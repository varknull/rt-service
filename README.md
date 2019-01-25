# High throughput service example

A server accepts the following HTTP requests:
```
POST /analytics?timestamp={millis_since_epoch}&user={username}&{click|impression}
```
```
GET /analytics?timestamp={millis_since_epoch}
```

When the `POST` request is made, nothing is returned to the client. We simply side-effect and add the details from the request to our tracking data store.

When the `GET` request is made, we return the following information in plain-text to the client, for the hour of the requested timestamp:
```
unique_users,{number_of_unique_usernames}
clicks,{number_of_clicks}
impressions,{number_of_impressions}
```

The server will receive many more `GET` requests (95%) for the **current hour** than for hours in the past (5%).

Most `POST` requests received by the server will contain a timestamp that matches the current timestamp (95%). However, it is possible for the timestamp in a `POST` request to contain a *historic* timestamp (5%) -- e.g. it is currently the eighth hour of the day, yet the request contains a timestamp for hour six of the day.

Additional servers should be able to be spun up, at any time, without effecting the correctness of our metrics.

The `POST` and `GET` endpoints should be optimized for high throughput and low latency.


# Implementation details

In order to optimize for high throughput and low latency the application keeps in memory the most accessed data using Redis.
I assume that the Redis data has a TTL of one hour (which is the 95% of requests hitting the memory). The other 5% of
request hits Couchbase. I've chosen this solution for different reasons: it cache in memory but persist on disk,
it has a set of handy atomic commands and being nosql it's easier and quicker to use than a relational DB.

All requests are partitioned using the provided `timestamp` by rounding the epoch to its hour. For example 
`1513982504` becomes `1513980000`. Then if the request fall in the last hour group it is handled through Redis, 
otherwise it goes to Couchbase. A POST request, no matter the timestamp, it's always persisted both in Redis and Couchbase 
(through a background thread). This way all memory data has a backup that it's used whenever that hour will be considered history.

The data itself is stored in order to make GET/POST as efficient as possible. Users are stored in Sets grouped per 
rounded timestamp so that the Set size will already give the number of unique users. Clicks and impressions are stored in 
a Map using the rounded timestamp as key and taking advantage of atomic increments to update counters.

A POST request will pipeline the following Redis commands:
```
SADD(user_timestamp, username) -> Adds user in user_<t> set: O(1)
HINCRBY(click, timestamp, click_increment) -> Increment the counter for click map with key t: O(1)
HINCRBY(impression, timestamp, impression_increment) -> Increment the counter for impression map with key t: O(1)
```

A GET request will pipeline the following Redis commands:
```
SCARD(user_timestamp) -> Gets unique users for timestamp t: O(1)
HGET(click, timestamp) -> Gets counter for click(t): O(1)
HGET(impression, timestamp) -> Gets counter for impression(t): O(1)
```

Similarly, a POST request will use the Couchbase operations:
```
insert_document(user_timestamp) -> Creates user_timestamp document if not exist
setAdd(user_timestamp, user) -) Add user in set

insert_document(click_timestamp) -> Creates click_timestamp document if not exist
counter(click_timestamp, increment) -> Increment click_timestamp counter 

insert_document(impression_timestamp) -> Creates impression_timestamp document if not exist
counter(impression_timestamp, increment) -> Increment impression_timestamp counter 

```

And a GET request will use the Couchbase operations:
```
setSize(user_timestamp) -> Gets unique users for timestamp t
get(click_timestamp) -> Gets counter for click(t)
get(impression_timestamp) -> Gets counter for impression(t)
```

## Concurrency notes
All operations are executed atomically (increments, user adds, etc) and while the Redis commands in POST are executed in
a transaction the Couchbase operations are not. This means Couchbase will eventually produce the correct combination of
users, clicks, impression (but there could be discrepancies in a certain moment t). A Couchbase lock could have been
easily implemented but I preferred to give priority to performances.

## Scalability
Vert.x also offer the option to run multiple instances in a clustered mode (haven't played much with it). 
Multiple applications could easily be spun up behind a load balancer and work together with their remote Redis/Couchbase clusters. 

## Getting Started

Once redis and couchbase are running, you can launch the service using:
```
java -cp ./target/rt-service-0.1.0-fat.jar io.vertx.examples.RTServer -conf ./src/resources/conf.json
```
The service will run on the port configured by conf.json (e.g. 8090).

### Prerequisites

A running Redis instance/cluster
A running Couchbase platform. 

### Installing

Redis host and port can be configured on conf.json.

Couchbase needs an admin user to be set up the first time after installation on http://localhost:8091.
The same user/password need to be configured in the conf.json parameters couchbase.user and couchbase.password.

## Testing the service

It's possible to test the service with a simple curl: 

```
curl -v "http://localhost:8095/analytics?timestamp=1513982504"
```

```
curl -v --data "" "http://localhost:8090/analytics?timestamp=1513982504&user=valerio&click=1&impression=2"
```

I've also run some stress test of POST/GET with random parameters using `wrk` achieving performances around 12k req/s
Not bad for a quick prototype but surely it can be improved with a bit of tuning. 

## Built With

* [Vert.x](http://vertx.io/)
* [Redis](http://redis.io)
* [Cuchbase](https://www.couchbase.com/)
* [Maven](https://maven.apache.org/)

## Authors

* **varknull** -
