/*
 * GRAKN.AI - THE KNOWLEDGE GRAPH
 * Copyright (C) 2018 Grakn Labs Ltd
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

package grakn.core.server.kb.concept;

import grakn.core.concept.Label;
import grakn.core.concept.LabelId;
import grakn.core.concept.type.Rule;
import grakn.core.concept.type.SchemaConcept;
import grakn.core.server.exception.PropertyNotUniqueException;
import grakn.core.server.exception.TransactionException;
import grakn.core.server.kb.Schema;
import grakn.core.server.kb.Cache;
import grakn.core.server.kb.structure.VertexElement;
import org.apache.tinkerpop.gremlin.structure.Direction;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Schema Specific Concept
 * Allows you to create schema or ontological elements.
 * These differ from normal graph constructs in two ways:
 * 1. They have a unique Label which identifies them
 * 2. You can link them together into a hierarchical structure
 *
 * @param <T> The leaf interface of the object concept.
 *            For example an EntityType or RelationType or Role
 */
public abstract class SchemaConceptImpl<T extends SchemaConcept> extends ConceptImpl implements SchemaConcept {
    private final Cache<Label> cachedLabel = new Cache<>(() -> Label.of(vertex().property(Schema.VertexProperty.SCHEMA_LABEL)));
    private final Cache<LabelId> cachedLabelId = new Cache<>(() -> LabelId.of(vertex().property(Schema.VertexProperty.LABEL_ID)));
    private final Cache<T> cachedSuperType = new Cache<>(() -> this.<T>neighbours(Direction.OUT, Schema.EdgeLabel.SUB).findFirst().orElse(null));
    private final Cache<Set<T>> cachedDirectSubTypes = new Cache<>(() -> this.<T>neighbours(Direction.IN, Schema.EdgeLabel.SUB).collect(Collectors.toSet()));
    private final Cache<Boolean> cachedIsImplicit = new Cache<>(() -> vertex().propertyBoolean(Schema.VertexProperty.IS_IMPLICIT));

    SchemaConceptImpl(VertexElement vertexElement) {
        super(vertexElement);
    }

    SchemaConceptImpl(VertexElement vertexElement, T superType) {
        this(vertexElement);
        if (sup() == null) sup(superType);
    }

    public static <X extends SchemaConcept> SchemaConceptImpl<X> from(SchemaConcept schemaConcept) {
        //noinspection unchecked
        return (SchemaConceptImpl<X>) schemaConcept;
    }

    public T label(Label label) {
        try {
            vertex().tx().cache().remove(this);
            vertex().propertyUnique(Schema.VertexProperty.SCHEMA_LABEL, label.getValue());
            cachedLabel.set(label);
            vertex().tx().cache().cacheConcept(this);
            return getThis();
        } catch (PropertyNotUniqueException exception) {
            vertex().tx().cache().cacheConcept(this);
            throw TransactionException.labelTaken(label);
        }
    }

    /**
     * @return The internal id which is used for fast lookups
     */
    @Override
    public LabelId labelId() {
        return cachedLabelId.get();
    }

    /**
     * @return The label of this ontological element
     */
    @Override
    public Label label() {
        return cachedLabel.get();
    }

    /**
     * @return The super of this SchemaConcept
     */
    public T sup() {
        return cachedSuperType.get();
    }

    @Override
    public Stream<T> sups() {
        Set<T> superSet = new HashSet<>();

        T superParent = getThis();

        while (superParent != null && !Schema.MetaSchema.THING.getLabel().equals(superParent.label())) {
            superSet.add(superParent);

            //noinspection unchecked
            superParent = (T) superParent.sup();
        }

        return superSet.stream();
    }

    /**
     * @return returns true if the type was created implicitly through the resource syntax
     */
    @Override
    public Boolean isImplicit() {
        return cachedIsImplicit.get();
    }

    /**
     * Deletes the concept as a SchemaConcept
     */
    @Override
    public void delete() {
        if (deletionAllowed()) {
            //Force load of linked concepts whose caches need to be updated
            T superConcept = cachedSuperType.get();

            deleteNode();

            //Update neighbouring caches
            SchemaConceptImpl.from(superConcept).deleteCachedDirectedSubType(getThis());

            //clear rule cache
            vertex().tx().ruleCache().clear();
        } else {
            throw TransactionException.cannotBeDeleted(this);
        }
    }

    boolean deletionAllowed() {
        checkSchemaMutationAllowed();
        return !neighbours(Direction.IN, Schema.EdgeLabel.SUB).findAny().isPresent();
    }

    /**
     * @return All the subs of this concept including itself
     */
    @Override
    public Stream<T> subs() {
        return nextSubLevel(getThis());
    }

    /**
     * Adds a new sub type to the currently cached sub types. If no subtypes have been cached then this will hit the database.
     *
     * @param newSubType The new subtype
     */
    private void addCachedDirectSubType(T newSubType) {
        cachedDirectSubTypes.ifCached(set -> set.add(newSubType));
    }

    /**
     * @param root The current SchemaConcept
     * @return All the sub children of the root. Effectively calls  the cache SchemaConceptImpl#cachedDirectSubTypes recursively
     */
    private Stream<T> nextSubLevel(T root) {
        return Stream.concat(Stream.of(root),
                SchemaConceptImpl.<T>from(root).cachedDirectSubTypes.get().stream().flatMap(this::nextSubLevel));
    }

    /**
     * Checks if we are mutating a SchemaConcept in a valid way. SchemaConcept mutations are valid if:
     * 1. The SchemaConcept is not a meta-type
     * 2. The graph is not batch loading
     */
    void checkSchemaMutationAllowed() {
        if (Schema.MetaSchema.isMetaLabel(label())) {
            throw TransactionException.metaTypeImmutable(label());
        }
    }

    /**
     * Removes an old sub type from the currently cached sub types. If no subtypes have been cached then this will hit the database.
     *
     * @param oldSubType The old sub type which should not be cached anymore
     */
    private void deleteCachedDirectedSubType(T oldSubType) {
        cachedDirectSubTypes.ifCached(set -> set.remove(oldSubType));
    }

    /**
     * @param newSuperType This type's super type
     * @return The Type itself
     */
    public T sup(T newSuperType) {
        T oldSuperType = sup();
        if (changingSuperAllowed(oldSuperType, newSuperType)) {
            //Update the super type of this type in cache
            cachedSuperType.set(newSuperType);

            //Note the check before the actual construction
            if (superLoops()) {
                cachedSuperType.set(oldSuperType); //Reset if the new super type causes a loop
                throw TransactionException.loopCreated(this, newSuperType);
            }

            //Modify the graph once we have checked no loop occurs
            deleteEdge(Direction.OUT, Schema.EdgeLabel.SUB);
            putEdge(ConceptVertex.from(newSuperType), Schema.EdgeLabel.SUB);

            //Update the sub types of the old super type
            if (oldSuperType != null) {
                //noinspection unchecked - Casting is needed to access {deleteCachedDirectedSubTypes} method
                ((SchemaConceptImpl<T>) oldSuperType).deleteCachedDirectedSubType(getThis());
            }

            //Add this as the subtype to the supertype
            //noinspection unchecked - Casting is needed to access {addCachedDirectSubTypes} method
            ((SchemaConceptImpl<T>) newSuperType).addCachedDirectSubType(getThis());

            //Track any existing data if there is some
            if (oldSuperType != null) trackRolePlayers();
        }
        return getThis();
    }

    /**
     * Checks if changing the super is allowed. This passed if:
     * The <code>newSuperType</code> is different from the old.
     *
     * @param oldSuperType the old super
     * @param newSuperType the new super
     * @return true if we can set the new super
     */
    boolean changingSuperAllowed(T oldSuperType, T newSuperType) {
        checkSchemaMutationAllowed();
        return oldSuperType == null || !oldSuperType.equals(newSuperType);
    }

    /**
     * Method which performs tasks needed in order to track super changes properly
     */
    abstract void trackRolePlayers();

    private boolean superLoops() {
        //Check For Loop
        HashSet<SchemaConcept> foundTypes = new HashSet<>();
        SchemaConcept currentSuperType = sup();
        while (currentSuperType != null) {
            foundTypes.add(currentSuperType);
            currentSuperType = currentSuperType.sup();
            if (foundTypes.contains(currentSuperType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return A collection of Rule for which this SchemaConcept serves as a hypothesis
     */
    @Override
    public Stream<Rule> whenRules() {
        return Stream.concat(
                neighbours(Direction.IN, Schema.EdgeLabel.POSITIVE_HYPOTHESIS),
                neighbours(Direction.IN, Schema.EdgeLabel.NEGATIVE_HYPOTHESIS)
        );
    }

    /**
     * @return A collection of Rule for which this SchemaConcept serves as a conclusion
     */
    @Override
    public Stream<Rule> thenRules() {
        return neighbours(Direction.IN, Schema.EdgeLabel.CONCLUSION);
    }

    @Override
    public String innerToString() {
        String message = super.innerToString();
        message = message + " - Label [" + label() + "] ";
        return message;
    }
}
