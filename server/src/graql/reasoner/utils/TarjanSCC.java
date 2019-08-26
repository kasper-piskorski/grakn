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
import com.google.common.collect.Iterables;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;

/**
 * Tarjan's Strongly Connected Components algorithm
 * https://en.wikipedia.org/wiki/Tarjan%27s_strongly_connected_components_algorithm
 *
 * Here used to find cycles in the graph.
 *
 * @param <T> type of the graph node
 */
public  class TarjanSCC<T> {

    private final Set<T> visited = new HashSet<>();
    private final Stack<T> stack = new Stack<>();
    private final HashMap<T, Integer> lowLink = new HashMap<>();

    private final List<Set<T>> scc = new ArrayList<>();
    private int currentIndex = 0;

    private final HashMultimap<T, T> graph;

    public TarjanSCC(HashMultimap<T, T> graph) {
        this.graph = graph;
        for (T node : graph.keySet()) {
            if (!visited.contains(node)) dfs(node);
        }
    }

    public List<Set<T>> getSCC(){ return scc;}

    /**
     * A cycle isa a connected component that has more than one vertex or a single vertex linked to itself.
     * @return list of cycles in the graph
     */
    public List<Set<T>> getCycles() {
        return scc.stream().filter(cc ->
                cc.size() > 1
                        || graph.get(Iterables.getOnlyElement(cc)).contains(Iterables.getOnlyElement(cc))
        )
                .collect(Collectors.toList());
    }

    private final HashMultimap<T, T> successors = HashMultimap.create();

    /**
     * @return the successor map - transitive closure over the type graph - all types that are reachable from a given type
     */
    public HashMultimap<T, T> successorMap(){ return successors;}

    private void dfs(T node) {
        visited.add(node);
        lowLink.put(node, currentIndex++);

        int rootIndex = lowLink.get(node);
        stack.push(node);
        successors.putAll(node, graph.get(node));

        //look at neighbours of v
        for (T neighbour : graph.get(node)) {
            if (!visited.contains(neighbour)) dfs(neighbour);

            Integer neighbourLowLink = lowLink.get(neighbour);
            if (neighbourLowLink < rootIndex) rootIndex = neighbourLowLink;

            successors.putAll(node, successors.get(neighbour));
        }
        if (rootIndex < lowLink.get(node)) {
            lowLink.put(node, rootIndex);
            return;
        }
        T w;
        Set<T> component = new HashSet<>();
        do {
            w = stack.pop();
            component.add(w);
            lowLink.put(w, graph.keySet().size());
            successors.putAll(node, successors.get(w));
        } while (w != node);
        scc.add(component);
    }
}
