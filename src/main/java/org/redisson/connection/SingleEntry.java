package org.redisson.connection;

import io.netty.channel.EventLoopGroup;

import org.redisson.MasterSlaveServersConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lambdaworks.redis.RedisClient;
import com.lambdaworks.redis.RedisConnection;
import com.lambdaworks.redis.RedisConnectionException;
import com.lambdaworks.redis.codec.RedisCodec;
import com.lambdaworks.redis.pubsub.RedisPubSubConnection;

public class SingleEntry extends MasterSlaveEntry {

    private final Logger log = LoggerFactory.getLogger(getClass());

    public SingleEntry(RedisCodec codec, EventLoopGroup group, MasterSlaveServersConfig config) {
        super(codec, group, config);
    }

    @Override
    public void setupMasterEntry(String host, int port) {
        RedisClient masterClient = new RedisClient(group, host, port);
        masterEntry = new SubscribesConnectionEntry(masterClient, config.getMasterConnectionPoolSize(), config.getSlaveSubscriptionConnectionPoolSize());
    }

    private void acquireSubscribeConnection() {
        if (!((SubscribesConnectionEntry)masterEntry).getSubscribeConnectionsSemaphore().tryAcquire()) {
            log.warn("Subscribe connection pool gets exhausted! Trying to acquire connection ...");
            long time = System.currentTimeMillis();
            ((SubscribesConnectionEntry)masterEntry).getSubscribeConnectionsSemaphore().acquireUninterruptibly();
            long endTime = System.currentTimeMillis() - time;
            log.warn("Subscribe connection acquired, time spended: {} ms", endTime);
        }
    }

    @Override
    RedisPubSubConnection nextPubSubConnection() {
        acquireSubscribeConnection();

        RedisPubSubConnection conn = ((SubscribesConnectionEntry)masterEntry).pollFreeSubscribeConnection();
        if (conn != null) {
            return conn;
        }

        try {
            conn = masterEntry.getClient().connectPubSub(codec);
            if (config.getPassword() != null) {
                conn.auth(config.getPassword());
            }
            return conn;
        } catch (RedisConnectionException e) {
            ((SubscribesConnectionEntry)masterEntry).getSubscribeConnectionsSemaphore().release();
            throw e;
        }
    }

    @Override
    public void returnSubscribeConnection(PubSubConnectionEntry entry) {
        ((SubscribesConnectionEntry)masterEntry).offerFreeSubscribeConnection(entry.getConnection());
        ((SubscribesConnectionEntry)masterEntry).getSubscribeConnectionsSemaphore().release();
    }

    @Override
    public <K, V> RedisConnection<K, V> connectionReadOp() {
        return super.connectionWriteOp();
    }

    @Override
    public void releaseRead(RedisConnection сonnection) {
        super.releaseWrite(сonnection);
    }
}