/*
 * Copyright (C) 2020 Grakn Labs
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
 *
 */

package grakn.core.traversal;

import grakn.core.common.async.Producer;
import grakn.core.common.exception.GraknException;
import grakn.core.common.iterator.SynchronisedIterator;
import grakn.core.graph.GraphManager;
import grakn.core.graph.vertex.Vertex;
import grakn.core.traversal.procedure.Procedure;
import grakn.core.traversal.procedure.ProcedureVertex;
import graql.lang.pattern.variable.Reference;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static grakn.core.common.exception.ErrorMessage.Internal.ILLEGAL_STATE;
import static grakn.core.common.iterator.Iterators.synchronised;
import static java.util.concurrent.CompletableFuture.runAsync;

public class TraversalProducer implements Producer<Map<Reference, Vertex<?, ?>>> {

    private final int parallelisation;
    private final GraphManager graphMgr;
    private final Procedure procedure;
    private final Traversal.Parameters parameters;
    private final SynchronisedIterator<? extends Vertex<?, ?>> start;
    private final Map<TraversalIterator, CompletableFuture<Void>> futures;
    private final AtomicBoolean isDone;

    public TraversalProducer(GraphManager graphMgr, Procedure procedure, Traversal.Parameters parameters, int parallelisation) {
        assert parallelisation > 0;
        this.graphMgr = graphMgr;
        this.procedure = procedure;
        this.parameters = parameters;
        this.parallelisation = parallelisation;
        this.isDone = new AtomicBoolean(false);
        this.futures = new ConcurrentHashMap<>();
        this.start = synchronised(procedure.vertices().filter(ProcedureVertex::isStartingVertex)
                                          .findAny().orElseThrow(() -> GraknException.of(ILLEGAL_STATE))
                                          .execute(graphMgr, parameters));
    }

    @Override
    public void produce(Sink<Map<Reference, Vertex<?, ?>>> sink, int count) {
        int splitCount = (int) Math.ceil((double) count / parallelisation);

        if (futures.isEmpty()) {
            if (!start.hasNext()) sink.done();
            int i = 0;
            for (; i < parallelisation && start.hasNext(); i++) {
                TraversalIterator iterator = new TraversalIterator(graphMgr, start.next(), procedure, parameters);
                futures.computeIfAbsent(iterator, k -> runAsync(consume(iterator, splitCount, sink)));
            }
            produce(sink, (parallelisation - i) * splitCount);
        } else {
            for (TraversalIterator iterator : futures.keySet()) {
                futures.computeIfPresent(iterator, (k, v) -> v.thenRun(consume(k, splitCount, sink)));
            }
        }
    }

    private Runnable consume(TraversalIterator iterator, int count, Sink<Map<Reference, Vertex<?, ?>>> sink) {
        return () -> {
            int j = 0;
            for (; j < count; j++) {
                if (iterator.hasNext()) sink.put(iterator.next());
                else break;
            }
            if (count - j > 0) {
                compensate(iterator, count - j, sink);
            }
        };
    }

    private void compensate(TraversalIterator completedIterator, int remaining, Sink<Map<Reference, Vertex<?, ?>>> sink) {
        futures.remove(completedIterator);
        Vertex<?, ?> next;
        if ((next = start.atomicNext()) != null) {
            TraversalIterator iterator = new TraversalIterator(graphMgr, next, procedure, parameters);
            futures.put(iterator, runAsync(consume(iterator, remaining, sink)));
        } else if (futures.isEmpty()) {
            done(sink);
        } else {
            produce(sink, remaining);
        }
    }

    private void done(Sink<Map<Reference, Vertex<?, ?>>> sink) {
        if (isDone.compareAndSet(false, true)) {
            sink.done();
        }
    }

    @Override
    public void recycle() {
        start.recycle();
        futures.keySet().forEach(TraversalIterator::recycle);
    }
}
