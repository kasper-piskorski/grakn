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

package grakn.core.server.session.cache;

import com.google.common.annotations.VisibleForTesting;
import grakn.core.concept.Concept;
import grakn.core.concept.ConceptId;
import grakn.core.concept.Label;
import grakn.core.concept.LabelId;
import grakn.core.concept.thing.Attribute;
import grakn.core.concept.thing.Relation;
import grakn.core.concept.thing.Thing;
import grakn.core.concept.type.RelationType;
import grakn.core.concept.type.Role;
import grakn.core.concept.type.Rule;
import grakn.core.concept.type.SchemaConcept;
import grakn.core.concept.type.Type;
import grakn.core.graql.reasoner.utils.Pair;
import grakn.core.server.kb.Schema;
import grakn.core.server.kb.concept.AttributeImpl;
import grakn.core.server.kb.structure.Casting;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Caches TransactionOLTP specific data this includes:
 * Validation Concepts - Concepts which need to undergo validation.
 * Built Concepts -  Prevents rebuilding when the same vertex is encountered
 * The Schema - Optimises validation checks by preventing db read.
 * Label - Allows mapping type labels to type Ids
 */
public class TransactionCache {
    //Cache which is shared across multiple transactions
    private final KeyspaceCache keyspaceCache;

    //Caches any concept which has been touched before
    private final Map<ConceptId, Concept> conceptCache = new HashMap<>();
    private final Map<Label, SchemaConcept> schemaConceptCache = new HashMap<>();
    private final Map<Label, LabelId> labelCache = new HashMap<>();

    //Elements Tracked For Validation
    private final Set<Relation> newRelations = new HashSet<>();
    private final Set<Thing> modifiedThings = new HashSet<>();

    private final Set<Role> modifiedRoles = new HashSet<>();
    private final Set<Casting> modifiedCastings = new HashSet<>();

    private final Set<RelationType> modifiedRelationTypes = new HashSet<>();

    private final Set<Rule> modifiedRules = new HashSet<>();
    private final Set<Thing> inferredConcepts = new HashSet<>();
    private final Set<Thing> inferredConceptsToPersist = new HashSet<>();

    //We Track the number of concept connections which have been made which may result in a new shard
    private final Map<ConceptId, Long> shardingCount = new HashMap<>();

    //New attributes are tracked so that we can merge any duplicate attributes at commit time.
    // The label, index and id are directly cached to prevent unneeded reads
    private Map<Pair<Label, String>, ConceptId> newAttributes = new HashMap<>();
    // Track the removed attributes so that we can evict old attribute indexes from attributesCache in session
    // after commit
    private Set<String> removedAttributes = new HashSet<>();

    public TransactionCache(KeyspaceCache keyspaceCache) {
        this.keyspaceCache = keyspaceCache;
    }

    public void flushToKeyspaceCache() {
        // This method is used to actually flush to the keyspace cache
        keyspaceCache.readTxCache(this);
    }

    /**
     * Refreshes the transaction schema cache by reading the keyspace schema cache into this transaction cache.
     * This method performs this operation whilst making a deep clone of the cached concepts to ensure transactions
     * do not accidentally break the central schema cache.
     */
    public void updateSchemaCacheFromKeyspaceCache() {
        keyspaceCache.populateSchemaTxCache(this);
    }

    /**
     * @param concept The element to be later validated
     */
    public void trackForValidation(Concept concept) {
        if (concept.isThing()) {
            modifiedThings.add(concept.asThing());
        } else if (concept.isRole()) {
            modifiedRoles.add(concept.asRole());
        } else if (concept.isRelationType()) {
            modifiedRelationTypes.add(concept.asRelationType());
        } else if (concept.isRule()) {
            modifiedRules.add(concept.asRule());
        }
    }

    public void trackForValidation(Casting casting) {
        modifiedCastings.add(casting);
    }

    public void removeFromValidation(Type type) {
        if (type.isRelationType()) {
            modifiedRelationTypes.remove(type.asRelationType());
        }
    }

    /**
     * @return All the types labels currently cached in the transaction.
     */
    Map<Label, LabelId> getLabelCache() {
        return labelCache;
    }

    /**
     * @param concept The concept to no longer track
     */
    @SuppressWarnings("SuspiciousMethodCalls")
    public void remove(Concept concept) {
        modifiedThings.remove(concept);
        modifiedRoles.remove(concept);
        modifiedRelationTypes.remove(concept);
        modifiedRules.remove(concept);

        if (concept.isAttribute()) {
            AttributeImpl attr = AttributeImpl.from(concept.asAttribute());
            newAttributes.remove(new Pair<>(attr.type().label(), attr.getIndex()));
            removedAttributes.add(Schema.generateAttributeIndex(attr.type().label(), attr.value().toString()));
        }

        if (concept.isRelation()) {
            newRelations.remove(concept.asRelation());
        }

        if (concept.isThing()){
            Thing instance = concept.asThing();
            if (instance.isInferred()) removeInferredInstance(instance);
        }

        conceptCache.remove(concept.id());
        if (concept.isSchemaConcept()) {
            Label label = concept.asSchemaConcept().label();
            schemaConceptCache.remove(label);
            labelCache.remove(label);
        }
    }

    public void remove(Casting casting) {
        modifiedCastings.remove(casting);
    }

    /**
     * Caches a concept so it does not have to be rebuilt later.
     *
     * @param concept The concept to be cached.
     */
    public void cacheConcept(Concept concept) {
        conceptCache.put(concept.id(), concept);
        if (concept.isSchemaConcept()) {
            SchemaConcept schemaConcept = concept.asSchemaConcept();
            schemaConceptCache.put(schemaConcept.label(), schemaConcept);
            labelCache.put(schemaConcept.label(), schemaConcept.labelId());
        }
    }

    /**
     * Caches the mapping of a type label to a type id. This is necessary in order for ANY types to be looked up.
     *
     * @param label The type label to cache
     * @param id    Its equivalent id which can be looked up quickly in the graph
     */
    void cacheLabel(Label label, LabelId id) {
        labelCache.put(label, id);
    }

    /**
     * Checks if the concept has been built before and is currently cached
     *
     * @param id The id of the concept
     * @return true if the concept is cached
     */
    public boolean isConceptCached(ConceptId id) {
        return conceptCache.containsKey(id);
    }

    /**
     * @param label The label of the type to cache
     * @return true if the concept is cached
     */
    public boolean isTypeCached(Label label) {
        return schemaConceptCache.containsKey(label);
    }

    /**
     * @param label the type label which may be in the cache
     * @return true if the label is cached and has a valid mapping to a id
     */
    public boolean isLabelCached(Label label) {
        return labelCache.containsKey(label);
    }

    /**
     * Returns a previously built concept
     *
     * @param id  The id of the concept
     * @param <X> The type of the concept
     * @return The cached concept
     */
    public <X extends Concept> X getCachedConcept(ConceptId id) {
        //noinspection unchecked
        return (X) conceptCache.get(id);
    }

    /**
     * Caches an inferred instance for possible persistence later.
     *
     * @param thing The inferred instance to be cached.
     */
    public void inferredInstance(Thing thing){
        inferredConcepts.add(thing);
    }

    /**
     * Remove an inferred instance from tracking.
     *
     * @param thing The inferred instance to be cached.
     */
    public void removeInferredInstance(Thing thing){
        inferredConcepts.remove(thing);
        inferredConceptsToPersist.remove(thing);
    }

    public void inferredInstanceToPersist(Thing t) {
        inferredConceptsToPersist.add(t);
    }

    Stream<Thing> getInferredInstances() {
        return inferredConcepts.stream();
    }

    /**
     * @return cached things that are inferred
     */
    public Stream<Thing> getInferredInstancesToDiscard() {
        return inferredConcepts.stream()
                .filter(t -> !inferredConceptsToPersist.contains(t));
    }

    /**
     * Returns a previously built type
     *
     * @param label The label of the type
     * @param <X>   The type of the type
     * @return The cached type
     */
    public <X extends SchemaConcept> X getCachedSchemaConcept(Label label) {
        //noinspection unchecked
        return (X) schemaConceptCache.get(label);
    }

    public LabelId convertLabelToId(Label label) {
        return labelCache.get(label);
    }

    public void addedInstance(ConceptId conceptId) {
        shardingCount.compute(conceptId, (key, value) -> value == null ? 1 : value + 1);
        cleanupShardingCount(conceptId);
    }

    public void removedInstance(ConceptId conceptId) {
        shardingCount.compute(conceptId, (key, value) -> value == null ? -1 : value - 1);
        cleanupShardingCount(conceptId);
    }

    private void cleanupShardingCount(ConceptId conceptId) {
        if (shardingCount.get(conceptId) == 0) shardingCount.remove(conceptId);
    }


    public void addNewAttribute(Label label, String index, ConceptId conceptId) {
        newAttributes.put(new Pair<>(label, index), conceptId);
    }

    public Map<Pair<Label, String>, ConceptId> getNewAttributes() {
        return newAttributes;
    }

    //--------------------------------------- Concepts Needed For Validation -------------------------------------------
    public Set<Thing> getModifiedThings() {
        return modifiedThings;
    }

    public Set<Role> getModifiedRoles() {
        return modifiedRoles;
    }

    public Set<RelationType> getModifiedRelationTypes() {
        return modifiedRelationTypes;
    }

    public Set<Rule> getModifiedRules() {
        return modifiedRules;
    }

    public Set<Casting> getModifiedCastings() {
        return modifiedCastings;
    }

    public void addNewRelation(Relation relation) {
        newRelations.add(relation);
    }

    public Set<Relation> getNewRelations() {
        return newRelations;
    }

    public Set<String> getRemovedAttributes() {
        return removedAttributes;
    }

    @VisibleForTesting
    Map<ConceptId, Concept> getConceptCache() {
        return conceptCache;
    }

    @VisibleForTesting
    Map<Label, SchemaConcept> getSchemaConceptCache() {
        return schemaConceptCache;
    }

}
