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
package grakn.core.graql.reasoner.atom;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import grakn.core.common.exception.ErrorMessage;
import grakn.core.concept.ConceptId;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.concept.type.Rule;
import grakn.core.concept.type.SchemaConcept;
import grakn.core.concept.type.Type;
import grakn.core.graql.exception.GraqlQueryException;
import grakn.core.graql.reasoner.atom.binary.AttributeAtom;
import grakn.core.graql.reasoner.atom.binary.IsaAtom;
import grakn.core.graql.reasoner.atom.binary.OntologicalAtom;
import grakn.core.graql.reasoner.atom.binary.RelationAtom;
import grakn.core.graql.reasoner.atom.binary.TypeAtom;
import grakn.core.graql.reasoner.atom.predicate.IdPredicate;
import grakn.core.graql.reasoner.atom.predicate.Predicate;
import grakn.core.graql.reasoner.atom.predicate.ValuePredicate;
import grakn.core.graql.reasoner.atom.predicate.VariablePredicate;
import grakn.core.graql.reasoner.cache.SemanticDifference;
import grakn.core.graql.reasoner.cache.VariableDefinition;
import grakn.core.graql.reasoner.rule.InferenceRule;
import grakn.core.graql.reasoner.unifier.MultiUnifier;
import grakn.core.graql.reasoner.unifier.MultiUnifierImpl;
import grakn.core.graql.reasoner.unifier.Unifier;
import grakn.core.graql.reasoner.unifier.UnifierType;
import graql.lang.property.IsaProperty;
import graql.lang.property.VarProperty;
import graql.lang.statement.Variable;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

import static java.util.stream.Collectors.toSet;

/**
 * AtomicBase extension defining specialised functionalities.
 */
public abstract class Atom extends AtomicBase {

    private Set<InferenceRule> applicableRules = null;

    public RelationAtom toRelationAtom() {
        throw GraqlQueryException.illegalAtomConversion(this, RelationAtom.class);
    }

    public AttributeAtom toAttributeAtom() { throw GraqlQueryException.illegalAtomConversion(this, AttributeAtom.class); }

    public IsaAtom toIsaAtom() {
        throw GraqlQueryException.illegalAtomConversion(this, IsaAtom.class);
    }

    /**
     * Determines whether the subsumption relation between this (A) and provided atom (B) holds,
     * i. e. determines if:
     *
     * A >= B,
     *
     * is true meaning that B is more general than A and the respective answer sets meet:
     *
     * answers(B) subsetOf answers(A)
     *
     * i. e. the set of answers of A is a subset of the set of answers of B
     *
     * @param atomic to compare with
     * @return true if this atom subsumes the provided atom
     */
    @Override
    public boolean subsumes(Atomic atomic) {
        if (!atomic.isAtom()) return false;
        Atom parent = (Atom) atomic;
        MultiUnifier multiUnifier = this.getMultiUnifier(parent, UnifierType.SUBSUMPTIVE);
        if (multiUnifier.isEmpty()) return false;

        MultiUnifier inverse = multiUnifier.inverse();
        return//check whether propagated answers would be complete
                !inverse.isEmpty() &&
                        inverse.stream().allMatch(u -> u.values().containsAll(this.getVarNames()))
                        && !parent.getPredicates(VariablePredicate.class).findFirst().isPresent()
                        && !this.getPredicates(VariablePredicate.class).findFirst().isPresent();
    }

    /**
     * @param atom to unify with
     * @return true if this unifies with atom via rule unifier
     */
    public boolean isUnifiableWith(Atom atom) {
        return !this.getMultiUnifier(atom, UnifierType.RULE).equals(MultiUnifierImpl.nonExistent());
    }

    @Override
    public boolean isAtom() { return true;}

    public boolean isRuleResolvable() {
        return getApplicableRules().findFirst().isPresent();
    }

    /**
     * @return true if the atom is ground (all variables are bound)
     */
    public boolean isGround() {
        Set<Variable> mappedVars = Stream.concat(getPredicates(), getInnerPredicates())
                .map(AtomicBase::getVarName)
                .collect(toSet());
        return mappedVars.containsAll(getVarNames());
    }

    /**
     * @return true if the query corresponding to this atom has a unique (single) answer if any
     */
    public boolean hasUniqueAnswer(){ return isGround();}

    /**
     * @return true if this atom is bounded - via substitution/specific resource or schema
     */
    public boolean isBounded() {
        return isResource() && ((AttributeAtom) this).isValueEquality()
                || this instanceof OntologicalAtom
                || isGround();
    }

    /**
     * @return true if this atom is disconnected (doesn't have neighbours)
     */
    public boolean isDisconnected() {
        return isSelectable()
                && getParentQuery().getAtoms(Atom.class)
                .filter(Atomic::isSelectable)
                .filter(at -> !at.equals(this))
                .allMatch(at -> Sets.intersection(at.getVarNames(), this.getVarNames()).isEmpty());
    }

    /**
     * @return true if this atom requires direct schema lookups
     */
    public boolean requiresSchema() {
        return getSchemaConcept() == null || this instanceof OntologicalAtom;
    }

    public abstract Class<? extends VarProperty> getVarPropertyClass();

    @Override
    public Set<String> validateAsRuleHead(Rule rule) {
        Set<String> errors = new HashSet<>();
        Set<Atomic> parentAtoms = getParentQuery().getAtoms(Atomic.class).filter(at -> !at.equals(this)).collect(toSet());
        Set<Variable> varNames = Sets.difference(
                getVarNames(),
                this.getInnerPredicates().map(Atomic::getVarName).collect(toSet())
        );
        boolean unboundVariables = varNames.stream()
                .anyMatch(var -> parentAtoms.stream().noneMatch(at -> at.getVarNames().contains(var)));
        if (unboundVariables) {
            errors.add(ErrorMessage.VALIDATION_RULE_ILLEGAL_HEAD_ATOM_WITH_UNBOUND_VARIABLE.getMessage(rule.then(), rule.label()));
        }

        SchemaConcept schemaConcept = getSchemaConcept();
        if (schemaConcept == null) {
            errors.add(ErrorMessage.VALIDATION_RULE_ILLEGAL_HEAD_ATOM_WITH_AMBIGUOUS_SCHEMA_CONCEPT.getMessage(rule.then(), rule.label()));
        } else if (schemaConcept.isImplicit()) {
            errors.add(ErrorMessage.VALIDATION_RULE_ILLEGAL_HEAD_ATOM_WITH_IMPLICIT_SCHEMA_CONCEPT.getMessage(rule.then(), rule.label()));
        }
        return errors;
    }

    /**
     * @return var properties this atom (its pattern) contains
     */
    public Stream<VarProperty> getVarProperties() {
        return getCombinedPattern().statements().stream().flatMap(statement -> statement.getProperties(getVarPropertyClass()));
    }

    @Override
    public boolean isDirect() {
        return getPattern().properties().stream().anyMatch(VarProperty::isExplicit);
    }

    /**
     * @return set of variables that need to be have their roles expanded
     */
    public Set<Variable> getRoleExpansionVariables() { return new HashSet<>();}

    private boolean isRuleApplicable(InferenceRule child) {
        return (getIdPredicate(getVarName()) == null
                || child.redefinesType()
                || child.appendsRolePlayers())
                && isRuleApplicableViaAtom(child.getRuleConclusionAtom());
    }

    protected abstract boolean isRuleApplicableViaAtom(Atom headAtom);

    /**
     * @return set of potentially applicable rules - does shallow (fast) check for applicability
     */
    public Stream<Rule> getPotentialRules() {
        boolean isDirect = getPattern().getProperties(IsaProperty.class).findFirst()
                .map(IsaProperty::isExplicit).orElse(false);

        return getPossibleTypes().stream()
                .flatMap(type -> tx().ruleCache().getRulesWithType(type, isDirect))
                .distinct();
    }

    /**
     * @return set of applicable rules - does detailed (slow) check for applicability
     */
    public Stream<InferenceRule> getApplicableRules() {
        if (applicableRules == null) {
            applicableRules = new HashSet<>();
            getPotentialRules()
                    .map(rule -> tx().ruleCache().getRule(rule))
                    .filter(this::isRuleApplicable)
                    .map(r -> r.rewrite(this))
                    .forEach(applicableRules::add);
        }
        return applicableRules.stream();
    }

    /**
     * @return true if the atom requires materialisation in order to be referenced
     */
    public boolean requiresMaterialisation() { return false; }

    /**
     * @return true if the atom requires role expansion
     */
    public boolean requiresRoleExpansion() { return false; }

    /**
     * @return if this atom requires decomposition into a set of atoms
     */
    public boolean requiresDecomposition() {
        return this.getPotentialRules()
                .map(r -> tx().ruleCache().getRule(r))
                .anyMatch(InferenceRule::appendsRolePlayers);
    }

    /**
     * @return corresponding type if any
     */
    public abstract SchemaConcept getSchemaConcept();

    /**
     * @return type id of the corresponding type if any
     */
    public abstract ConceptId getTypeId();

    /**
     * @return value variable name
     */
    public abstract Variable getPredicateVariable();

    public abstract Stream<Predicate> getInnerPredicates();

    /**
     * @param type the class of Predicate to return
     * @param <T>  the type of Predicate to return
     * @return stream of predicates relevant to this atom
     */
    public <T extends Predicate> Stream<T> getInnerPredicates(Class<T> type) {
        return getInnerPredicates().filter(type::isInstance).map(type::cast);
    }

    /**
     *
     * @param var variable of interest
     * @param type the class of Predicate to return
     * @param <T>  the type of Predicate to return
     * @return stream of all predicates (public and inner) relevant to this atom and variable
     */
    public <T extends Predicate> Stream<T> getAllPredicates(Variable var, Class<T> type) {
        return Stream.concat(
                getPredicates(type),
                getInnerPredicates(type)
        ).filter(p -> p.getVarName().equals(var));
    }

    /**
     * @return set of types relevant to this atom
     */
    public Stream<TypeAtom> getTypeConstraints() {
        return getParentQuery().getAtoms(TypeAtom.class)
                .filter(atom -> atom != this)
                .filter(atom -> containsVar(atom.getVarName()));
    }

    /**
     * @param type the class of Predicate to return
     * @param <T>  the type of neighbour Atomic to return
     * @return neighbours of this atoms, i.e. atoms connected to this atom via shared variable
     */
    protected <T extends Atomic> Stream<T> getNeighbours(Class<T> type) {
        return getParentQuery().getAtoms(type)
                .filter(atom -> atom != this)
                .filter(atom -> !Sets.intersection(this.getVarNames(), atom.getVarNames()).isEmpty());
    }

    /**
     * @return set of constraints of this atom (predicates + types) that are not selectable
     */
    public Stream<Atomic> getNonSelectableConstraints() {
        return Stream.concat(
                Stream.concat(
                        getPredicates(),
                        getPredicates().flatMap(AtomicBase::getPredicates)
                ),
                getTypeConstraints().filter(at -> !at.isSelectable())
        );
    }

    @Override
    public Atom inferTypes() { return inferTypes(new ConceptMap()); }

    @Override
    public Atom inferTypes(ConceptMap sub) { return this; }

    /**
     * @return list of types this atom can take
     */
    public ImmutableList<Type> getPossibleTypes() {
        return (getSchemaConcept() != null && getSchemaConcept().isType())?
                ImmutableList.of(getSchemaConcept().asType()) :
                ImmutableList.of();}

    /**
     * @param sub partial substitution
     * @return list of possible atoms obtained by applying type inference
     */
    public List<Atom> atomOptions(ConceptMap sub) { return Lists.newArrayList(inferTypes(sub));}

    /**
     * @param type to be added to this Atom
     * @return new Atom with specified type
     */
    public Atom addType(SchemaConcept type) { return this;}

    /**
     * Materialises the atom - does an insert of the corresponding pattern.
     * Exhibits PUT behaviour - if things are already present, nothing is inserted.
     *
     * @return materialised answer to this atom
     */
    public Stream<ConceptMap> materialise() { return Stream.empty();}

    /**
     * @return set of atoms this atom can be decomposed to
     */
    public Set<Atom> rewriteToAtoms() { return Sets.newHashSet(this);}

    /**
     * rewrites the atom to user-defined type variable
     *
     * @param parentAtom parent atom that triggers rewrite
     * @return rewritten atom
     */
    public Atom rewriteWithTypeVariable(Atom parentAtom) {
        if (parentAtom.getPredicateVariable().isReturned()
                && !this.getPredicateVariable().isReturned()
                && this.getClass() == parentAtom.getClass()) {
            return rewriteWithTypeVariable();
        }
        return this;
    }

    /**
     * rewrites the atom to one with suitably user-defined names depending on provided parent
     *
     * @param parentAtom parent atom that triggers rewrite
     * @return rewritten atom
     */
    public abstract Atom rewriteToUserDefined(Atom parentAtom);


    public abstract Atom rewriteWithTypeVariable();

    /**
     * rewrites the atom to one with user defined relation variable
     *
     * @return rewritten atom
     */
    public Atom rewriteWithRelationVariable() { return this;}

    /**
     * attempt to find a UNIQUE unifier with the parent atom
     *
     * @param parentAtom  atom to be unified with
     * @param unifierType type of unifier to be computed
     * @return corresponding unifier
     */
    @Nullable
    public abstract Unifier getUnifier(Atom parentAtom, UnifierType unifierType);

    /**
     *
     * @param parentAtom atom wrt which we check the compatibility
     * @param unifier mappings between this (child) and parent variables
     * @param unifierType unifier type in question
     * @return true if predicates between this (child) and parent are compatible based on the mappings provided by unifier
     */
    protected boolean isPredicateCompatible(Atom parentAtom, Unifier unifier, UnifierType unifierType){
        //check value predicates compatibility
        return unifier.mappings().stream().allMatch(mapping -> {
            Variable childVar = mapping.getKey();
            Variable parentVar = mapping.getValue();
            Set<Atomic> parentIdPredicates = parentAtom.getPredicates(parentVar, IdPredicate.class).collect(Collectors.toSet());
            Set<Atomic> childIdPredicates = this.getPredicates(childVar, IdPredicate.class).collect(Collectors.toSet());
            Set<Atomic> parentValuePredicates = parentAtom.getAllPredicates(parentVar, ValuePredicate.class).collect(Collectors.toSet());
            Set<Atomic> childValuePredicates = this.getAllPredicates(childVar, ValuePredicate.class).collect(Collectors.toSet());

            if (unifierType.inferValues()) {
                parentAtom.getParentQuery().getAtoms(IdPredicate.class)
                        .filter(id -> id.getVarName().equals(parentVar))
                        .map(IdPredicate::toValuePredicate)
                        .filter(Objects::nonNull)
                        .forEach(parentValuePredicates::add);
                this.getParentQuery().getAtoms(IdPredicate.class)
                        .filter(id -> id.getVarName().equals(childVar))
                        .map(IdPredicate::toValuePredicate)
                        .filter(Objects::nonNull)
                        .forEach(childValuePredicates::add);
            }

            return unifierType.idCompatibility(parentIdPredicates, childIdPredicates)
                    && unifierType.valueCompatibility(parentValuePredicates, childValuePredicates);
        });
    }

    /**
     * find the (multi) unifier with parent atom
     *
     * @param parentAtom  atom to be unified with
     * @param unifierType type of unifier to be computed
     * @return multiunifier
     */
    public MultiUnifier getMultiUnifier(Atom parentAtom, UnifierType unifierType) {
        //NB only for relations we can have non-unique unifiers
        Unifier unifier = this.getUnifier(parentAtom, unifierType);
        return unifier != null ? new MultiUnifierImpl(unifier) : MultiUnifierImpl.nonExistent();
    }

    /**
     * Calculates the semantic difference between the parent and this (child) atom,
     * that needs to be applied on A(P) to find the subset belonging to A(C).
     *
     * @param parentAtom parent atom
     * @param unifier    child->parent unifier
     * @return semantic difference between child and parent
     */
    public SemanticDifference semanticDifference(Atom parentAtom, Unifier unifier) {
        Set<VariableDefinition> diff = new HashSet<>();
        Unifier unifierInverse = unifier.inverse();

        unifier.mappings().forEach(m -> {
            Variable childVar = m.getKey();
            Variable parentVar = m.getValue();

            Type childType = this.getParentQuery().getUnambiguousType(childVar, false);
            Type parentType = parentAtom.getParentQuery().getUnambiguousType(parentVar, false);
            Type type = childType != null ?
                    parentType != null ?
                            (!parentType.equals(childType) ? childType : null) :
                            childType
                    : null;


            Set<ValuePredicate> predicates = this.getPredicates(childVar, ValuePredicate.class).collect(toSet());
            parentAtom.getPredicates(parentVar, ValuePredicate.class)
                    .flatMap(vp -> vp.unify(unifierInverse).stream())
                    .forEach(predicates::remove);

            diff.add(new VariableDefinition(childVar, type, null, new HashSet<>(), predicates));
        });
        return new SemanticDifference(diff);
    }
}
