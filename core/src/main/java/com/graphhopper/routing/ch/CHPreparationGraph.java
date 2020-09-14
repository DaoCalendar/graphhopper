/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.graphhopper.routing.ch;

import com.carrotsearch.hppc.*;
import com.carrotsearch.hppc.sorting.IndirectComparator;
import com.carrotsearch.hppc.sorting.IndirectSort;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.TurnCost;
import com.graphhopper.routing.util.AllEdgesIterator;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.TurnCostStorage;
import com.graphhopper.util.BitUtil;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.GHUtility;

import java.util.Arrays;

/**
 * Graph data structure used for CH preparation. It allows caching weights and edges that are not needed anymore
 * (those adjacent to contracted nodes) can be removed (see {@link #disconnect}.
 */
public class CHPreparationGraph {
    private final int nodes;
    private final int edges;
    private final boolean edgeBased;
    private final TurnCostFunction turnCostFunction;
    // each edge/shortcut between nodes a/b is represented as a single object and we maintain a list of references
    // to these objects at every node. this needs to be memory-efficient especially for node-based (because there
    // are less shortcuts overall so the size of the prepare graph is crucial, while for edge-based most memory is
    // consumed towards the end of the preparation anyway). for edge-based it would actually be better/faster to keep
    // separate lists of incoming/outgoing edges and even use uni-directional edge-objects.
    private Array2D<PrepareEdge> prepareEdges;
    private IntSet neighborSet;
    private OrigGraph origGraph;
    private OrigGraph.Builder origGraphBuilder;
    private int nextShortcutId;
    private boolean ready;

    public static CHPreparationGraph nodeBased(int nodes, int edges) {
        return new CHPreparationGraph(nodes, edges, false, (in, via, out) -> 0);
    }

    public static CHPreparationGraph edgeBased(int nodes, int edges, TurnCostFunction turnCostFunction) {
        return new CHPreparationGraph(nodes, edges, true, turnCostFunction);
    }

    /**
     * @param nodes (fixed) number of nodes of the graph
     * @param edges the maximum number of (non-shortcut) edges in this graph. edges-1 is the maximum edge id that may
     *              be used.
     */
    private CHPreparationGraph(int nodes, int edges, boolean edgeBased, TurnCostFunction turnCostFunction) {
        this.turnCostFunction = turnCostFunction;
        this.nodes = nodes;
        this.edges = edges;
        this.edgeBased = edgeBased;
        prepareEdges = new Array2D<>(nodes, 2);
        origGraphBuilder = edgeBased ? new OrigGraph.Builder() : null;
        neighborSet = new IntHashSet();
        nextShortcutId = edges;
    }

    public static void buildFromGraph(CHPreparationGraph prepareGraph, Graph graph, Weighting weighting) {
        if (graph.getNodes() != prepareGraph.getNodes())
            throw new IllegalArgumentException("Cannot initialize from given graph. The number of nodes does not match: " +
                    graph.getNodes() + " vs. " + prepareGraph.getNodes());
        if (graph.getEdges() != prepareGraph.getOriginalEdges())
            throw new IllegalArgumentException("Cannot initialize from given graph. The number of edges does not match: " +
                    graph.getEdges() + " vs. " + prepareGraph.getOriginalEdges());
        BooleanEncodedValue accessEnc = weighting.getFlagEncoder().getAccessEnc();
        AllEdgesIterator iter = graph.getAllEdges();
        while (iter.next()) {
            double weightFwd = iter.get(accessEnc) ? weighting.calcEdgeWeight(iter, false) : Double.POSITIVE_INFINITY;
            double weightBwd = iter.getReverse(accessEnc) ? weighting.calcEdgeWeight(iter, true) : Double.POSITIVE_INFINITY;
            prepareGraph.addEdge(iter.getBaseNode(), iter.getAdjNode(), iter.getEdge(), weightFwd, weightBwd);
        }
        prepareGraph.prepareForContraction();
    }

    /**
     * Builds a turn cost function for a given graph('s turn cost storage) and a weighting.
     * The trivial implementation would be simply returning {@link Weighting#calcTurnWeight}. However, it turned out
     * that reading all turn costs for the current encoder and then storing them in separate arrays upfront speeds up
     * edge-based CH preparation by about 25%. See #2084
     */
    public static TurnCostFunction buildTurnCostFunctionFromTurnCostStorage(Graph graph, Weighting weighting) {
        FlagEncoder encoder = weighting.getFlagEncoder();
        String key = TurnCost.key(encoder.toString());
        if (!encoder.hasEncodedValue(key))
            return (inEdge, viaNode, outEdge) -> 0;

        DecimalEncodedValue turnCostEnc = encoder.getDecimalEncodedValue(key);
        TurnCostStorage turnCostStorage = graph.getTurnCostStorage();
        // we maintain a list of inEdge/outEdge/turn-cost triples (we use two arrays for this) that is sorted by nodes
        LongArrayList turnCostEdgePairs = new LongArrayList();
        DoubleArrayList turnCosts = new DoubleArrayList();
        // for each node we store the index of the first turn cost entry/triple in the list
        final int[] turnCostNodes = new int[graph.getNodes() + 1];
        // todonow: get rid of this hack... / obtain the u-turn costs directly from weighting
        double uTurnCosts = weighting.calcTurnWeight(1, 0, 1);
        TurnCostStorage.TurnRelationIterator tcIter = turnCostStorage.getAllTurnRelations();
        int lastNode = -1;
        while (tcIter.next()) {
            int viaNode = tcIter.getViaNode();
            if (viaNode < lastNode)
                throw new IllegalStateException();
            long edgePair = BitUtil.LITTLE.combineIntsToLong(tcIter.getFromEdge(), tcIter.getToEdge());
            double turnCost = tcIter.getCost(turnCostEnc);
            // todonow: do not forget that for pure OSM this is always infinite currently...
            int index = turnCostEdgePairs.size();
            turnCostEdgePairs.add(edgePair);
            turnCosts.add(turnCost);
            if (viaNode != lastNode) {
                for (int i = lastNode + 1; i <= viaNode; i++) {
                    turnCostNodes[i] = index;
                }
            }
            lastNode = viaNode;
        }
        for (int i = lastNode + 1; i <= turnCostNodes.length - 1; i++) {
            turnCostNodes[i] = turnCostEdgePairs.size();
        }
        turnCostNodes[turnCostNodes.length - 1] = turnCostEdgePairs.size();

        return (inEdge, viaNode, outEdge) -> {
            if (!EdgeIterator.Edge.isValid(inEdge) || !EdgeIterator.Edge.isValid(outEdge))
                return 0;
            else if (inEdge == outEdge)
                return uTurnCosts;
            // traverse all turn cost entries we have for this viaNode and return the turn costs if we find a match
            for (int i = turnCostNodes[viaNode]; i < turnCostNodes[viaNode + 1]; i++) {
                long l = turnCostEdgePairs.get(i);
                if (inEdge == BitUtil.LITTLE.getIntLow(l) && outEdge == BitUtil.LITTLE.getIntHigh(l))
                    return turnCosts.get(i);
            }
            return 0;
        };
    }

    public int getNodes() {
        return nodes;
    }

    public int getOriginalEdges() {
        return edges;
    }

    public int getDegree(int node) {
        return prepareEdges.size(node);
    }

    public void addEdge(int from, int to, int edge, double weightFwd, double weightBwd) {
        checkNotReady();
        boolean fwd = Double.isFinite(weightFwd);
        boolean bwd = Double.isFinite(weightBwd);
        if (!fwd && !bwd)
            return;
        // todonow: is it ok to cast to float? maybe add some check that asserts certain precision? especially inf?
        PrepareBaseEdge prepareEdge = new PrepareBaseEdge(edge, from, to, (float) weightFwd, (float) weightBwd);
        prepareEdges.add(from, prepareEdge);
        if (from != to)
            prepareEdges.add(to, prepareEdge);

        if (edgeBased)
            origGraphBuilder.addEdge(from, to, edge, fwd, bwd);
    }

    public int addShortcut(int from, int to, int origEdgeKeyFirst, int origEdgeKeyLast, int skipped1,
                           int skipped2, double weight, int origEdgeCount) {
        checkReady();
        PrepareEdge prepareEdge = edgeBased
                ? new EdgeBasedPrepareShortcut(nextShortcutId, from, to, origEdgeKeyFirst, origEdgeKeyLast, weight, skipped1, skipped2, origEdgeCount)
                : new PrepareShortcut(nextShortcutId, from, to, weight, skipped1, skipped2, origEdgeCount);
        prepareEdges.add(from, prepareEdge);
        if (from != to)
            prepareEdges.add(to, prepareEdge);
        return nextShortcutId++;
    }

    public void prepareForContraction() {
        checkNotReady();
        origGraph = edgeBased ? origGraphBuilder.build() : null;
        origGraphBuilder = null;
        // todo: performance - maybe sort the edges in some clever way?
        ready = true;
    }

    public PrepareGraphEdgeExplorer createOutEdgeExplorer() {
        checkReady();
        return new PrepareGraphEdgeExplorerImpl(prepareEdges, false);
    }

    public PrepareGraphEdgeExplorer createInEdgeExplorer() {
        checkReady();
        return new PrepareGraphEdgeExplorerImpl(prepareEdges, true);
    }

    public PrepareGraphOrigEdgeExplorer createOutOrigEdgeExplorer() {
        checkReady();
        if (!edgeBased)
            throw new IllegalStateException("orig out explorer is not available for node-based graph");
        return origGraph.createOutOrigEdgeExplorer();
    }

    public PrepareGraphOrigEdgeExplorer createInOrigEdgeExplorer() {
        checkReady();
        if (!edgeBased)
            throw new IllegalStateException("orig in explorer is not available for node-based graph");
        return origGraph.createInOrigEdgeExplorer();
    }

    public double getTurnWeight(int inEdge, int viaNode, int outEdge) {
        return turnCostFunction.getTurnWeight(inEdge, viaNode, outEdge);
    }

    public IntContainer disconnect(int node) {
        checkReady();
        // we use this neighbor set to guarantee a deterministic order of the returned
        // node ids
        neighborSet.clear();
        IntArrayList neighbors = new IntArrayList(getDegree(node));
        for (int i = 0; i < prepareEdges.size(node); i++) {
            PrepareEdge prepareEdge = prepareEdges.get(node, i);
            int adjNode = prepareEdge.getNodeB();
            if (adjNode == node)
                adjNode = prepareEdge.getNodeA();
            if (adjNode == node)
                // this is a loop
                continue;
            prepareEdges.remove(adjNode, prepareEdge);
            if (neighborSet.add(adjNode))
                neighbors.add(adjNode);
        }
        prepareEdges.clear(node);
        return neighbors;
    }

    public void close() {
        checkReady();
        prepareEdges = null;
        neighborSet = null;
        if (edgeBased)
            origGraph = null;
    }

    private void checkReady() {
        if (!ready)
            throw new IllegalStateException("You need to call prepareForContraction() before calling this method");
    }

    private void checkNotReady() {
        if (ready)
            throw new IllegalStateException("You cannot call this method after calling prepareForContraction()");
    }

    @FunctionalInterface
    public interface TurnCostFunction {
        double getTurnWeight(int inEdge, int viaNode, int outEdge);
    }

    private static class PrepareGraphEdgeExplorerImpl implements PrepareGraphEdgeExplorer, PrepareGraphEdgeIterator {
        private final Array2D<PrepareEdge> prepareEdges;
        private final boolean reverse;
        private int node = -1;
        private PrepareEdge currEdge;
        private int index;

        PrepareGraphEdgeExplorerImpl(Array2D<PrepareEdge> prepareEdges, boolean reverse) {
            this.prepareEdges = prepareEdges;
            this.reverse = reverse;
        }

        @Override
        public PrepareGraphEdgeIterator setBaseNode(int node) {
            this.node = node;
            this.index = -1;
            return this;
        }

        @Override
        public boolean next() {
            while (true) {
                index++;
                if (index >= prepareEdges.size(node)) {
                    currEdge = null;
                    return false;
                }
                currEdge = prepareEdges.get(node, index);
                if (!currEdge.isShortcut())
                    return true;
                if ((!reverse && nodeAisBase()) || (reverse && currEdge.getNodeB() == node))
                    return true;
            }
        }

        @Override
        public int getBaseNode() {
            return node;
        }

        @Override
        public int getAdjNode() {
            return nodeAisBase() ? currEdge.getNodeB() : currEdge.getNodeA();
        }

        @Override
        public int getPrepareEdge() {
            return currEdge.getPrepareEdge();
        }

        @Override
        public boolean isShortcut() {
            return currEdge.isShortcut();
        }

        @Override
        public int getOrigEdgeKeyFirst() {
            return nodeAisBase() ? currEdge.getOrigEdgeKeyFirstAB() : currEdge.getOrigEdgeKeyFirstBA();
        }

        @Override
        public int getOrigEdgeKeyLast() {
            if (nodeAisBase())
                return currEdge.getOrigEdgeKeyLastAB();
            else
                return currEdge.getOrigEdgeKeyLastBA();
        }

        @Override
        public int getSkipped1() {
            return currEdge.getSkipped1();
        }

        @Override
        public int getSkipped2() {
            return currEdge.getSkipped2();
        }

        @Override
        public double getWeight() {
            if (!reverse && nodeAisBase())
                return currEdge.getWeightAB();
            else if (!reverse)
                return currEdge.getWeightBA();
            else if (nodeAisBase())
                return currEdge.getWeightBA();
            else
                return currEdge.getWeightAB();
        }

        @Override
        public int getOrigEdgeCount() {
            return currEdge.getOrigEdgeCount();
        }

        @Override
        public void setSkippedEdges(int skipped1, int skipped2) {
            currEdge.setSkipped1(skipped1);
            currEdge.setSkipped2(skipped2);
        }

        @Override
        public void setWeight(double weight) {
            assert Double.isFinite(weight);
            currEdge.setWeight(weight);
        }

        @Override
        public void setOrigEdgeCount(int origEdgeCount) {
            currEdge.setOrigEdgeCount(origEdgeCount);
        }

        @Override
        public String toString() {
            return index < 0 ? "not_started" : getBaseNode() + "-" + getAdjNode();
        }

        private boolean nodeAisBase() {
            // in some cases we need to determine which direction of the (bidirectional) edge we want
            return currEdge.getNodeA() == node;
        }
    }

    interface PrepareEdge {
        boolean isShortcut();

        int getPrepareEdge();

        int getNodeA();

        int getNodeB();

        double getWeightAB();

        double getWeightBA();

        int getOrigEdgeKeyFirstAB();

        int getOrigEdgeKeyFirstBA();

        int getOrigEdgeKeyLastAB();

        int getOrigEdgeKeyLastBA();

        int getSkipped1();

        int getSkipped2();

        int getOrigEdgeCount();

        void setSkipped1(int skipped1);

        void setSkipped2(int skipped2);

        void setWeight(double weight);

        void setOrigEdgeCount(int origEdgeCount);
    }

    public static class PrepareBaseEdge implements PrepareEdge {
        private final int prepareEdge;
        private final int nodeA;
        private final int nodeB;
        private final float weightAB;
        private final float weightBA;

        public PrepareBaseEdge(int prepareEdge, int nodeA, int nodeB, float weightAB, float weightBA) {
            this.prepareEdge = prepareEdge;
            this.nodeA = nodeA;
            this.nodeB = nodeB;
            this.weightAB = weightAB;
            this.weightBA = weightBA;
        }

        @Override
        public boolean isShortcut() {
            return false;
        }

        @Override
        public int getPrepareEdge() {
            return prepareEdge;
        }

        @Override
        public int getNodeA() {
            return nodeA;
        }

        @Override
        public int getNodeB() {
            return nodeB;
        }

        @Override
        public double getWeightAB() {
            return weightAB;
        }

        @Override
        public double getWeightBA() {
            return weightBA;
        }

        @Override
        public int getOrigEdgeKeyFirstAB() {
            int keyFwd = prepareEdge << 1;
            if (nodeA > nodeB)
                keyFwd += 1;
            return keyFwd;
        }

        @Override
        public int getOrigEdgeKeyFirstBA() {
            int keyBwd = prepareEdge << 1;
            if (nodeB > nodeA)
                keyBwd += 1;
            return keyBwd;
        }

        @Override
        public int getOrigEdgeKeyLastAB() {
            int keyFwd = prepareEdge << 1;
            if (nodeA > nodeB)
                keyFwd += 1;
            return keyFwd;
        }

        @Override
        public int getOrigEdgeKeyLastBA() {
            int keyBwd = prepareEdge << 1;
            if (nodeB > nodeA)
                keyBwd += 1;
            return keyBwd;
        }

        @Override
        public int getSkipped1() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getSkipped2() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getOrigEdgeCount() {
            return 1;
        }

        @Override
        public void setSkipped1(int skipped1) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setSkipped2(int skipped2) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setWeight(double weight) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setOrigEdgeCount(int origEdgeCount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String toString() {
            return nodeA + "-" + nodeB + " (" + prepareEdge + ") " + weightAB + " " + weightBA;
        }
    }

    private static class PrepareShortcut implements PrepareEdge {
        private final int prepareEdge;
        private final int from;
        private final int to;
        private double weight;
        private int skipped1;
        private int skipped2;
        private int origEdgeCount;

        private PrepareShortcut(int prepareEdge, int from, int to, double weight, int skipped1, int skipped2, int origEdgeCount) {
            this.prepareEdge = prepareEdge;
            this.from = from;
            this.to = to;
            assert Double.isFinite(weight);
            this.weight = weight;
            this.skipped1 = skipped1;
            this.skipped2 = skipped2;
            this.origEdgeCount = origEdgeCount;
        }

        @Override
        public boolean isShortcut() {
            return true;
        }

        @Override
        public int getPrepareEdge() {
            return prepareEdge;
        }

        @Override
        public int getNodeA() {
            return from;
        }

        @Override
        public int getNodeB() {
            return to;
        }

        @Override
        public double getWeightAB() {
            return weight;
        }

        @Override
        public double getWeightBA() {
            return weight;
        }

        @Override
        public int getOrigEdgeKeyFirstAB() {
            throw new IllegalStateException("Not supported for node-based shortcuts");
        }

        @Override
        public int getOrigEdgeKeyFirstBA() {
            throw new IllegalStateException("Not supported for node-based shortcuts");
        }

        @Override
        public int getOrigEdgeKeyLastAB() {
            throw new IllegalStateException("Not supported for node-based shortcuts");
        }

        @Override
        public int getOrigEdgeKeyLastBA() {
            throw new IllegalStateException("Not supported for node-based shortcuts");
        }

        @Override
        public int getSkipped1() {
            return skipped1;
        }

        @Override
        public int getSkipped2() {
            return skipped2;
        }

        @Override
        public int getOrigEdgeCount() {
            return origEdgeCount;
        }

        @Override
        public void setSkipped1(int skipped1) {
            this.skipped1 = skipped1;
        }

        @Override
        public void setSkipped2(int skipped2) {
            this.skipped2 = skipped2;
        }

        @Override
        public void setWeight(double weight) {
            this.weight = weight;
        }

        @Override
        public void setOrigEdgeCount(int origEdgeCount) {
            this.origEdgeCount = origEdgeCount;
        }

        @Override
        public String toString() {
            return from + "-" + to + " " + weight;
        }
    }

    private static class EdgeBasedPrepareShortcut extends PrepareShortcut {
        // we use this subclass to save some memory for node-based where these are not needed
        private final int origEdgeKeyFirst;
        private final int origEdgeKeyLast;

        public EdgeBasedPrepareShortcut(int prepareEdge, int from, int to, int origEdgeKeyFirst, int origEdgeKeyLast,
                                        double weight, int skipped1, int skipped2, int origEdgeCount) {
            super(prepareEdge, from, to, weight, skipped1, skipped2, origEdgeCount);
            this.origEdgeKeyFirst = origEdgeKeyFirst;
            this.origEdgeKeyLast = origEdgeKeyLast;
        }

        @Override
        public int getOrigEdgeKeyFirstAB() {
            return origEdgeKeyFirst;
        }

        @Override
        public int getOrigEdgeKeyFirstBA() {
            return origEdgeKeyFirst;
        }

        @Override
        public int getOrigEdgeKeyLastAB() {
            return origEdgeKeyLast;
        }

        @Override
        public int getOrigEdgeKeyLastBA() {
            return origEdgeKeyLast;
        }

        @Override
        public String toString() {
            return getNodeA() + "-" + getNodeB() + " (" + origEdgeKeyFirst + ", " + origEdgeKeyLast + ") " + getWeightAB();
        }
    }

    private static class OrigGraph {
        private final IntArrayList firstEdgesByNode;
        private final IntArrayList adjNodes;
        private final IntArrayList edgesAndFlags;

        private OrigGraph(IntArrayList firstEdgesByNode, IntArrayList adjNodes, IntArrayList edgesAndFlags) {
            this.firstEdgesByNode = firstEdgesByNode;
            this.adjNodes = adjNodes;
            this.edgesAndFlags = edgesAndFlags;
        }

        PrepareGraphOrigEdgeExplorer createOutOrigEdgeExplorer() {
            return new OrigEdgeIteratorImpl(this, false);
        }

        PrepareGraphOrigEdgeExplorer createInOrigEdgeExplorer() {
            return new OrigEdgeIteratorImpl(this, true);
        }

        static class Builder {
            private final IntArrayList fromNodes = new IntArrayList();
            private final IntArrayList toNodes = new IntArrayList();
            private final IntArrayList edgesAndFlags = new IntArrayList();
            private int maxFrom = -1;
            private int maxTo = -1;

            void addEdge(int from, int to, int edge, boolean fwd, boolean bwd) {
                fromNodes.add(from);
                toNodes.add(to);
                edgesAndFlags.add(getEdgeWithFlags(edge, fwd, bwd));
                maxFrom = Math.max(maxFrom, from);
                maxTo = Math.max(maxTo, to);

                fromNodes.add(to);
                toNodes.add(from);
                edgesAndFlags.add(getEdgeWithFlags(edge, bwd, fwd));
                maxFrom = Math.max(maxFrom, to);
                maxTo = Math.max(maxTo, from);
            }

            OrigGraph build() {
                int[] sortOrder = IndirectSort.mergesort(0, fromNodes.elementsCount, new IndirectComparator.AscendingIntComparator(fromNodes.buffer));
                sortAndTrim(fromNodes, sortOrder);
                sortAndTrim(toNodes, sortOrder);
                sortAndTrim(edgesAndFlags, sortOrder);
                return new OrigGraph(buildFirstEdgesByNode(), toNodes, edgesAndFlags);
            }

            private int getEdgeWithFlags(int edge, boolean fwd, boolean bwd) {
                // we use only 30 bits for the edge Id and store two access flags along with the same int
                if (edge >= Integer.MAX_VALUE >> 2)
                    throw new IllegalArgumentException("Maximum edge ID exceeded: " + Integer.MAX_VALUE);
                edge <<= 1;
                if (fwd)
                    edge++;
                edge <<= 1;
                if (bwd)
                    edge++;
                return edge;
            }

            private IntArrayList buildFirstEdgesByNode() {
                // it is assumed the edges have been sorted already
                final int numFroms = maxFrom + 1;
                IntArrayList firstEdgesByNode = new IntArrayList(numFroms + 1);
                firstEdgesByNode.elementsCount = numFroms + 1;
                int numEdges = fromNodes.size();
                if (numFroms == 0) {
                    firstEdgesByNode.set(0, numEdges);
                    return firstEdgesByNode;
                }
                int edgeIndex = 0;
                for (int from = 0; from < numFroms; from++) {
                    while (edgeIndex < numEdges && fromNodes.get(edgeIndex) < from) {
                        edgeIndex++;
                    }
                    firstEdgesByNode.set(from, edgeIndex);
                }
                firstEdgesByNode.set(numFroms, numEdges);
                return firstEdgesByNode;
            }

        }
    }

    private static class OrigEdgeIteratorImpl implements PrepareGraphOrigEdgeExplorer, PrepareGraphOrigEdgeIterator {
        private final OrigGraph graph;
        private final boolean reverse;
        private int node;
        private int endEdge;
        private int index;

        public OrigEdgeIteratorImpl(OrigGraph graph, boolean reverse) {
            this.graph = graph;
            this.reverse = reverse;
        }

        @Override
        public PrepareGraphOrigEdgeIterator setBaseNode(int node) {
            this.node = node;
            index = graph.firstEdgesByNode.get(node) - 1;
            endEdge = graph.firstEdgesByNode.get(node + 1);
            return this;
        }

        @Override
        public boolean next() {
            while (true) {
                index++;
                if (index >= endEdge)
                    return false;
                if (hasAccess())
                    return true;
            }
        }

        @Override
        public int getBaseNode() {
            return node;
        }

        @Override
        public int getAdjNode() {
            return graph.adjNodes.get(index);
        }

        @Override
        public int getOrigEdgeKeyFirst() {
            int e = graph.edgesAndFlags.get(index);
            return GHUtility.createEdgeKey(node, getAdjNode(), e >> 2, false);
        }

        @Override
        public int getOrigEdgeKeyLast() {
            return getOrigEdgeKeyFirst();
        }

        private boolean hasAccess() {
            int e = graph.edgesAndFlags.get(index);
            if (reverse) {
                return (e & 0b01) == 0b01;
            } else {
                return (e & 0b10) == 0b10;
            }
        }
    }

    private static void sortAndTrim(IntArrayList arr, int[] sortOrder) {
        arr.buffer = applySortOrder(sortOrder, arr.buffer);
        arr.elementsCount = arr.buffer.length;
    }

    private static int[] applySortOrder(int[] sortOrder, int[] arr) {
        if (sortOrder.length > arr.length) {
            throw new IllegalArgumentException("sort order must not be shorter than array");
        }
        int[] result = new int[sortOrder.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = arr[sortOrder[i]];
        }
        return result;
    }

    /**
     * This is a more memory-efficient version of `ArrayList<T>[]`, i.e. this is a fixed size array with variable
     * sized sub-arrays. It is more memory efficient than an array of `ArrayList`s, because it saves the object-overhead
     * of using an ArrayList object for each sub-array.
     */
    private static class Array2D<T> {
        private static final int GROW_FACTOR = 2;
        private final int initialSubArrayCapacity;
        private final Object[][] data;
        private final int[] sizes;

        Array2D(int size, int initialSubArrayCapacity) {
            data = new Object[size][];
            sizes = new int[size];
            this.initialSubArrayCapacity = initialSubArrayCapacity;
        }

        int size(int subArray) {
            return sizes[subArray];
        }

        void add(int subArray, T element) {
            if (data[subArray] == null) {
                data[subArray] = new Object[initialSubArrayCapacity];
                data[subArray][0] = element;
                sizes[subArray] = 1;
            } else {
                assert data[subArray].length != 0;
                if (sizes[subArray] == data[subArray].length)
                    data[subArray] = Arrays.copyOf(data[subArray], data[subArray].length * GROW_FACTOR);
                data[subArray][sizes[subArray]] = element;
                sizes[subArray]++;
            }
        }

        T get(int subArray, int element) {
            return (T) data[subArray][element];
        }

        /**
         * Removes the given element from the given sub-array. Using this method changes the order of the existing elements
         * in the sub-array unless we remove the very last element!
         */
        void remove(int subArray, T element) {
            for (int i = 0; i < sizes[subArray]; i++) {
                if (data[subArray][i] == element) {
                    data[subArray][i] = data[subArray][sizes[subArray] - 1];
                    data[subArray][sizes[subArray] - 1] = null;
                    sizes[subArray]--;
                }
            }
        }

        void clear(int subArray) {
            data[subArray] = null;
            sizes[subArray] = 0;
        }

    }
}
