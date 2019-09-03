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
package grakn.core.server.kb.structure;

import grakn.core.server.exception.PropertyNotUniqueException;
import grakn.core.server.exception.TransactionException;
import grakn.core.server.session.TransactionOLTP;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.function.Function;

import static org.apache.tinkerpop.gremlin.structure.T.id;

/**
 * TransactionOLTP AbstractElement
 * Base class used to represent a construct in the graph. This includes exposed constructs such as Concept
 * and hidden constructs such as EdgeElement and Casting
 *
 * @param <E> The type of the element. Either VertexElement of EdgeElement
 * @param <P> Enum indicating the allowed properties on each type. Either Schema.VertexProperty or
 *            Schema.EdgeProperty
 */
public abstract class AbstractElement<E extends Element, P extends Enum> {
    private E element;
    private final TransactionOLTP tx;

    AbstractElement(TransactionOLTP tx, E element) {
        this.tx = tx;
        this.element = element;
    }

    public E element() {
        return element;
    }

    public Object id() {
        return element().id();
    }

    /**
     * Deletes the element from the graph
     */
    public void delete() {
        /*
        Here we force a re-fetch from janus right before deleting an element
        This is because the element contained within this object may have been evicted from the Janus transaction cache -
        doing the re-read here brings a new copy back in before deleting in, which then pins it to the cache for
        the remainder of the transaction
         */
        if (this instanceof VertexElement) {
            element = (E)tx.getTinkerTraversal().V(id()).next();
        } else {
            element = (E)tx.getTinkerTraversal().E(id()).next();
        }
        element().remove();
    }

    /**
     * @param key   The key of the property to mutate
     * @param value The value to commit into the property
     */
    public void property(P key, Object value) {
        if (value == null) {
            element().property(key.name()).remove();
        } else {
            Property<Object> foundProperty = element().property(key.name());
            if (!foundProperty.isPresent() || !foundProperty.value().equals(value)) {
                element().property(key.name(), value);
            }
        }
    }

    /**
     * @param key The key of the non-unique property to retrieve
     * @return The value stored in the property
     */
    @Nullable
    public <X> X property(P key) {
        Property<X> property = element().property(key.name());
        if (property != null && property.isPresent()) {
            return property.value();
        }
        return null;
    }

    public Boolean propertyBoolean(P key) {
        Boolean value = property(key);
        if (value == null) return false;
        return value;
    }

    /**
     * @return The grakn graph this concept is bound to.
     */
    public TransactionOLTP tx() {
        return tx;
    }

    /**
     * @return The hash code of the underlying vertex
     */
    public int hashCode() {
        return id.hashCode(); //Note: This means that concepts across different transactions will be equivalent.
    }

    /**
     * @return true if the elements equal each other
     */
    @Override
    public boolean equals(Object object) {
        //Compare Concept
        //based on id because vertex comparisons are equivalent
        return this == object || object instanceof AbstractElement && ((AbstractElement) object).id().equals(id());
    }

    /**
     * Sets the value of a property with the added restriction that no other vertex can have that property.
     *
     * @param key   The key of the unique property to mutate
     * @param value The new value of the unique property
     */
    public void propertyUnique(P key, String value) {
        GraphTraversal<Vertex, Vertex> traversal = tx().getTinkerTraversal().V().has(key.name(), value);

        if (traversal.hasNext()) {
            Vertex vertex = traversal.next();
            if (!vertex.equals(element()) || traversal.hasNext()) {
                if (traversal.hasNext()) vertex = traversal.next();
                throw PropertyNotUniqueException.cannotChangeProperty(element(), vertex, key, value);
            }
        }

        property(key, value);
    }

    /**
     * Sets a property which cannot be mutated
     *
     * @param property   The key of the immutable property to mutate
     * @param newValue   The new value to put on the property (if the property is not set)
     * @param foundValue The current value of the property
     * @param converter  Helper method to ensure data is persisted in the correct format
     */
    public <X> void propertyImmutable(P property, X newValue, @Nullable X foundValue, Function<X, Object> converter) {
        Objects.requireNonNull(property);

        if (foundValue == null) {
            property(property, converter.apply(newValue));

        } else if (!foundValue.equals(newValue)) {
            throw TransactionException.immutableProperty(foundValue, newValue, property);
        }
    }

    public <X> void propertyImmutable(P property, X newValue, X foundValue) {
        propertyImmutable(property, newValue, foundValue, Function.identity());
    }

    /**
     * @return the label of the element in the graph.
     */
    public String label() {
        return element().label();
    }

    public final boolean isDeleted() {
        return !tx().isValidElement(element());
    }
}
