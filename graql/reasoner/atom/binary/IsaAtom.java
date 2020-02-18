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

package grakn.core.graql.reasoner.atom.binary;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.concept.util.ConceptUtils;
import grakn.core.graql.reasoner.atom.Atom;
import grakn.core.graql.reasoner.atom.predicate.Predicate;
import grakn.core.graql.reasoner.unifier.UnifierImpl;
import grakn.core.graql.reasoner.unifier.UnifierType;
import grakn.core.graql.reasoner.utils.AnswerUtil;
import grakn.core.kb.concept.api.Concept;
import grakn.core.kb.concept.api.ConceptId;
import grakn.core.kb.concept.api.EntityType;
import grakn.core.kb.concept.api.SchemaConcept;
import grakn.core.kb.concept.api.Type;
import grakn.core.kb.concept.manager.ConceptManager;
import grakn.core.kb.graql.exception.GraqlSemanticException;
import grakn.core.kb.graql.reasoner.atom.Atomic;
import grakn.core.kb.graql.reasoner.cache.RuleCache;
import grakn.core.kb.graql.reasoner.query.ReasonerQuery;
import grakn.core.kb.graql.reasoner.unifier.Unifier;
import graql.lang.pattern.Pattern;
import graql.lang.property.IsaProperty;
import graql.lang.property.VarProperty;
import graql.lang.statement.Statement;
import graql.lang.statement.Variable;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * TypeAtom corresponding to graql a IsaProperty property.
 */
public class IsaAtom extends IsaAtomBase {

    private int hashCode;
    private boolean hashCodeMemoised;

    IsaAtom(ConceptManager conceptManager, RuleCache ruleCache, Variable varName, Statement pattern, ReasonerQuery reasonerQuery, ConceptId typeId,
            Variable predicateVariable) {
        super(conceptManager, ruleCache, varName, pattern, reasonerQuery, typeId, predicateVariable);
    }

    public static IsaAtom create(ConceptManager conceptManager, RuleCache ruleCache, Variable var, Variable predicateVar, Statement pattern, @Nullable ConceptId predicateId, ReasonerQuery parent) {
        return new IsaAtom(conceptManager, ruleCache, var.asReturnedVar(), pattern, parent, predicateId, predicateVar);
    }

    public static IsaAtom create(ConceptManager conceptManager, RuleCache ruleCache, Variable var, Variable predicateVar, @Nullable ConceptId predicateId, boolean isDirect, ReasonerQuery parent) {
        Statement pattern = isDirect ?
                new Statement(var).isaX(new Statement(predicateVar)) :
                new Statement(var).isa(new Statement(predicateVar));

        return new IsaAtom(conceptManager, ruleCache, var, pattern, parent, predicateId, predicateVar);
    }

    public static IsaAtom create(ConceptManager conceptManager, RuleCache ruleCache, Variable var, Variable predicateVar, SchemaConcept type, boolean isDirect, ReasonerQuery parent) {
        Statement pattern = isDirect ?
                new Statement(var).isaX(new Statement(predicateVar)) :
                new Statement(var).isa(new Statement(predicateVar));
        return new IsaAtom(conceptManager, ruleCache, var, pattern, parent, type.id(), predicateVar);
    }

    private static IsaAtom create(ConceptManager conceptManager, RuleCache ruleCache,IsaAtom a, ReasonerQuery parent) {
        return create(conceptManager, ruleCache, a.getVarName(), a.getPredicateVariable(), a.getPattern(), a.getTypeId(), parent);
    }

    @Override
    public Atomic copy(ReasonerQuery parent){
        return create(conceptManager, ruleCache, this, parent);
    }

    @Override
    public IsaAtom toIsaAtom(){ return this; }

    @Override
    public Class<? extends VarProperty> getVarPropertyClass() {
        return IsaProperty.class;
    }

    @Override
    public void checkValid(){
        super.checkValid();
        SchemaConcept type = getSchemaConcept();
        if (type != null && !type.isType()) {
            throw GraqlSemanticException.cannotGetInstancesOfNonType(type.label());
        }
    }

    //NB: overriding as these require a derived property
    @Override
    public final boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || this.getClass() != obj.getClass()) return false;
        IsaAtom that = (IsaAtom) obj;
        return this.getVarName().equals(that.getVarName())
                && this.isDirect() == that.isDirect()
                && ((this.getTypeId() == null) ? (that.getTypeId() == null) : this.getTypeId().equals(that.getTypeId()));
    }

    @Override
    public int hashCode() {
        if (!hashCodeMemoised) {
            hashCode = Objects.hash(getVarName(), getTypeId());
            hashCodeMemoised = true;
        }
        return hashCode;
    }

    @Override
    public String toString(){
        String typeString = (getSchemaConcept() != null? getSchemaConcept().label() : "") + "(" + getVarName() + ")";
        return typeString +
                (getPredicateVariable().isReturned()? "(" + getPredicateVariable() + ")" : "") +
                (isDirect()? "!" : "") +
                getPredicates().map(Predicate::toString).collect(Collectors.joining(""));
    }

    @Override
    protected Pattern createCombinedPattern(){
        if (getPredicateVariable().isReturned()) return super.createCombinedPattern();
        return getSchemaConcept() == null?
                new Statement(getVarName()).isa(new Statement(getPredicateVariable())) :
                isDirect()?
                        new Statement(getVarName()).isaX(getSchemaConcept().label().getValue()) :
                        new Statement(getVarName()).isa(getSchemaConcept().label().getValue()) ;
    }

    @Override
    public IsaAtom addType(SchemaConcept type) {
        if (getTypeId() != null) return this;
        return create(conceptManager, ruleCache, getVarName(), getPredicateVariable(), type.id(), this.isDirect(), this.getParentQuery());
    }

    private IsaAtom inferEntityType(ConceptMap sub){
        if (getTypePredicate() != null) return this;
        if (sub.containsVar(getPredicateVariable())) return addType(sub.get(getPredicateVariable()).asType());
        return this;
    }

    private ImmutableList<Type> inferPossibleTypes(ConceptMap sub){
        if (getSchemaConcept() != null) return ImmutableList.of(getSchemaConcept().asType());
        if (sub.containsVar(getPredicateVariable())) return ImmutableList.of(sub.get(getPredicateVariable()).asType());

        //determine compatible types from played roles
        Set<Type> typesFromRoles = getParentQuery().getAtoms(RelationAtom.class)
                .filter(r -> r.getVarNames().contains(getVarName()))
                .flatMap(r -> r.getRoleVarMap().entries().stream()
                        .filter(e -> e.getValue().equals(getVarName()))
                        .map(Map.Entry::getKey))
                .map(role -> role.players().collect(Collectors.toSet()))
                .reduce(Sets::intersection)
                .orElse(Sets.newHashSet());

        Set<Type> typesFromTypes = getParentQuery().getAtoms(IsaAtom.class)
                .filter(at -> at.getVarNames().contains(getVarName()))
                .filter(at -> at != this)
                .map(Binary::getSchemaConcept)
                .filter(Objects::nonNull)
                .filter(Concept::isType)
                .map(Concept::asType)
                .collect(Collectors.toSet());

        //TODO determine types from attribute atoms

        Set<Type> types = typesFromTypes.isEmpty()?
                typesFromRoles :
                typesFromRoles.isEmpty()? typesFromTypes: Sets.intersection(typesFromRoles, typesFromTypes);

        return !types.isEmpty()?
                ImmutableList.copyOf(ConceptUtils.top(types)) :
                conceptManager.getMetaConcept().subs().collect(ImmutableList.toImmutableList());
    }

    @Override
    public IsaAtom inferTypes(ConceptMap sub) {
        return this
                .inferEntityType(sub);
    }

    @Override
    public ImmutableList<Type> getPossibleTypes() { return inferPossibleTypes(new ConceptMap()); }

    @Override
    public List<Atom> atomOptions(ConceptMap sub) {
        return this.inferPossibleTypes(sub).stream()
                .map(this::addType)
                .sorted(Comparator.comparing(Atom::isRuleResolvable))
                .collect(Collectors.toList());
    }

    @Override
    public Stream<ConceptMap> materialise(){
        ConceptMap substitution = getParentQuery().getSubstitution();
        EntityType entityType = getSchemaConcept().asEntityType();

        Concept foundConcept = substitution.containsVar(getVarName())? substitution.get(getVarName()) : null;
        if (foundConcept != null) return Stream.of(substitution);

        Concept concept = entityType.addEntityInferred();
        return Stream.of(
                AnswerUtil.joinAnswers(substitution, new ConceptMap(ImmutableMap.of(getVarName(), concept))
        ));
    }

    @Override
    public Atom rewriteWithTypeVariable() {
        return create(conceptManager, ruleCache, getVarName(), getPredicateVariable().asReturnedVar(), getTypeId(), this.isDirect(), getParentQuery());
    }

    @Override
    public Atom rewriteToUserDefined(Atom parentAtom) {
        return this.rewriteWithTypeVariable(parentAtom);
    }

    @Override
    public Unifier getUnifier(Atom parentAtom, UnifierType unifierType) {
        //in general this <= parent, so no specialisation viable
        if (this.getClass() != parentAtom.getClass()) return UnifierImpl.nonExistent();
        return super.getUnifier(parentAtom, unifierType);
    }
}
