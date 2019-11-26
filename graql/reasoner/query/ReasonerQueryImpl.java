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
 *
 */

package grakn.core.graql.reasoner.query;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import grakn.common.util.Pair;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.graql.reasoner.CacheCasting;
import grakn.core.kb.concept.api.Concept;
import grakn.core.kb.concept.api.ConceptId;
import grakn.core.kb.concept.api.Label;
import grakn.core.kb.concept.api.Type;
import grakn.core.core.Schema;
import grakn.core.graql.reasoner.ReasonerCheckedException;
import grakn.core.kb.concept.util.ConceptUtils;
import grakn.core.graql.reasoner.cache.MultilevelSemanticCache;
import grakn.core.kb.server.Transaction;
import grakn.core.graql.reasoner.ReasonerException;
import grakn.core.graql.reasoner.atom.Atom;
import grakn.core.kb.graql.reasoner.atom.Atomic;
import grakn.core.graql.reasoner.atom.AtomicBase;
import grakn.core.graql.reasoner.atom.AtomicFactory;
import grakn.core.graql.reasoner.atom.binary.IsaAtom;
import grakn.core.graql.reasoner.atom.binary.IsaAtomBase;
import grakn.core.graql.reasoner.atom.binary.RelationAtom;
import grakn.core.graql.reasoner.atom.predicate.IdPredicate;
import grakn.core.graql.reasoner.atom.predicate.VariablePredicate;
import grakn.core.graql.reasoner.cache.Index;
import grakn.core.graql.reasoner.explanation.JoinExplanation;
import grakn.core.graql.reasoner.explanation.LookupExplanation;
import grakn.core.graql.reasoner.plan.GraqlTraversalPlanner;
import grakn.core.graql.reasoner.plan.ResolutionPlan;
import grakn.core.graql.reasoner.plan.ResolutionQueryPlan;
import grakn.core.graql.reasoner.rule.InferenceRule;
import grakn.core.graql.reasoner.rule.RuleUtils;
import grakn.core.graql.reasoner.state.AnswerPropagatorState;
import grakn.core.graql.reasoner.state.AnswerState;
import grakn.core.graql.reasoner.state.ConjunctiveState;
import grakn.core.graql.reasoner.state.CumulativeState;
import grakn.core.graql.reasoner.state.ResolutionState;
import grakn.core.graql.reasoner.state.VariableComparisonState;
import grakn.core.kb.graql.reasoner.unifier.MultiUnifier;
import grakn.core.kb.graql.reasoner.unifier.Unifier;
import grakn.core.graql.reasoner.unifier.UnifierType;
import graql.lang.Graql;
import graql.lang.pattern.Conjunction;
import graql.lang.pattern.Pattern;
import graql.lang.statement.Statement;
import graql.lang.statement.Variable;
import grakn.core.kb.graql.reasoner.query.ReasonerQuery;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 *
 * Base reasoner query providing resolution and atom handling facilities for conjunctive graql queries.
 *
 */
public class ReasonerQueryImpl implements ResolvableQuery {

    private final Transaction tx;
    private final ImmutableSet<Atomic> atomSet;
    private ConceptMap substitution = null;
    private ImmutableSetMultimap<Variable, Type> varTypeMap = null;
    private ResolutionPlan resolutionPlan = null;
    private Conjunction<Pattern> pattern = null;
    private Set<Variable> varNames = null;

    ReasonerQueryImpl(Conjunction<Statement> pattern, Transaction tx) {
        this.tx = tx;
        this.atomSet = ImmutableSet.<Atomic>builder()
                .addAll(AtomicFactory.createAtoms(pattern, this).iterator())
                .build();
    }

    ReasonerQueryImpl(Set<Atomic> atoms, Transaction tx){
        this.tx = tx;
        this.atomSet = ImmutableSet.<Atomic>builder()
                .addAll(atoms.stream().map(at -> at.copy(this)).iterator())
                .build();
    }

    ReasonerQueryImpl(List<Atom> atoms, Transaction tx){
        this.tx = tx;
        this.atomSet =  ImmutableSet.<Atomic>builder()
                .addAll(atoms.stream()
                        .flatMap(at -> Stream.concat(Stream.of(at), at.getNonSelectableConstraints()))
                        .map(at -> at.copy(this)).iterator())
                .build();
    }

    ReasonerQueryImpl(Atom atom) {
        this(Collections.singletonList(atom), atom.getParentQuery().tx());
    }

    ReasonerQueryImpl(ReasonerQueryImpl q) {
        this.tx = q.tx;
        this.atomSet =  ImmutableSet.<Atomic>builder()
                .addAll(q.getAtoms().stream().map(at -> at.copy(this)).iterator())
                .build();
    }

    @Override
    public ReasonerQuery conjunction(ReasonerQuery q) {
        return new ReasonerQueryImpl(
                Sets.union(getAtoms(), q.getAtoms()),
                this.tx()
        );
    }

    @Override
    public CompositeQuery asComposite() {
        return new CompositeQuery(getPattern(), tx());
    }

    @Override
    public ReasonerQueryImpl withSubstitution(ConceptMap sub){
        return new ReasonerQueryImpl(Sets.union(this.getAtoms(), AtomicFactory.answerToPredicates(sub,this)), this.tx());
    }

    @Override
    public ReasonerQueryImpl inferTypes() {
        return new ReasonerQueryImpl(getAtoms().stream().map(Atomic::inferTypes).collect(Collectors.toSet()), tx());
    }

    @Override
    public ReasonerQueryImpl constantValuePredicateQuery(){
        return ReasonerQueries.create(
                getAtoms().stream()
                        .filter(at -> !(at instanceof VariablePredicate))
                        .collect(Collectors.toSet()),
                tx());
    }

    /**
     * @return true if the query doesn't contain any NeqPredicates
     */
    boolean containsVariablePredicates(){
        return getAtoms(VariablePredicate.class).findFirst().isPresent();
    }

    /**
     * @param transform map defining id transform: var -> new id
     * @return new query with id predicates transformed according to the transform
     */
    public ReasonerQueryImpl transformIds(Map<Variable, ConceptId> transform){
        Set<Atomic> atoms = this.getAtoms(IdPredicate.class).map(p -> {
            ConceptId conceptId = transform.get(p.getVarName());
            if (conceptId != null) return IdPredicate.create(p.getVarName(), conceptId, p.getParentQuery());
            return p;
        }).collect(Collectors.toSet());
        getAtoms().stream().filter(at -> !(at instanceof IdPredicate)).forEach(atoms::add);
        return new ReasonerQueryImpl(atoms, tx());
    }

    @Override
    public String toString(){
        return "{\n\t" +
                getAtoms(Atomic.class).map(Atomic::toString).collect(Collectors.joining(";\n\t")) +
                "\n}";
    }

    @Override
    public ReasonerQueryImpl copy() {
        return new ReasonerQueryImpl(this);
    }

    //alpha-equivalence equality
    @Override
    public boolean equals(Object obj) {
        if (obj == null || this.getClass() != obj.getClass()) return false;
        if (obj == this) return true;
        ReasonerQueryImpl q2 = (ReasonerQueryImpl) obj;
        return this.isEquivalent(q2);
    }

    @Override
    public int hashCode() {
        return ReasonerQueryEquivalence.AlphaEquivalence.hash(this);
    }

    @Override
    public Transaction tx() {
        return tx;
    }

    @Override
    public void checkValid() { getAtoms().forEach(Atomic::checkValid);}

    @Override
    public Conjunction<Pattern> getPattern() {
        if (pattern == null) {
            pattern = Graql.and(
                    getAtoms().stream()
                            .map(Atomic::getCombinedPattern)
                            .flatMap(p -> p.statements().stream())
                            .collect(Collectors.toSet())
            );
        }
        return pattern;
    }

    @Override
    public Set<String> validateOntologically(Label ruleLabel) {
        return getAtoms().stream()
                .flatMap(at -> at.validateAsRuleBody(ruleLabel).stream())
                .collect(Collectors.toSet());
    }

    @Override
    public boolean isRuleResolvable() {
        return selectAtoms().anyMatch(Atom::isRuleResolvable);
    }

    /**
     * @return true if this query is atomic
     */
    @Override
    public boolean isAtomic() {
        return atomSet.stream().filter(Atomic::isSelectable).count() == 1;
    }

    /**
     * @param typedVar variable of interest
     * @param parentType to be checked
     * @return true if typing the typeVar with type is compatible with role configuration of this query
     */
    @Override
    public boolean isTypeRoleCompatible(Variable typedVar, Type parentType){
        if (parentType == null || Schema.MetaSchema.isMetaLabel(parentType.label())) return true;

        Set<Type> parentTypes = parentType.subs().collect(Collectors.toSet());
        return getAtoms(RelationAtom.class)
                .filter(ra -> ra.getVarNames().contains(typedVar))
                .noneMatch(ra -> ra.getRoleVarMap().entries().stream()
                        //get roles this type needs to play
                        .filter(e -> e.getValue().equals(typedVar))
                        .filter(e -> !Schema.MetaSchema.isMetaLabel(e.getKey().label()))
                        //check if it can play it
                        .anyMatch(e -> e.getKey().players().noneMatch(parentTypes::contains)));
    }

    @Override
    public boolean isEquivalent(ResolvableQuery q) {
        return ReasonerQueryEquivalence.AlphaEquivalence.equivalent(this, q);
    }

    /**
     * @return true if this query is a ground query
     */
    public boolean isGround(){
        return getSubstitution().vars().containsAll(getVarNames());
    }

    /**
     * @return true if this query has a unique (single) answer if any
     */
    public boolean hasUniqueAnswer(){ return isGround();}

    /**
     * @return true if this query contains disconnected atoms that are unbounded
     */
    public boolean isBoundlesslyDisconnected(){
        return !isAtomic()
                && selectAtoms()
                .filter(at -> !at.isBounded())
                .anyMatch(Atom::isDisconnected);
    }

    /**
     * @return true if the query requires direct schema lookups
     */
    public boolean requiresSchema(){ return selectAtoms().anyMatch(Atom::requiresSchema);}

    @Override
    public Set<Atomic> getAtoms() { return atomSet;}

    @Override
    public Set<Variable> getVarNames() {
        if (varNames == null) {
            Set<Variable> vars = new HashSet<>();
            getAtoms().forEach(atom -> vars.addAll(atom.getVarNames()));
            varNames = vars;
        }
        return varNames;
    }

    @Override
    public MultiUnifier getMultiUnifier(ReasonerQuery parent) {
        return getMultiUnifier(parent, UnifierType.EXACT);
    }

    /**
     * @param parent query we want to unify this query with
     * @param unifierType unifier type
     * @return corresponding multiunifier
     */
    public MultiUnifier getMultiUnifier(ReasonerQuery parent, UnifierType unifierType){
        throw ReasonerException.getUnifierOfNonAtomicQuery();
    }

    private Stream<IsaAtom> inferEntityTypes(ConceptMap sub) {
        Set<Variable> typedVars = getAtoms(IsaAtomBase.class).map(AtomicBase::getVarName).collect(Collectors.toSet());
        return Stream.concat(
                getAtoms(IdPredicate.class),
                AtomicFactory.answerToPredicates(sub, this).stream().map(IdPredicate.class::cast)
        )
                .filter(p -> !typedVars.contains(p.getVarName()))
                .map(p -> new Pair<>(p, tx().<Concept>getConcept(p.getPredicate())))
                .filter(p -> Objects.nonNull(p.second()))
                .filter(p -> p.second().isEntity())
                .map(p -> IsaAtom.create(p.first().getVarName(), new Variable(), p.second().asEntity().type(), false,this));
    }

    private Multimap<Variable, Type> getVarTypeMap(Stream<IsaAtomBase> isas){
        HashMultimap<Variable, Type> map = HashMultimap.create();
        isas
                .map(at -> new Pair<>(at.getVarName(), at.getSchemaConcept()))
                .filter(p -> Objects.nonNull(p.second()))
                .filter(p -> p.second().isType())
                .forEach(p -> {
                    Variable var = p.first();
                    Type newType = p.second().asType();
                    Set<Type> types = map.get(var);

                    if (types.isEmpty()) map.put(var, newType);
                    else {
                        boolean isSubType = newType.sups().anyMatch(types::contains);
                        boolean isSuperType = newType.subs().anyMatch(types::contains);

                        //if it's a supertype of existing type, put most specific type
                        if (isSubType){
                            map.removeAll(var);
                            ConceptUtils
                                    .bottom(Sets.union(types, Sets.newHashSet(newType)))
                                    .forEach( t -> map.put(var, t));
                        }
                        if (!isSubType && !isSuperType) map.put(var, newType);
                    }
                });
        return map;
    }

    @Override
    public ImmutableSetMultimap<Variable, Type> getVarTypeMap(boolean inferTypes) {
        if (!inferTypes) return ImmutableSetMultimap.copyOf(getVarTypeMap(getAtoms(IsaAtomBase.class)));
        return getVarTypeMap();
    }

    @Override
    public ImmutableSetMultimap<Variable, Type> getVarTypeMap() {
        if (varTypeMap == null) {
            this.varTypeMap = getVarTypeMap(new ConceptMap());
        }
        return varTypeMap;
    }

    @Override
    public ImmutableSetMultimap<Variable, Type> getVarTypeMap(ConceptMap sub) {
        return ImmutableSetMultimap.copyOf(
                getVarTypeMap(
                        Stream.concat(
                                getAtoms(IsaAtomBase.class),
                                inferEntityTypes(sub)
                        )
                )
        );
    }

    @Override
    public Type getUnambiguousType(Variable var, boolean inferTypes){
        ImmutableSet<Type> types = getVarTypeMap(inferTypes).get(var);
        Type type = null;
        if(types.isEmpty()) return type;

        try {
            type = Iterables.getOnlyElement(types);
        } catch(IllegalArgumentException e){
            throw ReasonerException.ambiguousType(var, types);
        }
        return type;
    }

    /**
     * @return the resolution plan for this query
     */
    public ResolutionPlan resolutionPlan(){
        if (resolutionPlan == null){
            resolutionPlan = new ResolutionPlan(this);
        }
        return resolutionPlan;
    }

    /**
     * @param var variable name
     * @return id predicate for the specified var name if any
     */
    @Nullable
    private IdPredicate getIdPredicate(Variable var) {
        return getAtoms(IdPredicate.class)
                .filter(sub -> sub.getVarName().equals(var))
                .findFirst().orElse(null);
    }

    /**
     * returns id transform that would convert this query to a query alpha-equivalent to the query,
     * provided they are structurally equivalent
     * @param query for which the transform is to be constructed
     * @param unifier between this query and provided query
     * @return id transform
     */
    public Map<Variable, ConceptId> idTransform(ReasonerQueryImpl query, Unifier unifier){
        Map<Variable, ConceptId> transform = new HashMap<>();
        this.getAtoms(IdPredicate.class)
                .forEach(thisP -> {
                    Collection<Variable> vars = unifier.get(thisP.getVarName());
                    Variable var = !vars.isEmpty()? Iterators.getOnlyElement(vars.iterator()) : thisP.getVarName();
                    IdPredicate p2 = query.getIdPredicate(var);
                    if ( p2 != null) transform.put(thisP.getVarName(), p2.getPredicate());
                });
        return transform;
    }

    /** Does id predicates -> answer conversion
     * @return substitution obtained from all id predicates (including internal) in the query
     */
    public ConceptMap getSubstitution(){
        if (substitution == null) {
            Set<Variable> varNames = getVarNames();
            Set<IdPredicate> predicates = getAtoms(IsaAtomBase.class)
                    .map(IsaAtomBase::getTypePredicate)
                    .filter(Objects::nonNull)
                    .filter(p -> varNames.contains(p.getVarName()))
                    .collect(Collectors.toSet());
            getAtoms(IdPredicate.class).forEach(predicates::add);

            HashMap<Variable, Concept> answerMap = new HashMap<>();
            predicates.forEach(p -> {
                Concept concept = tx().getConcept(p.getPredicate());
                if (concept == null) throw ReasonerCheckedException.idNotFound(p.getPredicate());
                answerMap.put(p.getVarName(), concept);
            });
            substitution = new ConceptMap(answerMap);
        }
        return substitution;
    }

    public ConceptMap getRoleSubstitution(){
        Map<Variable, Concept> roleSub = new HashMap<>();
        getAtoms(RelationAtom.class)
                .flatMap(RelationAtom::getRolePredicates)
                .forEach(p -> {
                    Concept concept = tx().getConcept(p.getPredicate());
                    if (concept == null) throw ReasonerCheckedException.idNotFound(p.getPredicate());
                    roleSub.put(p.getVarName(), concept);
                });
        return new ConceptMap(roleSub);
    }

    /**
     * @return selected atoms
     */
    @Override
    public Stream<Atom> selectAtoms() {
        return getAtoms(Atom.class).filter(Atomic::isSelectable);
    }

    @Override
    public boolean requiresDecomposition(){
        return this.selectAtoms().anyMatch(Atom::requiresDecomposition);
    }

    /**
     * @return rewritten (decomposed) version of the query
     */
    @Override
    public ReasonerQueryImpl rewrite(){
        if (!requiresDecomposition()) return this;
        return new ReasonerQueryImpl(
                this.selectAtoms()
                        .flatMap(at -> at.rewriteToAtoms().stream())
                        .collect(Collectors.toList()),
                tx()
        );
    }

    private static final String PLACEHOLDER_ID = "000000";

    /**
     * @return true if this query has complete entries in the cache
     */
    public boolean isCacheComplete(){
        MultilevelSemanticCache queryCache = CacheCasting.queryCacheCast(tx.queryCache());
        if (selectAtoms().count() == 0) return false;
        if (isAtomic()) return queryCache.isComplete(ReasonerQueries.atomic(selectAtoms().iterator().next()));
        List<ReasonerAtomicQuery> queries = resolutionPlan().plan().stream().map(ReasonerQueries::atomic).collect(Collectors.toList());
        Set<IdPredicate> subs = new HashSet<>();
        Map<ReasonerAtomicQuery, ReasonerAtomicQuery> queryMap = new HashMap<>();
        for (ReasonerAtomicQuery query : queries) {
            Set<Variable> vars = query.getVarNames();
            Conjunction<Statement> conjunction =
            Graql.and(
                    GraqlTraversalPlanner.atomsToPattern(
                    query.getAtoms(Atom.class).collect(Collectors.toList()),
                    Sets.union(
                            query.getAtoms(IdPredicate.class).collect(Collectors.toSet()),
                            subs.stream().filter(sub -> vars.contains(sub.getVarName())).collect(Collectors.toSet())
                    )).statements()
            );
            queryMap.put(query, ReasonerQueries.atomic(conjunction, tx()));
            query.getVarNames().stream()
                    .filter(v -> subs.stream().noneMatch(s -> s.getVarName().equals(v)))
                    .map(v -> IdPredicate.create(v, ConceptId.of(PLACEHOLDER_ID), query))
                    .forEach(subs::add);
        }
        return queryMap.entrySet().stream()
                .filter(e -> e.getKey().isRuleResolvable())
                .allMatch(e ->
                        Objects.nonNull(e.getKey().getAtom().getSchemaConcept())
                                && queryCache.isComplete(e.getValue())
                );
    }

    @Override
    public boolean requiresReiteration() {
        if (isCacheComplete()) return false;
        Set<InferenceRule> dependentRules = RuleUtils.getDependentRules(this);
        return RuleUtils.subGraphIsCyclical(dependentRules, tx())
                || RuleUtils.subGraphHasRulesWithHeadSatisfyingBody(dependentRules)
                || selectAtoms().filter(Atom::isDisconnected).filter(Atom::isRuleResolvable).count() > 1;
    }

    @Override
    public ResolutionState resolutionState(ConceptMap sub, Unifier u, AnswerPropagatorState parent, Set<ReasonerAtomicQuery> subGoals){
        return !containsVariablePredicates() ?
                new ConjunctiveState(this, sub, u, parent, subGoals) :
                new VariableComparisonState(this, sub, u, parent, subGoals);
    }

    /**
     * @param sub partial substitution
     * @param u unifier with parent state
     * @param parent parent state
     * @param subGoals set of visited sub goals
     * @return resolution subGoals formed from this query obtained by expanding the inferred types contained in the query
     */
    public Stream<ResolutionState> expandedStates(ConceptMap sub, Unifier u, AnswerPropagatorState parent, Set<ReasonerAtomicQuery> subGoals){
        return getQueryStream(sub)
                .map(q -> q.resolutionState(sub, u, parent, subGoals));
    }

    /**
     * @return stream of queries obtained by inserting all inferred possible types (if ambiguous)
     */
    Stream<ReasonerQueryImpl> getQueryStream(ConceptMap sub){
        return Stream.of(this);
    }

    private List<ConceptMap> splitToPartialAnswers(ConceptMap mergedAnswer){
         return this.selectAtoms()
            .map(at -> at.inferTypes(mergedAnswer.project(at.getVarNames())))
            .map(ReasonerQueries::atomic)
                .map(aq -> mergedAnswer.project(aq.getVarNames()).explain(new LookupExplanation(), aq.withSubstitution(mergedAnswer).getPattern()))
            .collect(Collectors.toList());
    }

    @Override
    public Iterator<ResolutionState> innerStateIterator(AnswerPropagatorState parent, Set<ReasonerAtomicQuery> subGoals){
        Iterator<AnswerState> dbIterator;
        Iterator<AnswerPropagatorState> subGoalIterator;

        if(!this.isRuleResolvable()) {
            Set<Type> queryTypes = new HashSet<>(this.getVarTypeMap().values());
            boolean fruitless = tx.ruleCache().absentTypes(queryTypes);
            if (fruitless) dbIterator = Collections.emptyIterator();
            else {
                dbIterator = tx.executor().traverse(getPattern())
                        .map(ans -> ans.explain(new JoinExplanation(this.splitToPartialAnswers(ans)), this.getPattern()))
                        .map(ans -> new AnswerState(ans, parent.getUnifier(), parent))
                        .iterator();
            }
            subGoalIterator = Collections.emptyIterator();
        } else {
            dbIterator = Collections.emptyIterator();

            ResolutionQueryPlan queryPlan = new ResolutionQueryPlan(this);
            subGoalIterator = Iterators.singletonIterator(new CumulativeState(queryPlan.queries(), new ConceptMap(), parent.getUnifier(), parent, subGoals));
        }
        return Iterators.concat(dbIterator, subGoalIterator);
    }

    /**
     * @return set o variables containing a matching substitution
     */
    private Set<Variable> subbedVars(){
        return getAtoms(IdPredicate.class).map(Atomic::getVarName).collect(Collectors.toSet());
    }

    /**
     * @return answer index corresponding to corresponding partial substitution
     */
    public ConceptMap getAnswerIndex(){
        return getSubstitution().project(subbedVars());
    }

    /**
     * @return var index consisting of variables with a substitution
     */
    public Index index(){
        return Index.of(subbedVars());
    }
}
