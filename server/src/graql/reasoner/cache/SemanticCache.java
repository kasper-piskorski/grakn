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

package grakn.core.graql.reasoner.cache;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Sets;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.concept.type.SchemaConcept;
import grakn.core.graql.reasoner.query.ReasonerAtomicQuery;
import grakn.core.graql.reasoner.unifier.MultiUnifier;
import grakn.core.graql.reasoner.unifier.MultiUnifierImpl;
import grakn.core.graql.reasoner.unifier.UnifierType;
import grakn.core.graql.reasoner.utils.Pair;
import graql.lang.statement.Variable;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.stream.Collectors.toSet;


/**
 * Base implementation of a semantic query cache - query cache capable of recognising relations between queries
 * and subsequently reusing their answer sets.
 *
 * Relies on the following concepts:
 * - Query Subsumption {@link ReasonerAtomicQuery#subsumes(ReasonerAtomicQuery)}
 * Subsumption relation between a query C (child) and a provided query P (parent) holds if:
 *
 * C <= P,
 *
 * is true, meaning that P is more general than C (C specialises P)
 * and their respective answer sets meet:
 *
 * answers(C) subsetOf answers(P)
 *
 * i. e. the set of answers of C is a subset of the set of answers of P.
 *
 * - query semantic difference SemanticDifference
 * Semantic difference between query C and P defines a specialisation operation
 * required to transform query P into a query equivalent to C.
 *
 * Those concepts form the basis of the operation of the semantic cache:
 * if we are looking for answers to query C and the cache already contains query P which has all DB entries (db-complete),
 * we can propagate the answers, possibly specialising them (applying the semantic difference).
 *
 * @param <QE> cache entry query type
 * @param <SE> cache entry storage type
 */
public abstract class SemanticCache<
        QE,
        SE extends Set<ConceptMap>> extends AtomicQueryCacheBase<QE, SE> {

    final private HashMultimap<SchemaConcept, QE> families = HashMultimap.create();
    final private HashMultimap<QE, QE> parents = HashMultimap.create();

    UnifierType semanticUnifier(){ return UnifierType.RULE;}

    @Override
    public void clear(){
        super.clear();
        families.clear();
        parents.clear();
    }

    /**
     * Propagate ALL answers between entries provided they satisfy the corresponding semantic difference.
     *
     * @param parentEntry parent entry we want to propagate answers from
     * @param childEntry cache entry we want to propagate answers to
     * @param inferred true if inferred answers should be propagated
     * @return true if new answers were found during propagation
     */
    protected abstract boolean propagateAnswers(CacheEntry<ReasonerAtomicQuery, SE> parentEntry, CacheEntry<ReasonerAtomicQuery, SE> childEntry, boolean inferred);

    protected abstract Stream<ConceptMap> entryToAnswerStream(CacheEntry<ReasonerAtomicQuery, SE> entry);

    protected abstract Pair<Stream<ConceptMap>, MultiUnifier> entryToAnswerStreamWithUnifier(ReasonerAtomicQuery query, CacheEntry<ReasonerAtomicQuery, SE> entry);

    /**
     * @param query to be checked for answers
     * @return true if cache answers the input query
     */
    protected abstract boolean answersQuery(ReasonerAtomicQuery query);

    abstract CacheEntry<ReasonerAtomicQuery, SE> createEntry(ReasonerAtomicQuery query, Set<ConceptMap> answers);

    private CacheEntry<ReasonerAtomicQuery, SE> addEntry(CacheEntry<ReasonerAtomicQuery, SE> entry){
        CacheEntry<ReasonerAtomicQuery, SE> cacheEntry = putEntry(entry);
        ReasonerAtomicQuery query = cacheEntry.query();
        updateFamily(query);
        computeParents(query);
        propagateAnswersToQuery(query, cacheEntry, query.isGround());
        return cacheEntry;
    }

    @Override
    public boolean isComplete(ReasonerAtomicQuery query){
        return super.isComplete(query)
                || getParents(query).stream().anyMatch(q -> super.isComplete(keyToQuery(q)));
    }

    /**
     * propagate answers within the cache (children fetch answers from parents)
     */
    public void propagateAnswers(){
        queries().stream()
                .filter(this::isComplete)
                .forEach(child-> {
                    CacheEntry<ReasonerAtomicQuery, SE> childEntry = getEntry(child);
                    if (childEntry != null) {
                        propagateAnswersToQuery(child, childEntry, true);
                        ackCompleteness(child);
                    }
                });
    }

    private Set<QE> getParents(ReasonerAtomicQuery child){
        Set<QE> parents = this.parents.get(queryToKey(child));
        if (parents.isEmpty()) parents = computeParents(child);
        return parents.stream()
                .filter(parent -> child.subsumes(keyToQuery(parent)))
                .collect(toSet());
    }

    /**
     * @param query to find
     * @return queries that belong to the same family as input query
     */
    private Set<QE> getFamily(ReasonerAtomicQuery query){
        SchemaConcept schemaConcept = query.getAtom().getSchemaConcept();
        if (schemaConcept == null) return new HashSet<>();
        Set<QE> family = families.get(schemaConcept);
        return family != null?
                family.stream().filter(q -> !q.equals(queryToKey(query))).collect(toSet()) :
                new HashSet<>();
    }

    private void updateFamily(ReasonerAtomicQuery query){
        SchemaConcept schemaConcept = query.getAtom().getSchemaConcept();
        if (schemaConcept != null){
            Set<QE> familyEntry = families.get(schemaConcept);
            QE familyQuery = queryToKey(query);
            if (familyEntry != null){
                familyEntry.add(familyQuery);
            } else {
                families.put(schemaConcept, familyQuery);
            }
        }
    }

    private Set<QE> computeParents(ReasonerAtomicQuery child){
        Set<QE> family = getFamily(child);
        Set<QE> computedParents = new HashSet<>();
        family.stream()
                .map(this::keyToQuery)
                .filter(child::subsumes)
                .map(this::queryToKey)
                .peek(computedParents::add)
                .forEach(parent -> parents.put(queryToKey(child), parent));
        return computedParents;
    }

    /**
     * NB: uses getEntry
     * NB: target and childMatch.query() are in general not the same hence explicit arguments
     * @param target query we want propagate the answers to
     * @param childMatch entry to which we want to propagate answers
     * @param fetchInferred true if inferred answers should be propagated
     */
    private boolean propagateAnswersToQuery(ReasonerAtomicQuery target, CacheEntry<ReasonerAtomicQuery, SE> childMatch, boolean fetchInferred){
        ReasonerAtomicQuery child = childMatch.query();
        boolean[] newAnswersFound = {false};
        boolean childGround = child.isGround();
        getParents(target)
                .forEach(parent -> {
                    boolean parentDbComplete = isDBComplete(keyToQuery(parent));
                    if (parentDbComplete || childGround){
                        boolean parentComplete = isComplete(keyToQuery(parent));
                        CacheEntry<ReasonerAtomicQuery, SE> parentMatch = getEntry(keyToQuery(parent));

                        boolean propagateInferred = fetchInferred || parentComplete || child.getAtom().getVarName().isReturned();
                        boolean newAnswers = propagateAnswers(parentMatch, childMatch, propagateInferred);
                        newAnswersFound[0] = newAnswers;
                        if (parentDbComplete || newAnswers) ackDBCompleteness(target);
                        if (parentComplete) ackCompleteness(target);
                    }
                });
        return newAnswersFound[0];
    }

    @Override
    public CacheEntry<ReasonerAtomicQuery, SE> record(
            ReasonerAtomicQuery query,
            ConceptMap answer,
            @Nullable CacheEntry<ReasonerAtomicQuery, SE> entry,
            @Nullable MultiUnifier unifier) {

        assert(query.isPositive());
        validateAnswer(answer, query, query.getVarNames());

        /*
         * find SE entry
         * - if entry exists - easy
         * - if not, add entry and establish whether any parents present
         */
        CacheEntry<ReasonerAtomicQuery, SE> match = entry != null? entry : this.getEntry(query);
        if (match != null){
            ReasonerAtomicQuery equivalentQuery = match.query();
            SE answerSet = match.cachedElement();
            MultiUnifier multiUnifier = unifier == null? query.getMultiUnifier(equivalentQuery, unifierType()) : unifier;
            Set<Variable> cacheVars = equivalentQuery.getVarNames();
            //NB: this indexes answer according to all indices in the set
            multiUnifier
                    .apply(answer)
                    .peek(ans -> validateAnswer(ans, equivalentQuery, cacheVars))
                    .forEach(answerSet::add);
            return match;
        }
        return addEntry(createEntry(query, Sets.newHashSet(answer)));
    }

    @Override
    public CacheEntry<ReasonerAtomicQuery, SE> record(ReasonerAtomicQuery query, Set<ConceptMap> answers) {
        ConceptMap first = answers.stream().findFirst().orElse(null);
        if (first == null) return null;
        CacheEntry<ReasonerAtomicQuery, SE> record = record(query, first);
        Sets.difference(answers, Sets.newHashSet(first)).forEach(ans -> record(query, ans, record, null));
        return record;
    }

    @Override
    public CacheEntry<ReasonerAtomicQuery, SE> record(ReasonerAtomicQuery query, Stream<ConceptMap> answers) {
        return record(query, answers.collect(toSet()));
    }

    private Pair<Stream<ConceptMap>, MultiUnifier> getDBAnswerStreamWithUnifier(ReasonerAtomicQuery query){
        return new Pair<>(
                structuralCache().get(query),
                MultiUnifierImpl.trivial()
        );
    }

    private static final Logger LOG = LoggerFactory.getLogger(SemanticCache.class);

    @Override
    public Pair<Stream<ConceptMap>, MultiUnifier> getAnswerStreamWithUnifier(ReasonerAtomicQuery query) {
        assert(query.isPositive());
        CacheEntry<ReasonerAtomicQuery, SE> match = getEntry(query);
        boolean queryGround = query.isGround();

        if (match != null) {
            boolean answersToGroundQuery = false;
            boolean queryDBComplete = isDBComplete(query);
            if (queryGround) {
                boolean newAnswersPropagated = propagateAnswersToQuery(query, match, true);
                if (newAnswersPropagated) answersToGroundQuery = answersQuery(query);
            }

            //extra check is a quasi-completeness check if there's no parent present we have no guarantees about completeness with respect to the db.
            Pair<Stream<ConceptMap>, MultiUnifier> cachePair = entryToAnswerStreamWithUnifier(query, match);

            //if db complete or we found answers to ground query via propagation we don't need to hit the database
            if (queryDBComplete || answersToGroundQuery) return cachePair;

            //otherwise lookup and add inferred answers on top
            return new Pair<>(
                            Stream.concat(
                                    getDBAnswerStreamWithUnifier(query).getKey(),
                                    cachePair.getKey().filter(ans -> ans.explanation().isRuleExplanation())
                            ),
                            cachePair.getValue());
        }

        //if no match but db-complete parent exists, use parent to create entry
        Set<QE> parents = getParents(query);
        boolean fetchFromParent = parents.stream().anyMatch(p ->
                queryGround || isDBComplete(keyToQuery(p))
        );
        if (fetchFromParent){
            LOG.trace("Query Cache miss: {} with fetch from parents {}", query, parents);
            CacheEntry<ReasonerAtomicQuery, SE> newEntry = addEntry(createEntry(query, new HashSet<>()));
            return new Pair<>(entryToAnswerStream(newEntry), MultiUnifierImpl.trivial());
        }
        return getDBAnswerStreamWithUnifier(query);
    }

    @Override
    public Stream<ConceptMap> getAnswerStream(ReasonerAtomicQuery query) {
        return getAnswerStreamWithUnifier(query).getKey();
    }

    @Override
    public Set<ConceptMap> getAnswers(ReasonerAtomicQuery query) {
        return getAnswerStream(query).collect(toSet());
    }

    @Override
    public Pair<Set<ConceptMap>, MultiUnifier> getAnswersWithUnifier(ReasonerAtomicQuery query) {
        Pair<Stream<ConceptMap>, MultiUnifier> answerStreamWithUnifier = getAnswerStreamWithUnifier(query);
        return new Pair<>(
                answerStreamWithUnifier.getKey().collect(toSet()),
                answerStreamWithUnifier.getValue()
        );
    }
}
