/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.decaton.client.internal;

import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.apache.kafka.clients.producer.ProducerRecord;

import com.google.protobuf.ByteString;

import com.linecorp.decaton.client.DecatonClient;
import com.linecorp.decaton.client.KafkaProducerSupplier;
import com.linecorp.decaton.client.PutTaskResult;
import com.linecorp.decaton.client.kafka.PrintableAsciiStringSerializer;
import com.linecorp.decaton.common.Serializer;
import com.linecorp.decaton.protocol.Decaton.TaskMetadataProto;
import com.linecorp.decaton.protocol.internal.DecatonInternal.DecatonTaskRequest;

public class DecatonClientImpl<T> implements DecatonClient<T> {
    private static final org.apache.kafka.common.serialization.Serializer<String> keySerializer =
            new PrintableAsciiStringSerializer();
    private final String topic;
    private final Serializer<T> serializer;
    private final DecatonTaskProducer producer;
    private final String applicationId;
    private final String instanceId;
    private final Supplier<Long> timestampSupplier;
    private final boolean produceInOldFormat;

    DecatonClientImpl(String topic,
                      Serializer<T> serializer,
                      String applicationId,
                      String instanceId,
                      Properties producerConfig,
                      KafkaProducerSupplier producerSupplier,
                      Supplier<Long> timestampSupplier,
                      boolean produceInOldFormat) {
        this.topic = topic;
        this.serializer = serializer;
        this.applicationId = applicationId;
        this.instanceId = instanceId;
        producer = new DecatonTaskProducer(producerConfig, producerSupplier);
        this.timestampSupplier = timestampSupplier;
        this.produceInOldFormat = produceInOldFormat;
    }

    public DecatonClientImpl(String topic,
                             Serializer<T> serializer,
                             String applicationId,
                             String instanceId,
                             Properties producerConfig,
                             KafkaProducerSupplier producerSupplier,
                             boolean produceInOldFormat) {
        this(topic, serializer, applicationId, instanceId, producerConfig, producerSupplier,
             System::currentTimeMillis, produceInOldFormat);
    }

    public DecatonClientImpl(String topic,
                             Serializer<T> serializer,
                             String applicationId,
                             String instanceId,
                             Properties producerConfig,
                             KafkaProducerSupplier producerSupplier) {
        this(topic, serializer, applicationId, instanceId, producerConfig, producerSupplier,
             System::currentTimeMillis, false);
    }

    @Override
    public CompletableFuture<PutTaskResult> put(String key, T task, long timestamp) {
        TaskMetadataProto taskMetadata = TaskMetadataProto.newBuilder()
                                                          .setTimestampMillis(timestamp)
                                                          .setSourceApplicationId(applicationId)
                                                          .setSourceInstanceId(instanceId)
                                                          .build();

        return put(key, task, taskMetadata, null);
    }

    @Override
    public CompletableFuture<PutTaskResult> put(String key, T task, TaskMetadata overrideTaskMetadata) {
        return put(key, task, overrideTaskMetadata, null);
    }

    @Override
    public CompletableFuture<PutTaskResult> put(String key, T task, TaskMetadata overrideTaskMetadata,
                                                Integer partition) {
        TaskMetadataProto taskMetadata = convertToTaskMetadataProto(overrideTaskMetadata);
        return put(key, task, taskMetadata, partition);
    }

    @Override
    public CompletableFuture<PutTaskResult> put(String key, T task) {
        return put(key, task, timestampSupplier.get());
    }

    @Override
    public CompletableFuture<PutTaskResult> put(String key, T task, Consumer<Throwable> errorCallback) {
        return put(key, task, timestampSupplier.get(), errorCallback);
    }

    @Override
    public void close() throws Exception {
        producer.close();
    }

    private CompletableFuture<PutTaskResult> put(String key, T task, TaskMetadataProto taskMetadataProto,
                                                 Integer partition) {
        byte[] serializedKey = keySerializer.serialize(topic, key);
        byte[] serializedTask = serializer.serialize(task);
        final ProducerRecord<byte[], byte[]> record;
        if (produceInOldFormat) {
            DecatonTaskRequest request =
                    DecatonTaskRequest.newBuilder()
                                      .setMetadata(taskMetadataProto)
                                      .setSerializedTask(ByteString.copyFrom(serializedTask))
                                      .build();
            record = new ProducerRecord<>(
                    topic, partition, serializedKey, request.toByteArray());
        } else {
            record = new ProducerRecord<>(
                    topic, partition, serializedKey, serializedTask);
            TaskMetadataUtil.writeAsHeader(taskMetadataProto, record.headers());
        }

        return producer.sendRequest(record);
    }

    private TaskMetadataProto convertToTaskMetadataProto(TaskMetadata overrideTaskMetadata) {
        final TaskMetadataProto.Builder
                taskMetadataProtoBuilder = TaskMetadataProto.newBuilder()
                                                            .setSourceApplicationId(applicationId)
                                                            .setSourceInstanceId(instanceId)
                                                            .setTimestampMillis(timestampSupplier.get());
        if (overrideTaskMetadata == null) {
            return taskMetadataProtoBuilder.build();
        }
        final Long timestamp = overrideTaskMetadata.getTimestamp();
        final Long scheduledTime = overrideTaskMetadata.getScheduledTime();
        if (timestamp != null) {
            taskMetadataProtoBuilder.setTimestampMillis(timestamp);
        }
        if (scheduledTime != null) {
            taskMetadataProtoBuilder.setScheduledTimeMillis(scheduledTime);
        }

        return taskMetadataProtoBuilder.build();
    }

}
