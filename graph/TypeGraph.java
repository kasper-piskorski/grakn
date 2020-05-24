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

package hypergraph.graph;

import hypergraph.common.concurrent.ManagedReadWriteLock;
import hypergraph.common.exception.HypergraphException;
import hypergraph.graph.util.IID;
import hypergraph.graph.util.Schema;
import hypergraph.graph.util.Storage;
import hypergraph.graph.vertex.TypeVertex;
import hypergraph.graph.vertex.Vertex;
import hypergraph.graph.vertex.impl.TypeVertexImpl;

import javax.annotation.Nullable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static hypergraph.graph.util.IID.Vertex.Type.generate;
import static hypergraph.graph.util.Schema.Vertex.Type.scopedLabel;

public class TypeGraph implements Graph<IID.Vertex.Type, TypeVertex> {

    private final Graphs graphManager;
    private final ConcurrentMap<String, TypeVertex> typeByLabel;
    private final ConcurrentMap<IID.Vertex.Type, TypeVertex> typeByIID;
    private final ConcurrentMap<String, ManagedReadWriteLock> singleLabelLocks;
    private final ManagedReadWriteLock multiLabelLock;

    TypeGraph(Graphs graphManager) {
        this.graphManager = graphManager;
        typeByLabel = new ConcurrentHashMap<>();
        typeByIID = new ConcurrentHashMap<>();
        singleLabelLocks = new ConcurrentHashMap<>();
        multiLabelLock = new ManagedReadWriteLock();
    }

    public ThingGraph thingGraph() {
        return graphManager.thing();
    }

    @Override
    public Storage storage() {
        return graphManager.storage();
    }

    public boolean isInitialised() throws HypergraphException {
        return get(Schema.Vertex.Type.Root.THING.label()) != null;
    }

    public void initialise() throws HypergraphException {
        TypeVertex rootThingType = insert(
                Schema.Vertex.Type.THING_TYPE,
                Schema.Vertex.Type.Root.THING.label()).isAbstract(true);
        TypeVertex rootEntityType = insert(
                Schema.Vertex.Type.ENTITY_TYPE,
                Schema.Vertex.Type.Root.ENTITY.label()).isAbstract(true);
        TypeVertex rootAttributeType = insert(
                Schema.Vertex.Type.ATTRIBUTE_TYPE,
                Schema.Vertex.Type.Root.ATTRIBUTE.label()).isAbstract(true).valueType(Schema.ValueType.OBJECT);
        TypeVertex rootRelationType = insert(
                Schema.Vertex.Type.RELATION_TYPE,
                Schema.Vertex.Type.Root.RELATION.label()).isAbstract(true);
        TypeVertex rootRoleType = insert(
                Schema.Vertex.Type.ROLE_TYPE,
                Schema.Vertex.Type.Root.ROLE.label(),
                Schema.Vertex.Type.Root.RELATION.label()).isAbstract(true);

        rootEntityType.outs().put(Schema.Edge.Type.SUB, rootThingType);
        rootAttributeType.outs().put(Schema.Edge.Type.SUB, rootThingType);
        rootRelationType.outs().put(Schema.Edge.Type.SUB, rootThingType);
        rootRelationType.outs().put(Schema.Edge.Type.RELATES, rootRoleType);
    }

    @Override
    public TypeVertex convert(IID.Vertex.Type iid) {
        return typeByIID.computeIfAbsent(iid, i -> {
            TypeVertex vertex = new TypeVertexImpl.Persisted(this, i);
            typeByLabel.putIfAbsent(vertex.scopedLabel(), vertex);
            return vertex;
        });
    }

    public TypeVertex get(String label) {
        return get(label, null);
    }

    public TypeVertex get(String label, @Nullable String scope) {
        String scopedLabel = scopedLabel(label, scope);
        try {
            multiLabelLock.lockRead();
            singleLabelLocks.computeIfAbsent(scopedLabel, x -> new ManagedReadWriteLock()).lockRead();

            TypeVertex vertex = typeByLabel.get(scopedLabel);
            if (vertex != null) return vertex;

            byte[] iid = graphManager.storage().get(IID.Index.Type.of(label, scope).bytes());
            if (iid != null) {
                vertex = typeByIID.computeIfAbsent(
                        IID.Vertex.Type.of(iid), i -> new TypeVertexImpl.Persisted(this, i, label, scope)
                );
                typeByLabel.putIfAbsent(scopedLabel, vertex);
            }

            return vertex;
        } catch (InterruptedException e) {
            throw new HypergraphException(e);
        } finally {
            singleLabelLocks.get(scopedLabel).unlockRead();
            multiLabelLock.unlockRead();
        }
    }

    public TypeVertex insert(Schema.Vertex.Type type, String label) {
        return insert(type, label, null);
    }

    public TypeVertex insert(Schema.Vertex.Type type, String label, @Nullable String scope) {
        String scopedLabel = scopedLabel(label, scope);
        try { // we intentionally use READ on multiLabelLock, as put() only concerns one label
            multiLabelLock.lockRead();
            singleLabelLocks.computeIfAbsent(scopedLabel, x -> new ManagedReadWriteLock()).lockWrite();

            TypeVertex typeVertex = typeByLabel.computeIfAbsent(scopedLabel, i -> new TypeVertexImpl.Buffered(
                    this, type, generate(graphManager.keyGenerator(), type), label, scope
            ));
            typeByIID.put(typeVertex.iid(), typeVertex);
            return typeVertex;
        } catch (InterruptedException e) {
            throw new HypergraphException(e);
        } finally {
            singleLabelLocks.get(scopedLabel).unlockWrite();
            multiLabelLock.unlockRead();
        }
    }

    public TypeVertex update(TypeVertex vertex, String oldLabel, @Nullable String oldScope, String newLabel, @Nullable String newScope) {
        String oldScopedLabel = scopedLabel(oldLabel, oldScope);
        String newScopedLabel = scopedLabel(newLabel, newScope);
        try {
            multiLabelLock.lockWrite();
            if (typeByLabel.containsKey(newScopedLabel)) throw new HypergraphException(
                    String.format("Invalid Write Operation: index '%s' is already in use", newScopedLabel));
            typeByLabel.remove(oldScopedLabel);
            typeByLabel.put(newScopedLabel, vertex);
            return vertex;
        } catch (InterruptedException e) {
            throw new HypergraphException(e);
        } finally {
            multiLabelLock.unlockWrite();
        }
    }

    @Override
    public void delete(TypeVertex vertex) {
        try { // we intentionally use READ on multiLabelLock, as delete() only concerns one label
            multiLabelLock.lockRead();
            singleLabelLocks.computeIfAbsent(vertex.scopedLabel(), x -> new ManagedReadWriteLock()).lockWrite();

            typeByLabel.remove(vertex.scopedLabel());
            typeByIID.remove(vertex.iid());
        } catch (InterruptedException e) {
            throw new HypergraphException(e);
        } finally {
            singleLabelLocks.get(vertex.scopedLabel()).unlockWrite();
            multiLabelLock.unlockRead();
        }
    }

    @Override
    public void commit() {
        typeByIID.values().parallelStream().filter(v -> v.status().equals(Schema.Status.BUFFERED)).forEach(
                vertex -> vertex.iid(generate(graphManager.storage().keyGenerator(), vertex.schema()))
        ); // typeByIID no longer contains valid mapping from IID to TypeVertex
        typeByIID.values().parallelStream().forEach(Vertex::commit);
        clear(); // we now flush the indexes after commit, and we do not expect this Graph.Type to be used again
    }

    @Override
    public void clear() {
        typeByIID.clear();
        typeByLabel.clear();
    }
}
