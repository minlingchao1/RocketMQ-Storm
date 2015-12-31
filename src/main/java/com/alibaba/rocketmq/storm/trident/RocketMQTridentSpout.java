package com.alibaba.rocketmq.storm.trident;

import java.util.*;
import java.util.concurrent.ConcurrentMap;

import com.alibaba.rocketmq.client.consumer.MQPullConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import storm.trident.operation.TridentCollector;
import storm.trident.spout.IPartitionedTridentSpout;
import storm.trident.spout.ISpoutPartition;
import storm.trident.topology.TransactionAttempt;
import backtype.storm.task.TopologyContext;
import backtype.storm.tuple.Fields;

import com.alibaba.rocketmq.client.consumer.DefaultMQPullConsumer;
import com.alibaba.rocketmq.client.consumer.PullResult;
import com.alibaba.rocketmq.client.exception.MQBrokerException;
import com.alibaba.rocketmq.client.exception.MQClientException;
import com.alibaba.rocketmq.common.message.MessageExt;
import com.alibaba.rocketmq.common.message.MessageQueue;
import com.alibaba.rocketmq.remoting.exception.RemotingException;
import com.alibaba.rocketmq.storm.MessageConsumerManager;
import com.alibaba.rocketmq.storm.domain.BatchMessage;
import com.alibaba.rocketmq.storm.domain.RocketMQConfig;
import com.google.common.collect.Lists;
import com.google.common.collect.MapMaker;

/**
 * @author Von Gosling
 */
public class RocketMQTridentSpout implements IPartitionedTridentSpout<List<MessageQueue>, ISpoutPartition, BatchMessage> {

    private static final long serialVersionUID = 8972193358178718167L;

    private static final Logger LOG = LoggerFactory.getLogger(RocketMQTridentSpout.class);

    private static final ConcurrentMap<String, List<MessageQueue>> cachedMessageQueue = new MapMaker().makeMap();

    private RocketMQConfig config;

    private volatile transient DefaultMQPullConsumer consumer;

    public RocketMQTridentSpout() {
    }

    private MQPullConsumer getConsumer() throws MQClientException {
        if (null == consumer) {
            synchronized (this) {
                if (null == consumer) {
                    consumer = (DefaultMQPullConsumer) MessageConsumerManager.getConsumerInstance(config, null, false);
                    consumer.setInstanceName(UUID.randomUUID().toString());
                    consumer.start();
                }
            }
        }
        return consumer;
    }

    public RocketMQTridentSpout(RocketMQConfig config) throws MQClientException {
        this.config = config;
    }

    private List<MessageQueue> getMessageQueue(String topic) throws MQClientException {
        List<MessageQueue> cachedQueue = cachedMessageQueue.get(topic);
        if (cachedQueue == null) {
            // Fetches all message queues from name server.
            Set<MessageQueue> mqs = getConsumer().fetchSubscribeMessageQueues(topic);
            cachedQueue = Lists.newArrayList(mqs);
            cachedMessageQueue.put(topic, cachedQueue);
        }
        return cachedQueue;
    }

    class RocketMQCoordinator implements Coordinator<List<MessageQueue>> {

        @Override
        public List<MessageQueue> getPartitionsForBatch() {
            List<MessageQueue> messageQueues = Lists.newArrayList();
            try {
                messageQueues = getMessageQueue(config.getTopic());

                // Debug info
                if (LOG.isDebugEnabled()) {
                    LOG.debug("RocketMQCoordinator#getPartitionsForBatch");
                    for (MessageQueue messageQueue : messageQueues) {
                        LOG.debug("Message Queue: {}", messageQueue);
                    }
                }
            } catch (Exception e) {
                LOG.error("Failed to fetch message queues", e);
            }
            return messageQueues;
        }

        @Override
        public boolean isReady(long txId) {
            return true;
        }

        @Override
        public void close() {
            LOG.info("close coordinator!");
        }

    }

    class RocketMQEmitter implements Emitter<List<MessageQueue>, ISpoutPartition, BatchMessage> {

        @Override
        public List<ISpoutPartition> getOrderedPartitions(List<MessageQueue> allPartitionInfo) {

            final String signature = getClass().getName() + "#getOrderedPartitions";
            LOG.debug("Enter: {}, Params: {}", signature, allPartitionInfo);

            List<ISpoutPartition> partition = Lists.newArrayList();
            for (final MessageQueue queue : allPartitionInfo) {
                partition.add(new ISpoutPartition() {
                    @Override
                    public String getId() {
                        return String.valueOf(queue.getQueueId());
                    }
                });
            }
            return partition;
        }

        private BatchMessage handlePullResult(TransactionAttempt tx, TridentCollector collector, PullResult result,
                                              MessageQueue mq, BatchMessage lastPartitionMeta)
                throws MQClientException {
            final String signature = getClass().getName() + "#handlePullResult";
            LOG.debug("Enter {}", signature);
            LOG.debug("PullResult status: {}", result.getPullStatus().name());
            switch (result.getPullStatus()) {
                case FOUND:
                    BatchMessage batchMessages = null;
                    List<MessageExt> msgs = result.getMsgFoundList();
                    LOG.debug("MessageFoundList size: {}", msgs.size());
                    // Filter message by tag.
                    if (null != config.getTopicTag() && !"*".equals(config.getTopicTag())) {
                        String[] tags = config.getTopicTag().split("\\|\\|");
                        List<MessageExt> filteredMsgs = Lists.newArrayList();
                        if (!msgs.isEmpty()) {
                            for (MessageExt msg : msgs) {
                                for (String tag : tags) {
                                    if (tag.equals(msg.getTags())) {
                                        filteredMsgs.add(msg);
                                        break;
                                    }
                                }
                            }
                        }
                        msgs = filteredMsgs;
                    }

                    if (msgs.size() > 0) {
                        batchMessages = new BatchMessage(msgs, mq);
                        batchMessages.setOffset(result.getMinOffset());
                        batchMessages.setNextOffset(result.getNextBeginOffset());
                        for (MessageExt msg : msgs) {
                            collector.emit(Lists.newArrayList(tx, msg));
                        }
                        assert result.getMaxOffset() == batchMessages.getNextOffset() - 1;
                    }
                    getConsumer().updateConsumeOffset(mq, result.getMaxOffset());
                    DefaultMQPullConsumer defaultMQPullConsumer = (DefaultMQPullConsumer)getConsumer();
                    defaultMQPullConsumer.getOffsetStore().persist(mq);
                    return batchMessages;

                case NO_NEW_MSG:
                    LOG.debug("No new messages for this pull request.");
                    return lastPartitionMeta;

                case NO_MATCHED_MSG:
                    LOG.debug("No matched messages for this pull request");
                    return lastPartitionMeta;

                case OFFSET_ILLEGAL:
                    LOG.error("Offset illegal, please notify RocketMQ Development Team");
                    return lastPartitionMeta;

                case SLAVE_LAG_BEHIND:
                    LOG.warn("Master node is down and slave replication is lagged behind.");
                    return lastPartitionMeta;

                case SUBSCRIPTION_NOT_LATEST:
                    LOG.error("Subscription is not latest, please notify RocketMQ Development Team");
                    return lastPartitionMeta;

                default:
                    LOG.error("Unexpected execution, please notify RocketMQ Development Team");
                    return lastPartitionMeta;
            }
        }

        /**
         * Emit a batch of tuples for a partition/transaction that's never been emitted before.
         * Return the metadata that can be used to reconstruct this partition/batch in the future.
         */
        @Override
        public BatchMessage emitPartitionBatchNew(TransactionAttempt tx, //
                                                  TridentCollector collector, //
                                                  ISpoutPartition partition, //
                                                  BatchMessage lastPartitionMeta //
        ) {
            final String signature = getClass().getName() + "#emitPartitionBatchNew";
            LOG.debug("Enter: {}, Params:[tx: {}, partition: {}, lastPartitionMeta: {}]",
                    signature, tx, partition, lastPartitionMeta);

            try {
                MessageQueue mq = getMessageQueue(config.getTopic()).get(Integer.parseInt(partition.getId()));

                long index = 0;
                if (null == lastPartitionMeta) {
                    index = getConsumer().fetchConsumeOffset(mq, false);
                    if (index < 0) {
                        index = 0;
                    }
                } else {
                    index = lastPartitionMeta.getNextOffset();
                }

                LOG.debug("Begin to pull[MessageQueue: {}, tag: {}, index: {}, maxNum: {}]",
                        mq, config.getTopicTag(), index, config.getPullBatchSize());
                PullResult result = getConsumer().pull(mq, config.getTopicTag(), index, config.getPullBatchSize());
                return handlePullResult(tx, collector, result, mq, lastPartitionMeta);
            } catch (MQClientException | RemotingException | MQBrokerException | InterruptedException e) {
                LOG.error("Pull Failed.", e);
                return lastPartitionMeta;
            }
        }

        @Override
        public void refreshPartitions(List<ISpoutPartition> partitionResponsibilities) {

        }

        /**
         * Emit a batch of tuples for a partition/transaction that has been emitted before, using
         * the metadata created when it was first emitted.
         */
        @Override
        public void emitPartitionBatch(TransactionAttempt tx, //
                                       TridentCollector collector, //
                                       ISpoutPartition partition, //
                                       BatchMessage partitionMeta //
        ) {
            final String signature = getClass().getName() + "#emitPartitionBatch";
            LOG.debug("Enter {}", signature);
            try {
                MessageQueue mq = getMessageQueue(config.getTopic()).get(Integer.parseInt(partition.getId()));
                int batchSize = (int) (partitionMeta.getNextOffset() - partitionMeta.getOffset());
                PullResult result = getConsumer().pull(mq, config.getTopicTag(), partitionMeta.getOffset(), batchSize);
                BatchMessage batchMessage = handlePullResult(tx, collector, result, mq, partitionMeta);
                if (batchMessage == partitionMeta) {
                    throw new RuntimeException("Pull failed, refer to log for details.");
                }
            } catch (Exception e) {
                LOG.error("Pull failed", e);
                throw new RuntimeException("Pull failed, refer to log file for details.", e);
            }
        }

        @Override
        public void close() {
            LOG.info("close emitter!");
        }

    }

    @Override
    public Coordinator<List<MessageQueue>> getCoordinator(@SuppressWarnings("rawtypes") Map conf, TopologyContext context) {
        return new RocketMQCoordinator();
    }

    @Override
    public Emitter<List<MessageQueue>, ISpoutPartition, BatchMessage> getEmitter(@SuppressWarnings("rawtypes") Map conf, TopologyContext context) {
        return new RocketMQEmitter();
    }

    @SuppressWarnings("rawtypes")
    @Override
    public Map getComponentConfiguration() {
        return null;
    }

    @Override
    public Fields getOutputFields() {
        return new Fields("tId", "message");
    }

}
