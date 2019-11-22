/*
 * GRAKN.AI - THE KNOWLEDGE GRAPH
 * Copyright (C) 2019 Grakn Labs Ltd
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package grakn.core.graql.reasoner.utils;

import com.google.common.collect.HashMultimap;
import grakn.common.util.Pair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;

/**
 * Tarjan's Strongly Connected Components algorithm
 * https://en.wikipedia.org/wiki/Tarjan%27s_strongly_connected_components_algorithm
 *
 * Here used to find cycles in the graph.
 *
 * @param <T> type of the graph node
 */
public  class TarjanReachability<T> implements Iterator<Pair<T,T>>{

    private final Set<T> visited = new HashSet<>();
    private final Stack<T> stack = new Stack<>();
    private final HashMap<T, Integer> lowLink = new HashMap<>();
    private final HashMultimap<T, T> successors = HashMultimap.create();
    private int currentIndex = 0;

    private final Function<T, Stream<T>> graph;

    private final Stack<T> nodes;
    private final Set<T> newNodes;
    private final T startNode;
    private final T endNode;
    private List<Pair<T, T>> newSuccessors = new ArrayList<>();
    private Iterator<Pair<T, T>> successorIterator = Collections.emptyIterator();

    public TarjanReachability(T startNode, @Nullable T endNode, Function<T, Stream<T>> fetchNeighbours) {
        this.graph = fetchNeighbours;
        this.nodes = new Stack<>();
        this.newNodes = Collections.singleton(startNode);
        this.startNode = startNode;
        this.endNode = endNode;
        nodes.push(startNode);
    }

    @Override
    public boolean hasNext() {
        while(!nodes.isEmpty()) {
            if (successorIterator.hasNext()) return true;
            T node = nodes.pop();

            if (!visited.contains(node)) dfs(node);
            successorIterator = newSuccessors.iterator();
            newSuccessors = new ArrayList<>();
        }
        return false;
    }

    public Stream<Pair<T,T>> stream() {
        Iterable<Pair<T,T>> iterable = () -> this;
        return StreamSupport.stream(iterable.spliterator(), false);
    }

    @Override
    public Pair<T, T> next() {
        return successorIterator.next();
    }

    private void updateSuccessors(T node, Set<T> nodes){
        Set<T> nodesToAck = nodes.stream()
                .filter(n -> successors.put(node, n))
                .collect(Collectors.toSet());
        if (startNode.equals(node)) {
            nodesToAck.stream()
                    .filter(n -> endNode == null || n.equals(endNode))
                    .forEach(n -> newSuccessors.add(new Pair<>(node, n)));
        }
    }

    private Set<T> getNeighbours(T node){
        Set<T> neighbours = new HashSet<>();
        graph.apply(node)
                .forEach(n -> {
                    if (!newNodes.contains(n)) nodes.push(n);
                    neighbours.add(n);
                });
        return neighbours;
    }

    private void dfs(T node) {
        visited.add(node);
        lowLink.put(node, currentIndex++);
        int rootIndex = lowLink.get(node);
        stack.push(node);

        Set<T> neighbours = getNeighbours(node);
        updateSuccessors(node, neighbours);
        //look at neighbours of v
        for (T n : neighbours) {
            if (!visited.contains(n)) dfs(n);
            if (lowLink.get(n) < rootIndex) rootIndex = lowLink.get(n);
            updateSuccessors(node, successors.get(n));
        }
        if (rootIndex < lowLink.get(node)) {
            lowLink.put(node, rootIndex);
            return;
        }
        T w;
        do {
            w = stack.pop();
            lowLink.put(w, Integer.MAX_VALUE);
            updateSuccessors(node, successors.get(w));
        } while (w != node);
    }
}