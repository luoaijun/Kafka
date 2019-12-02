/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.kstream.internals;

import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.streams.kstream.Aggregator;
import org.apache.kafka.streams.kstream.Initializer;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Merger;
import org.apache.kafka.streams.kstream.SessionWindows;
import org.apache.kafka.streams.kstream.Window;
import org.apache.kafka.streams.kstream.Windows;
import org.apache.kafka.streams.kstream.internals.graph.ProcessorGraphNode;
import org.apache.kafka.streams.kstream.internals.graph.ProcessorParameters;
import org.apache.kafka.streams.kstream.internals.graph.StatefulProcessorNode;
import org.apache.kafka.streams.kstream.internals.graph.StreamsGraphNode;
import org.apache.kafka.streams.processor.ProcessorSupplier;
import org.apache.kafka.streams.processor.StateStore;
import org.apache.kafka.streams.state.StoreBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;

class CogroupedStreamAggregateBuilder<K, VOut> {
    private final InternalStreamsBuilder builder;

    CogroupedStreamAggregateBuilder(final InternalStreamsBuilder builder) {
        this.builder = builder;
    }

    <KR, VIn, W extends Window> KTable<KR, VOut> build(final Map<KGroupedStreamImpl<K, ?>, Aggregator<? super K, ? super Object, VOut>> groupPatterns,
                                                       final Initializer<VOut> initializer,
                                                       final NamedInternal named,
                                                       final StoreBuilder<? extends StateStore> storeBuilder,
                                                       final Serde<KR> keySerde,
                                                       final Serde<VOut> valSerde,
                                                       final String queryableName,
                                                       final Windows<W> windows,
                                                       final SessionWindows sessionWindows,
                                                       final Merger<? super K, VOut> sessionMerger) {

        final Collection<? extends AbstractStream<K, ?>> groupedStreams = new ArrayList<>(groupPatterns.keySet());
        final AbstractStream<K, ?> kGrouped = groupedStreams.iterator().next();
        groupedStreams.remove(kGrouped);
        kGrouped.ensureCopartitionWith(groupedStreams);

        final Collection<StreamsGraphNode> processors = new ArrayList<>();
        boolean stateCreated = false;
        int counter = 0;
        for (final Entry<KGroupedStreamImpl<K, ?>, Aggregator<? super K, ? super Object, VOut>> kGroupedStream : groupPatterns.entrySet()) {
            final StatefulProcessorNode statefulProcessorNode = getStatefulProcessorNode(
                kGroupedStream.getValue(),
                initializer,
                named.suffixWithOrElseGet(
                    "-cogroup-agg-" + counter++,
                    builder,
                    CogroupedKStreamImpl.AGGREGATE_NAME),
                stateCreated,
                storeBuilder,
                windows,
                sessionWindows,
                sessionMerger);
            stateCreated = true;
            processors.add(statefulProcessorNode);
            builder.addGraphNode(kGroupedStream.getKey().streamsGraphNode, statefulProcessorNode);
        }
        final String mergeProcessorName = named.suffixWithOrElseGet(
            "-cogroup-merge",
            builder,
            CogroupedKStreamImpl.MERGE_NAME);
        final ProcessorSupplier<K, VOut> passThrough = new PassThrough<>();
        final ProcessorGraphNode<K, VOut> mergeNode =
            new ProcessorGraphNode<>(mergeProcessorName, new ProcessorParameters<>(passThrough, mergeProcessorName));

        builder.addGraphNode(processors, mergeNode);

        return new KTableImpl<KR, VIn, VOut>(
            mergeProcessorName,
            keySerde,
            valSerde,
            Collections.singleton(mergeNode.nodeName()),
            queryableName,
            passThrough,
            mergeNode,
            builder);
    }

    private <W extends Window> StatefulProcessorNode getStatefulProcessorNode(final Aggregator<? super K, ? super Object, VOut> aggregator,
                                                                              final Initializer<VOut> initializer,
                                                                              final String processorName,
                                                                              final boolean stateCreated,
                                                                              final StoreBuilder<? extends StateStore> storeBuilder,
                                                                              final Windows<W> windows,
                                                                              final SessionWindows sessionWindows,
                                                                              final Merger<? super K, VOut> sessionMerger) {

        final ProcessorSupplier<K, ?> kStreamAggregate;

        if (windows == null && sessionWindows == null) {
            kStreamAggregate = new KStreamAggregate<>(storeBuilder.name(), initializer, aggregator);
        } else if (windows != null && sessionWindows == null) {
            kStreamAggregate = new KStreamWindowAggregate<>(windows, storeBuilder.name(), initializer, aggregator);
        } else if (windows == null && sessionMerger != null) {
            kStreamAggregate = new KStreamSessionWindowAggregate<>(sessionWindows, storeBuilder.name(), initializer, aggregator, sessionMerger);
        } else {
            throw new IllegalArgumentException("must include windows OR sessionWindows + sessionMerger OR all must be null");
        }

        final StatefulProcessorNode<K, ?> statefulProcessorNode;
        if (!stateCreated) {
            statefulProcessorNode =
                    new StatefulProcessorNode<>(
                            processorName,
                            new ProcessorParameters<>(kStreamAggregate, processorName),
                            storeBuilder
                    );
        } else {
            statefulProcessorNode =
                    new StatefulProcessorNode<>(
                            processorName,
                            new ProcessorParameters<>(kStreamAggregate, processorName),
                            new String[]{storeBuilder.name()}
                    );
        }
        return statefulProcessorNode;
    }


}