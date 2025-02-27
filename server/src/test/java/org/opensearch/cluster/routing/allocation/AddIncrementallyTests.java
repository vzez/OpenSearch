/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.cluster.routing.allocation;

import com.carrotsearch.hppc.cursors.ObjectCursor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.Version;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.OpenSearchAllocationTestCase;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.RoutingNode;
import org.opensearch.cluster.routing.RoutingNodes;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.cluster.routing.allocation.decider.ClusterRebalanceAllocationDecider;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.CollectionUtils;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;

import java.util.ArrayList;
import java.util.Collections;

import static org.opensearch.cluster.routing.ShardRoutingState.INITIALIZING;
import static org.opensearch.cluster.routing.ShardRoutingState.STARTED;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

public class AddIncrementallyTests extends OpenSearchAllocationTestCase {
    private final Logger logger = LogManager.getLogger(AddIncrementallyTests.class);

    public void testAddNodesAndIndices() {
        Settings.Builder settings = Settings.builder();
        settings.put(
            ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(),
            ClusterRebalanceAllocationDecider.ClusterRebalanceType.ALWAYS.toString()
        );
        AllocationService service = createAllocationService(settings.build());

        ClusterState clusterState = initCluster(service, 1, 3, 3, 1);
        assertThat(clusterState.getRoutingNodes().node("node0").shardsWithState(STARTED).size(), equalTo(9));
        assertThat(clusterState.getRoutingNodes().unassigned().size(), equalTo(9));
        int nodeOffset = 1;
        clusterState = addNodes(clusterState, service, 1, nodeOffset++);
        assertThat(clusterState.getRoutingNodes().node("node0").shardsWithState(STARTED).size(), equalTo(9));
        assertThat(clusterState.getRoutingNodes().node("node1").shardsWithState(STARTED).size(), equalTo(9));
        assertThat(clusterState.getRoutingNodes().unassigned().size(), equalTo(0));
        assertNumIndexShardsPerNode(clusterState, equalTo(3));
        clusterState = addNodes(clusterState, service, 1, nodeOffset++);
        assertNumIndexShardsPerNode(clusterState, equalTo(2));
        clusterState = addNodes(clusterState, service, 1, nodeOffset++);
        assertNumIndexShardsPerNode(clusterState, Matchers.lessThanOrEqualTo(2));
        assertAtLeastOneIndexShardPerNode(clusterState);
        clusterState = removeNodes(clusterState, service, 1);
        assertNumIndexShardsPerNode(clusterState, equalTo(2));

        clusterState = addIndex(clusterState, service, 3, 2, 3);
        assertThat(clusterState.getRoutingNodes().unassigned().size(), equalTo(2));
        assertNumIndexShardsPerNode(clusterState, "test3", equalTo(2));
        assertNumIndexShardsPerNode(clusterState, Matchers.lessThanOrEqualTo(2));

        clusterState = addIndex(clusterState, service, 4, 2, 3);
        assertThat(clusterState.getRoutingNodes().unassigned().size(), equalTo(4));
        assertNumIndexShardsPerNode(clusterState, "test4", equalTo(2));
        assertNumIndexShardsPerNode(clusterState, Matchers.lessThanOrEqualTo(2));
        clusterState = addNodes(clusterState, service, 1, nodeOffset++);
        assertNumIndexShardsPerNode(clusterState, Matchers.lessThanOrEqualTo(2));
        assertThat(clusterState.getRoutingNodes().unassigned().size(), equalTo(0));
        clusterState = removeNodes(clusterState, service, 1);
        assertThat(clusterState.getRoutingNodes().unassigned().size(), equalTo(4));
        assertNumIndexShardsPerNode(clusterState, Matchers.lessThanOrEqualTo(2));
        clusterState = addNodes(clusterState, service, 1, nodeOffset++);
        assertNumIndexShardsPerNode(clusterState, Matchers.lessThanOrEqualTo(2));
        assertThat(clusterState.getRoutingNodes().unassigned().size(), equalTo(0));
        logger.debug("ClusterState: {}", clusterState.getRoutingNodes());
    }

    public void testMinimalRelocations() {
        Settings.Builder settings = Settings.builder();
        settings.put(
            ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(),
            ClusterRebalanceAllocationDecider.ClusterRebalanceType.ALWAYS.toString()
        ).put("cluster.routing.allocation.node_concurrent_recoveries", 2);
        AllocationService service = createAllocationService(settings.build());

        ClusterState clusterState = initCluster(service, 1, 3, 3, 1);
        assertThat(clusterState.getRoutingNodes().node("node0").shardsWithState(STARTED).size(), equalTo(9));
        assertThat(clusterState.getRoutingNodes().unassigned().size(), equalTo(9));
        int nodeOffset = 1;
        clusterState = addNodes(clusterState, service, 1, nodeOffset++);
        assertThat(clusterState.getRoutingNodes().node("node0").shardsWithState(STARTED).size(), equalTo(9));
        assertThat(clusterState.getRoutingNodes().node("node1").shardsWithState(STARTED).size(), equalTo(9));
        assertThat(clusterState.getRoutingNodes().unassigned().size(), equalTo(0));
        assertNumIndexShardsPerNode(clusterState, equalTo(3));

        logger.info("now, start one more node, check that rebalancing will happen because we set it to always");
        DiscoveryNodes.Builder nodes = DiscoveryNodes.builder(clusterState.nodes());
        nodes.add(newNode("node2"));
        clusterState = ClusterState.builder(clusterState).nodes(nodes.build()).build();

        clusterState = service.reroute(clusterState, "reroute");
        RoutingNodes routingNodes = clusterState.getRoutingNodes();

        assertThat(routingNodes.node("node2").shardsWithState(INITIALIZING).size(), equalTo(2));
        assertThat(routingNodes.node("node0").shardsWithState(INITIALIZING).size(), equalTo(0));
        assertThat(routingNodes.node("node1").shardsWithState(INITIALIZING).size(), equalTo(0));

        ClusterState newState = startInitializingShardsAndReroute(service, clusterState);
        assertThat(newState, not(equalTo(clusterState)));
        clusterState = newState;
        routingNodes = clusterState.getRoutingNodes();
        assertThat(routingNodes.node("node2").shardsWithState(STARTED).size(), equalTo(2));
        assertThat(routingNodes.node("node2").shardsWithState(INITIALIZING).size(), equalTo(2));
        assertThat(routingNodes.node("node0").shardsWithState(INITIALIZING).size(), equalTo(0));
        assertThat(routingNodes.node("node1").shardsWithState(INITIALIZING).size(), equalTo(0));

        newState = startInitializingShardsAndReroute(service, clusterState);
        assertThat(newState, not(equalTo(clusterState)));
        clusterState = newState;
        routingNodes = clusterState.getRoutingNodes();
        assertThat(routingNodes.node("node2").shardsWithState(STARTED).size(), equalTo(4));
        assertThat(routingNodes.node("node2").shardsWithState(INITIALIZING).size(), equalTo(2));
        assertThat(routingNodes.node("node0").shardsWithState(INITIALIZING).size(), equalTo(0));
        assertThat(routingNodes.node("node1").shardsWithState(INITIALIZING).size(), equalTo(0));

        newState = startInitializingShardsAndReroute(service, clusterState);
        assertThat(newState, not(equalTo(clusterState)));
        clusterState = newState;
        routingNodes = clusterState.getRoutingNodes();
        assertThat(routingNodes.node("node2").shardsWithState(STARTED).size(), equalTo(6));
        assertThat(routingNodes.node("node2").shardsWithState(INITIALIZING).size(), equalTo(0));
        assertThat(routingNodes.node("node0").shardsWithState(INITIALIZING).size(), equalTo(0));
        assertThat(routingNodes.node("node1").shardsWithState(INITIALIZING).size(), equalTo(0));

        newState = startInitializingShardsAndReroute(service, clusterState);
        assertThat(newState, equalTo(clusterState));
        assertNumIndexShardsPerNode(clusterState, equalTo(2));
        logger.debug("ClusterState: {}", clusterState.getRoutingNodes());
    }

    public void testMinimalRelocationsNoLimit() {
        Settings.Builder settings = Settings.builder();
        settings.put(
            ClusterRebalanceAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ALLOW_REBALANCE_SETTING.getKey(),
            ClusterRebalanceAllocationDecider.ClusterRebalanceType.ALWAYS.toString()
        )
            .put("cluster.routing.allocation.node_concurrent_recoveries", 100)
            .put("cluster.routing.allocation.node_initial_primaries_recoveries", 100);
        AllocationService service = createAllocationService(settings.build());

        ClusterState clusterState = initCluster(service, 1, 3, 3, 1);
        assertThat(clusterState.getRoutingNodes().node("node0").shardsWithState(STARTED).size(), equalTo(9));
        assertThat(clusterState.getRoutingNodes().unassigned().size(), equalTo(9));
        int nodeOffset = 1;
        clusterState = addNodes(clusterState, service, 1, nodeOffset++);
        assertThat(clusterState.getRoutingNodes().node("node0").shardsWithState(STARTED).size(), equalTo(9));
        assertThat(clusterState.getRoutingNodes().node("node1").shardsWithState(STARTED).size(), equalTo(9));
        assertThat(clusterState.getRoutingNodes().unassigned().size(), equalTo(0));
        assertNumIndexShardsPerNode(clusterState, equalTo(3));

        logger.info("now, start one more node, check that rebalancing will happen because we set it to always");
        DiscoveryNodes.Builder nodes = DiscoveryNodes.builder(clusterState.nodes());
        nodes.add(newNode("node2"));
        clusterState = ClusterState.builder(clusterState).nodes(nodes.build()).build();

        clusterState = service.reroute(clusterState, "reroute");
        RoutingNodes routingNodes = clusterState.getRoutingNodes();

        assertThat(routingNodes.node("node2").shardsWithState(INITIALIZING).size(), equalTo(2));
        assertThat(routingNodes.node("node0").shardsWithState(INITIALIZING).size(), equalTo(0));
        assertThat(routingNodes.node("node1").shardsWithState(INITIALIZING).size(), equalTo(0));

        ClusterState newState = startInitializingShardsAndReroute(service, clusterState);
        assertThat(newState, not(equalTo(clusterState)));
        clusterState = newState;
        routingNodes = clusterState.getRoutingNodes();
        assertThat(routingNodes.node("node2").shardsWithState(STARTED).size(), equalTo(2));
        assertThat(routingNodes.node("node2").shardsWithState(INITIALIZING).size(), equalTo(2));
        assertThat(routingNodes.node("node0").shardsWithState(INITIALIZING).size(), equalTo(0));
        assertThat(routingNodes.node("node1").shardsWithState(INITIALIZING).size(), equalTo(0));

        newState = startInitializingShardsAndReroute(service, clusterState);
        assertThat(newState, not(equalTo(clusterState)));
        clusterState = newState;
        routingNodes = clusterState.getRoutingNodes();
        assertThat(routingNodes.node("node2").shardsWithState(STARTED).size(), equalTo(4));
        assertThat(routingNodes.node("node2").shardsWithState(INITIALIZING).size(), equalTo(2));
        assertThat(routingNodes.node("node0").shardsWithState(INITIALIZING).size(), equalTo(0));
        assertThat(routingNodes.node("node1").shardsWithState(INITIALIZING).size(), equalTo(0));

        newState = startInitializingShardsAndReroute(service, clusterState);
        assertThat(newState, not(equalTo(clusterState)));
        clusterState = newState;
        routingNodes = clusterState.getRoutingNodes();
        assertThat(routingNodes.node("node2").shardsWithState(STARTED).size(), equalTo(6));
        assertThat(routingNodes.node("node2").shardsWithState(INITIALIZING).size(), equalTo(0));
        assertThat(routingNodes.node("node0").shardsWithState(INITIALIZING).size(), equalTo(0));
        assertThat(routingNodes.node("node1").shardsWithState(INITIALIZING).size(), equalTo(0));

        newState = startInitializingShardsAndReroute(service, clusterState);
        assertThat(newState, equalTo(clusterState));
        assertNumIndexShardsPerNode(clusterState, equalTo(2));
        logger.debug("ClusterState: {}", clusterState.getRoutingNodes());
    }

    private void assertNumIndexShardsPerNode(ClusterState state, Matcher<Integer> matcher) {
        for (ObjectCursor<String> index : state.routingTable().indicesRouting().keys()) {
            assertNumIndexShardsPerNode(state, index.value, matcher);
        }
    }

    private void assertNumIndexShardsPerNode(ClusterState state, String index, Matcher<Integer> matcher) {
        for (RoutingNode node : state.getRoutingNodes()) {
            assertThat(node.shardsWithState(index, STARTED).size(), matcher);
        }
    }

    private void assertAtLeastOneIndexShardPerNode(ClusterState state) {
        for (ObjectCursor<String> index : state.routingTable().indicesRouting().keys()) {

            for (RoutingNode node : state.getRoutingNodes()) {
                assertThat(node.shardsWithState(index.value, STARTED).size(), Matchers.greaterThanOrEqualTo(1));
            }
        }

    }

    private ClusterState addNodes(ClusterState clusterState, AllocationService service, int numNodes, int nodeOffset) {
        logger.info("now, start [{}] more node, check that rebalancing will happen because we set it to always", numNodes);
        DiscoveryNodes.Builder nodes = DiscoveryNodes.builder(clusterState.nodes());
        for (int i = 0; i < numNodes; i++) {
            nodes.add(newNode("node" + (i + nodeOffset)));
        }

        clusterState = ClusterState.builder(clusterState).nodes(nodes.build()).build();

        clusterState = service.reroute(clusterState, "reroute");

        // move initializing to started
        return applyStartedShardsUntilNoChange(clusterState, service);
    }

    private ClusterState initCluster(
        AllocationService service,
        int numberOfNodes,
        int numberOfIndices,
        int numberOfShards,
        int numberOfReplicas
    ) {
        Metadata.Builder metadataBuilder = Metadata.builder();
        RoutingTable.Builder routingTableBuilder = RoutingTable.builder();

        for (int i = 0; i < numberOfIndices; i++) {
            IndexMetadata.Builder index = IndexMetadata.builder("test" + i)
                .settings(settings(Version.CURRENT))
                .numberOfShards(numberOfShards)
                .numberOfReplicas(numberOfReplicas);
            metadataBuilder = metadataBuilder.put(index);
        }

        Metadata metadata = metadataBuilder.build();

        for (final IndexMetadata cursor : metadata.indices().values()) {
            routingTableBuilder.addAsNew(cursor);
        }

        RoutingTable initialRoutingTable = routingTableBuilder.build();

        logger.info("start {} nodes", numberOfNodes);
        DiscoveryNodes.Builder nodes = DiscoveryNodes.builder();
        for (int i = 0; i < numberOfNodes; i++) {
            nodes.add(newNode("node" + i));
        }
        ClusterState clusterState = ClusterState.builder(org.opensearch.cluster.ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .nodes(nodes)
            .metadata(metadata)
            .routingTable(initialRoutingTable)
            .build();
        clusterState = service.reroute(clusterState, "reroute");

        logger.info("restart all the primary shards, replicas will start initializing");
        clusterState = startInitializingShardsAndReroute(service, clusterState);

        logger.info("start the replica shards");
        clusterState = startInitializingShardsAndReroute(service, clusterState);

        logger.info("complete rebalancing");
        return applyStartedShardsUntilNoChange(clusterState, service);
    }

    private ClusterState addIndex(
        ClusterState clusterState,
        AllocationService service,
        int indexOrdinal,
        int numberOfShards,
        int numberOfReplicas
    ) {
        Metadata.Builder metadataBuilder = Metadata.builder(clusterState.getMetadata());
        RoutingTable.Builder routingTableBuilder = RoutingTable.builder(clusterState.routingTable());

        IndexMetadata.Builder index = IndexMetadata.builder("test" + indexOrdinal)
            .settings(settings(Version.CURRENT))
            .numberOfShards(numberOfShards)
            .numberOfReplicas(numberOfReplicas);
        IndexMetadata imd = index.build();
        metadataBuilder = metadataBuilder.put(imd, true);
        routingTableBuilder.addAsNew(imd);

        Metadata metadata = metadataBuilder.build();
        clusterState = ClusterState.builder(clusterState).metadata(metadata).routingTable(routingTableBuilder.build()).build();
        clusterState = service.reroute(clusterState, "reroute");

        logger.info("restart all the primary shards, replicas will start initializing");
        clusterState = startInitializingShardsAndReroute(service, clusterState);

        logger.info("start the replica shards");
        clusterState = startInitializingShardsAndReroute(service, clusterState);

        logger.info("complete rebalancing");
        return applyStartedShardsUntilNoChange(clusterState, service);
    }

    private ClusterState removeNodes(ClusterState clusterState, AllocationService service, int numNodes) {
        logger.info("Removing [{}] nodes", numNodes);
        DiscoveryNodes.Builder nodes = DiscoveryNodes.builder(clusterState.nodes());
        ArrayList<DiscoveryNode> discoveryNodes = CollectionUtils.iterableAsArrayList(clusterState.nodes());
        Collections.shuffle(discoveryNodes, random());
        for (DiscoveryNode node : discoveryNodes) {
            nodes.remove(node.getId());
            numNodes--;
            if (numNodes <= 0) {
                break;
            }
        }

        clusterState = ClusterState.builder(clusterState).nodes(nodes.build()).build();
        clusterState = service.disassociateDeadNodes(clusterState, true, "reroute");

        logger.info("start all the primary shards, replicas will start initializing");
        clusterState = startInitializingShardsAndReroute(service, clusterState);

        logger.info("start the replica shards");
        clusterState = startInitializingShardsAndReroute(service, clusterState);

        logger.info("rebalancing");
        clusterState = service.reroute(clusterState, "reroute");

        logger.info("complete rebalancing");
        clusterState = applyStartedShardsUntilNoChange(clusterState, service);

        return clusterState;
    }
}
