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
package grakn.core.graql.reasoner.atom.binary;

import com.google.auto.value.AutoValue;
import com.google.auto.value.extension.memoized.Memoized;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import grakn.core.common.exception.ErrorMessage;
import grakn.core.common.util.Streams;
import grakn.core.concept.Concept;
import grakn.core.concept.ConceptId;
import grakn.core.concept.Label;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.concept.thing.Relation;
import grakn.core.concept.type.RelationType;
import grakn.core.concept.type.Role;
import grakn.core.concept.type.Rule;
import grakn.core.concept.type.SchemaConcept;
import grakn.core.concept.type.Type;
import grakn.core.graql.exception.GraqlCheckedException;
import grakn.core.graql.exception.GraqlQueryException;
import grakn.core.graql.exception.GraqlSemanticException;
import grakn.core.graql.reasoner.atom.Atom;
import grakn.core.graql.reasoner.atom.Atomic;
import grakn.core.graql.reasoner.atom.predicate.IdPredicate;
import grakn.core.graql.reasoner.atom.predicate.Predicate;
import grakn.core.graql.reasoner.atom.predicate.ValuePredicate;
import grakn.core.graql.reasoner.cache.SemanticDifference;
import grakn.core.graql.reasoner.cache.VariableDefinition;
import grakn.core.graql.reasoner.query.ReasonerAtomicQuery;
import grakn.core.graql.reasoner.query.ReasonerQueries;
import grakn.core.graql.reasoner.query.ReasonerQuery;
import grakn.core.graql.reasoner.query.ReasonerQueryEquivalence;
import grakn.core.graql.reasoner.query.ReasonerQueryImpl;
import grakn.core.graql.reasoner.unifier.MultiUnifier;
import grakn.core.graql.reasoner.unifier.MultiUnifierImpl;
import grakn.core.graql.reasoner.unifier.Unifier;
import grakn.core.graql.reasoner.unifier.UnifierImpl;
import grakn.core.graql.reasoner.unifier.UnifierType;
import grakn.core.graql.reasoner.utils.Pair;
import grakn.core.graql.reasoner.utils.ReasonerUtils;
import grakn.core.graql.reasoner.utils.conversion.RoleConverter;
import grakn.core.graql.reasoner.utils.conversion.TypeConverter;
import grakn.core.server.kb.Schema;
import grakn.core.server.kb.concept.ConceptUtils;
import grakn.core.server.kb.concept.RelationTypeImpl;
import grakn.core.server.session.TransactionOLTP;
import graql.lang.Graql;
import graql.lang.pattern.Pattern;
import graql.lang.property.IsaProperty;
import graql.lang.property.RelationProperty;
import graql.lang.property.VarProperty;
import graql.lang.statement.Statement;
import graql.lang.statement.StatementInstance;
import graql.lang.statement.StatementThing;
import graql.lang.statement.Variable;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static grakn.core.server.kb.concept.ConceptUtils.bottom;
import static grakn.core.server.kb.concept.ConceptUtils.top;
import static graql.lang.Graql.var;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

/**
 * Atom implementation defining a relation atom corresponding to a combined RelationProperty
 * and (optional) IsaProperty. The relation atom is a TypeAtom with relation players.
 */
@AutoValue
public abstract class RelationAtom extends IsaAtomBase {

    abstract ImmutableList<RelationProperty.RolePlayer> getRelationPlayers();
    abstract ImmutableSet<Label> getRoleLabels();

    private ImmutableList<Type> possibleTypes = null;

    public static RelationAtom create(Statement pattern, Variable predicateVar, @Nullable ConceptId predicateId, ReasonerQuery parent) {
        List<RelationProperty.RolePlayer> rps = new ArrayList<>();
        pattern.getProperty(RelationProperty.class)
                .ifPresent(prop -> prop.relationPlayers().stream().sorted(Comparator.comparing(Object::hashCode)).forEach(rps::add));
        ImmutableList<RelationProperty.RolePlayer> relationPlayers = ImmutableList.copyOf(rps);
        ImmutableSet<Label> roleLabels = ImmutableSet.<Label>builder().addAll(
                relationPlayers.stream()
                        .map(RelationProperty.RolePlayer::getRole)
                        .flatMap(Streams::optionalToStream)
                        .map(Statement::getType)
                        .flatMap(Streams::optionalToStream)
                        .map(Label::of).iterator()
        ).build();
        return new AutoValue_RelationAtom(pattern.var(), pattern, parent, predicateVar, predicateId, relationPlayers, roleLabels);
    }

    private static RelationAtom create(Statement pattern, Variable predicateVar, @Nullable ConceptId predicateId, @Nullable ImmutableList<Type> possibleTypes, ReasonerQuery parent) {
        RelationAtom atom = create(pattern, predicateVar, predicateId, parent);
        atom.possibleTypes = possibleTypes;
        return atom;
    }

    private static RelationAtom create(RelationAtom a, ReasonerQuery parent) {
        RelationAtom atom = new AutoValue_RelationAtom(a.getVarName(), a.getPattern(), parent, a.getPredicateVariable(), a.getTypeId(), a.getRelationPlayers(), a.getRoleLabels());
        atom.possibleTypes = a.possibleTypes;
        return atom;
    }

    //NB: overriding as these require a derived property
    @Override
    public final boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || this.getClass() != obj.getClass()) return false;
        RelationAtom that = (RelationAtom) obj;
        return Objects.equals(this.getTypeId(), that.getTypeId())
                && this.isUserDefined() == that.isUserDefined()
                && this.isDirect() == that.isDirect()
                && this.getVarNames().equals(that.getVarNames())
                && this.getRelationPlayers().equals(that.getRelationPlayers());
    }

    @Memoized
    @Override
    public int hashCode() {
        int hashCode = 1;
        hashCode = hashCode * 37 + (getTypeId() != null ? getTypeId().hashCode() : 0);
        hashCode = hashCode * 37 + getVarNames().hashCode();
        hashCode = hashCode * 37 + getRelationPlayers().hashCode();
        return hashCode;
    }

    @Override
    public Class<? extends VarProperty> getVarPropertyClass(){ return RelationProperty.class;}

    private void checkPattern(){
        getPattern().getProperties(RelationProperty.class)
                .flatMap(p -> p.relationPlayers().stream())
                .map(RelationProperty.RolePlayer::getRole).flatMap(Streams::optionalToStream)
                .map(Statement::getType).flatMap(Streams::optionalToStream)
                .map(Label::of)
                .forEach(roleId -> {
                    SchemaConcept schemaConcept = tx().getSchemaConcept(roleId);
                    if (schemaConcept == null || !schemaConcept.isRole()) {
                        throw GraqlSemanticException.invalidRoleLabel(roleId);
                    }
                });
    }

    @Override
    public void checkValid(){
        super.checkValid();
        SchemaConcept type = getSchemaConcept();
        if (type != null && !type.isRelationType()){
            throw GraqlSemanticException.relationWithNonRelationType(type.label());
        }
        checkPattern();
    }

    @Override
    public RelationAtom toRelationAtom(){ return this;}

    @Override
    public AttributeAtom toAttributeAtom(){
        SchemaConcept type = getSchemaConcept();
        if (type == null || !type.isImplicit()) {
            throw GraqlQueryException.illegalAtomConversion(this, AttributeAtom.class);
        }
        TransactionOLTP tx = getParentQuery().tx();
        Label explicitLabel = Schema.ImplicitType.explicitLabel(type.label());
        Role ownerRole = tx.getRole(Schema.ImplicitType.HAS_OWNER.getLabel(explicitLabel).getValue());
        Role valueRole = tx.getRole(Schema.ImplicitType.HAS_VALUE.getLabel(explicitLabel).getValue());
        Multimap<Role, Variable> roleVarMap = getRoleVarMap();
        Variable relationVariable = getVarName();
        Variable ownerVariable = Iterables.getOnlyElement(roleVarMap.get(ownerRole));
        Variable attributeVariable = Iterables.getOnlyElement(roleVarMap.get(valueRole));

        Statement attributeStatement = relationVariable.isReturned() ?
                var(ownerVariable).has(explicitLabel.getValue(), var(attributeVariable), var(relationVariable)) :
                var(ownerVariable).has(explicitLabel.getValue(), var(attributeVariable));
        AttributeAtom attributeAtom = AttributeAtom.create(
                attributeStatement,
                attributeVariable,
                relationVariable,
                getPredicateVariable(),
                tx.getSchemaConcept(explicitLabel).id(),
                new HashSet<>(),
                getParentQuery()
        );

        Set<Statement> patterns = new HashSet<>(attributeAtom.getCombinedPattern().statements());
        this.getPredicates().map(Predicate::getPattern).forEach(patterns::add);
        return ReasonerQueries.atomic(Graql.and(patterns), tx()).getAtom().toAttributeAtom();
    }


    @Override
    public IsaAtom toIsaAtom(){
        IsaAtom isaAtom = IsaAtom.create(getVarName(), getPredicateVariable(), getTypeId(), false, getParentQuery());
        Set<Statement> patterns = new HashSet<>(isaAtom.getCombinedPattern().statements());
        this.getPredicates().map(Predicate::getPattern).forEach(patterns::add);
        return ReasonerQueries.atomic(Graql.and(patterns), tx()).getAtom().toIsaAtom();
    }

    @Override
    public Set<Atom> rewriteToAtoms(){
        return this.getRelationPlayers().stream()
                .map(rp -> create(relationPattern(getVarName().asReturnedVar(), Sets.newHashSet(rp)), getPredicateVariable(), getTypeId(), null, this.getParentQuery()))
                .collect(toSet());
    }

    @Override
    public String toString(){
        String typeString = getSchemaConcept() != null?
                getSchemaConcept().label().getValue() :
                "{" + inferPossibleTypes(new ConceptMap()).stream().map(rt -> rt.label().getValue()).collect(Collectors.joining(", ")) + "}";
        String relationString = (isUserDefined()? getVarName() + " ": "") +
                typeString +
                (getPredicateVariable().isReturned()? "(" + getPredicateVariable() + ")" : "") +
                (isDirect()? "!" : "") +
                getRelationPlayers().toString();
        return relationString + getPredicates(Predicate.class).map(Predicate::toString).collect(Collectors.joining(""));
    }

    @Override
    public Set<Variable> getVarNames() {
        Set<Variable> vars = super.getVarNames();
        vars.addAll(getRolePlayers());
        vars.addAll(getRoleVariables());
        return vars;
    }

    /**
     * Determines the roleplayer directionality in the form of variable pairs.
     * NB: Currently we determine the directionality based on the role hashCode.
     * @return set of pairs of roleplayers arranged in terms of directionality
     */
    public Set<Pair<Variable, Variable>> varDirectionality(){
        Multimap<Role, Variable> roleVarMap = this.getRoleVarMap();
        Multimap<Variable, Role> varRoleMap = HashMultimap.create();
        roleVarMap.entries().forEach(e -> varRoleMap.put(e.getValue(), e.getKey()));

        List<Role> roleOrdering = roleVarMap.keySet().stream()
                .sorted(Comparator.comparing(r -> r.label().hashCode()))
                .distinct()
                .collect(toList());

        Set<Pair<Variable, Variable>> varPairs = new HashSet<>();
        roleVarMap.values().forEach(var -> {
                    Collection<Role> rolePlayed = varRoleMap.get(var);
                    rolePlayed.stream()
                            .sorted(Comparator.comparing(Object::hashCode))
                            .forEach(role -> {
                                int index = roleOrdering.indexOf(role);
                                List<Role> roles = roleOrdering.subList(index, roleOrdering.size());
                                roles.forEach(role2 -> roleVarMap.get(role2).stream()
                                        .filter(var2 -> !role.equals(role2) || !var.equals(var2))
                                        .forEach(var2 -> varPairs.add(new Pair<>(var, var2))));
                            });
                }
        );
        return varPairs;
    }

    /**
     * @return set constituting the role player var names
     */
    private Set<Variable> getRolePlayers() {
        return getRelationPlayers().stream().map(c -> c.getPlayer().var()).collect(toSet());
    }

    /**
     * @return set of user defined role variables if any
     */
    private Set<Variable> getRoleVariables(){
        return getRelationPlayers().stream()
                .map(RelationProperty.RolePlayer::getRole)
                .flatMap(Streams::optionalToStream)
                .map(Statement::var)
                .filter(Variable::isReturned)
                .collect(Collectors.toSet());
    }

    private ConceptMap getRoleSubstitution(){
        Map<Variable, Concept> roleSub = new HashMap<>();
        getRolePredicates().forEach(p -> roleSub.put(p.getVarName(), tx().getConcept(p.getPredicate())));
        return new ConceptMap(roleSub);
    }

    @Override
    public Atomic copy(ReasonerQuery parent) {
        return create(this, parent);
    }

    @Override
    protected Pattern createCombinedPattern(){
        if (getPredicateVariable().isReturned()) return super.createCombinedPattern();
        return getSchemaConcept() == null?
                relationPattern() :
                isDirect()?
                        relationPattern().isaX(getSchemaConcept().label().getValue()):
                        relationPattern().isa(getSchemaConcept().label().getValue());
    }

    private Statement relationPattern() {
        return relationPattern(getVarName(), getRelationPlayers());
    }

    /**
     * construct a $varName (rolemap) isa $typeVariable relation
     * @param varName            variable name
     * @param relationPlayers collection of rolePlayer-roleType mappings
     * @return corresponding Statement
     */
    private Statement relationPattern(Variable varName, Collection<RelationProperty.RolePlayer> relationPlayers) {
        Statement var = new Statement(varName);
        for (RelationProperty.RolePlayer rp : relationPlayers) {
            Statement rolePattern = rp.getRole().orElse(null);
            var = rolePattern != null? var.rel(rolePattern, rp.getPlayer()) : var.rel(rp.getPlayer());
        }
        return var;
    }

    @Override
    boolean isBaseEquivalent(Object obj){
        if (!super.isBaseEquivalent(obj)) return false;
        RelationAtom that = (RelationAtom) obj;
        //check relation players equivalent
        return this.getRolePlayers().size() == that.getRolePlayers().size()
                && this.getRelationPlayers().size() == that.getRelationPlayers().size()
                && this.getRoleLabels().equals(that.getRoleLabels());
    }

    private int baseHashCode(){
        int baseHashCode = 1;
        baseHashCode = baseHashCode * 37 + (this.getTypeId() != null ? this.getTypeId().hashCode() : 0);
        baseHashCode = baseHashCode * 37 + this.getRoleLabels().hashCode();
        return baseHashCode;
    }

    @Memoized
    @Override
    public int alphaEquivalenceHashCode() {
        int equivalenceHashCode = baseHashCode();
        SortedSet<Integer> hashes = new TreeSet<>();
        this.getRoleTypeMap().entries().stream()
                .sorted(Comparator.comparing(e -> e.getKey().label()))
                .sorted(Comparator.comparing(e -> e.getValue().label()))
                .forEach(e -> hashes.add(e.hashCode()));
        this.getRoleConceptIdMap().entries().stream()
                .sorted(Comparator.comparing(e -> e.getKey().label()))
                .sorted(Comparator.comparing(Map.Entry::getValue))
                .forEach(e -> hashes.add(e.hashCode()));
        for (Integer hash : hashes) equivalenceHashCode = equivalenceHashCode * 37 + hash;
        return equivalenceHashCode;
    }

    @Override
    public int structuralEquivalenceHashCode() {
        int equivalenceHashCode = baseHashCode();
        equivalenceHashCode = equivalenceHashCode * 37 + this.getRoleTypeMap(false).hashCode();
        equivalenceHashCode = equivalenceHashCode * 37 + this.getRoleConceptIdMap().keySet().hashCode();
        return equivalenceHashCode;
    }

    @Override
    public boolean isRelation() {
        return true;
    }

    @Override
    public boolean isSelectable() {
        return true;
    }

    @Override
    public boolean isType() {
        return getSchemaConcept() != null;
    }

    @Override
    public boolean requiresMaterialisation() {
        return isUserDefined();
    }

    @Override
    public boolean requiresRoleExpansion() {
        return !getRoleVariables().isEmpty();
    }

    @Override
    public Set<String> validateAsRuleHead(Rule rule){
        //can form a rule head if type is specified, type is not implicit and all relation players are insertable
        return Sets.union(super.validateAsRuleHead(rule), validateRelationPlayers(rule));
    }

    private Set<String> validateRelationPlayers(Rule rule){
        Set<String> errors = new HashSet<>();
        getRelationPlayers().forEach(rp -> {
            Statement role = rp.getRole().orElse(null);
            if (role == null){
                errors.add(ErrorMessage.VALIDATION_RULE_ILLEGAL_HEAD_RELATION_WITH_AMBIGUOUS_ROLE.getMessage(rule.then(), rule.label()));
            } else {
                String roleLabel = role.getType().orElse(null);
                if (roleLabel == null){
                    errors.add(ErrorMessage.VALIDATION_RULE_ILLEGAL_HEAD_RELATION_WITH_AMBIGUOUS_ROLE.getMessage(rule.then(), rule.label()));
                } else {
                    if (Schema.MetaSchema.isMetaLabel(Label.of(roleLabel))) {
                        errors.add(ErrorMessage.VALIDATION_RULE_ILLEGAL_HEAD_RELATION_WITH_AMBIGUOUS_ROLE.getMessage(rule.then(), rule.label()));
                    }
                    Role roleType = tx().getRole(roleLabel);
                    if (roleType != null && roleType.isImplicit()) {
                        errors.add(ErrorMessage.VALIDATION_RULE_ILLEGAL_HEAD_RELATION_WITH_IMPLICIT_ROLE.getMessage(rule.then(), rule.label()));
                    }
                }
            }
        });
        return errors;
    }

    @Override
    public Set<String> validateAsRuleBody(Label ruleLabel) {
        Set<String> errors = new HashSet<>();
        SchemaConcept type = getSchemaConcept();
        if (type != null && !type.isRelationType()){
            errors.add(ErrorMessage.VALIDATION_RULE_INVALID_RELATION_TYPE.getMessage(ruleLabel, type.label()));
            return errors;
        }

        //check role-type compatibility
        SetMultimap<Variable, Type> varTypeMap = getParentQuery().getVarTypeMap();
        for (Map.Entry<Role, Collection<Variable>> e : getRoleVarMap().asMap().entrySet() ){
            Role role = e.getKey();
            if (!Schema.MetaSchema.isMetaLabel(role.label())) {
                //check whether this role can be played in this relation
                if (type != null && type.asRelationType().roles().noneMatch(r -> r.equals(role))) {
                    errors.add(ErrorMessage.VALIDATION_RULE_ROLE_CANNOT_BE_PLAYED.getMessage(ruleLabel, role.label(), type.label()));
                }

                //check whether the role player's type allows playing this role
                for (Variable player : e.getValue()) {
                    varTypeMap.get(player).stream()
                            .filter(playerType -> playerType.playing().noneMatch(plays -> plays.equals(role)))
                            .forEach(playerType ->
                                errors.add(ErrorMessage.VALIDATION_RULE_TYPE_CANNOT_PLAY_ROLE.getMessage(ruleLabel, playerType.label(), role.label(), type == null ? "" : type.label()))
                    );
                }
            }
        }
        return errors;
    }

    public Stream<IdPredicate> getRolePredicates(){
        return getRelationPlayers().stream()
                .map(RelationProperty.RolePlayer::getRole)
                .flatMap(Streams::optionalToStream)
                .filter(var -> var.var().isReturned())
                .filter(vp -> vp.getType().isPresent())
                .map(vp -> {
                    String label = vp.getType().orElse(null);
                    return IdPredicate.create(vp.var(), tx().getRole(label).id(), getParentQuery());
                });
    }

    private <T extends Predicate> Multimap<Role, T> getRolePredicateMap(Class<T> type) {
        HashMultimap<Role, T> rolePredicateMap = HashMultimap.create();

        HashMultimap<Variable, T> predicateMap = HashMultimap.create();
        getPredicates(type).forEach(p -> p.getVarNames().forEach(v -> predicateMap.put(v, p)));
        Multimap<Role, Variable> roleMap = getRoleVarMap();

        roleMap.entries().stream()
                .filter(e -> predicateMap.containsKey(e.getValue()))
                .forEach(e ->  rolePredicateMap.putAll(e.getKey(), predicateMap.get(e.getValue())));
        return rolePredicateMap;
    }

    /**
     * @return map of pairs role type - Id predicate describing the role player playing this role (substitution)
     */
    @Memoized
    public Multimap<Role, String> getRoleConceptIdMap() {
        ImmutableMultimap.Builder<Role, String> builder = ImmutableMultimap.builder();
        getRolePredicateMap(IdPredicate.class)
                .entries()
                .forEach(e -> builder.put(e.getKey(), e.getValue().getPredicateValue()));
        return builder.build();
    }

    @Memoized
    public Multimap<Role, Type> getRoleTypeMap() {
        return getRoleTypeMap(false);
    }

    private Multimap<Role, Type> getRoleTypeMap(boolean inferTypes) {
        ImmutableMultimap.Builder<Role, Type> builder = ImmutableMultimap.builder();
        Multimap<Role, Variable> roleMap = getRoleVarMap();
        SetMultimap<Variable, Type> varTypeMap = getParentQuery().getVarTypeMap(inferTypes);

        roleMap.entries().stream()
                .sorted(Comparator.comparing(e -> e.getKey().label()))
                .flatMap(e -> varTypeMap.get(e.getValue()).stream().map(type -> new Pair<>(e.getKey(), type)))
                .sorted(Comparator.comparing(Pair::hashCode))
                .forEach(p -> builder.put(p.getKey(), p.getValue()));
        return builder.build();
    }

    private Stream<Role> getExplicitRoles() {
        ReasonerQueryImpl parent = (ReasonerQueryImpl) getParentQuery();
        TransactionOLTP tx = parent.tx();

        return getRelationPlayers().stream()
                .map(RelationProperty.RolePlayer::getRole)
                .flatMap(Streams::optionalToStream)
                .map(Statement::getType)
                .flatMap(Streams::optionalToStream)
                .map(tx::getRole)
                .filter(Objects::nonNull);
    }

    @Override
    public boolean isRuleApplicableViaAtom(Atom ruleAtom) {
        if (!(ruleAtom instanceof RelationAtom)) return isRuleApplicableViaAtom(ruleAtom.toRelationAtom());
        RelationAtom atomWithType = this.addType(ruleAtom.getSchemaConcept()).inferRoles(new ConceptMap());
        return ruleAtom.isUnifiableWith(atomWithType);
    }

    @Override
    public RelationAtom addType(SchemaConcept type) {
        if (getTypeId() != null) return this;
        //NB: do not cache possible types
        return create(this.getPattern(), this.getPredicateVariable(), type.id(), this.getParentQuery());
    }

    /**
     * infer RelationTypes that this RelationAtom can potentially have
     * NB: EntityTypes and link Roles are treated separately as they behave differently:
     * EntityTypes only play the explicitly defined Roles (not the relevant part of the hierarchy of the specified Role) and the Role inherited from parent
     * @return list of RelationTypes this atom can have ordered by the number of compatible Roles
     */
    private Set<Type> inferPossibleEntityTypePlayers(ConceptMap sub){
        return inferPossibleRelationConfigurations(sub).asMap().entrySet().stream()
                .flatMap(e -> {
                    Set<Role> rs = e.getKey().roles().collect(toSet());
                    rs.removeAll(e.getValue());
                    return rs.stream().flatMap(Role::players);
                }).collect(Collectors.toSet());
    }

    /**
     * @return a map of relations and corresponding roles that could be played by this atom
     */
    private Multimap<RelationType, Role> inferPossibleRelationConfigurations(ConceptMap sub){
        Set<Role> roles = getExplicitRoles().filter(r -> !Schema.MetaSchema.isMetaLabel(r.label())).collect(toSet());
        SetMultimap<Variable, Type> varTypeMap = getParentQuery().getVarTypeMap(sub);
        Set<Type> types = getRolePlayers().stream().filter(varTypeMap::containsKey).flatMap(v -> varTypeMap.get(v).stream()).collect(toSet());

        if (roles.isEmpty() && types.isEmpty()){
            RelationType metaRelationType = tx().getMetaRelationType();
            Multimap<RelationType, Role> compatibleTypes = HashMultimap.create();
            metaRelationType.subs()
                    .filter(rt -> !rt.equals(metaRelationType))
                    .forEach(rt -> compatibleTypes.putAll(rt, rt.roles().collect(toSet())));
            return compatibleTypes;
        }

        //intersect relation types from roles and types
        Multimap<RelationType, Role> compatibleTypes;

        Multimap<RelationType, Role> compatibleTypesFromRoles = ReasonerUtils.compatibleRelationTypesWithRoles(roles, new RoleConverter());
        Multimap<RelationType, Role> compatibleTypesFromTypes = ReasonerUtils.compatibleRelationTypesWithRoles(types, new TypeConverter());

        if (roles.isEmpty()){
            compatibleTypes = compatibleTypesFromTypes;
        }
        //no types from roles -> roles correspond to mutually exclusive relations
        else if(compatibleTypesFromRoles.isEmpty() || types.isEmpty()){
            compatibleTypes = compatibleTypesFromRoles;
        } else {
            compatibleTypes = ReasonerUtils.multimapIntersection(compatibleTypesFromTypes, compatibleTypesFromRoles);
        }
        return compatibleTypes;
    }

    @Override
    public ImmutableList<Type> getPossibleTypes(){ return inferPossibleTypes(new ConceptMap());}

    /**
     * infer RelationTypes that this RelationAtom can potentially have
     * NB: EntityTypes and link Roles are treated separately as they behave differently:
     * NB: Not using Memoized as memoized methods can't have parameters
     * EntityTypes only play the explicitly defined Roles (not the relevant part of the hierarchy of the specified Role) and the Role inherited from parent
     * @return list of RelationTypes this atom can have ordered by the number of compatible Roles
     */
    private ImmutableList<Type> inferPossibleTypes(ConceptMap sub) {
        if (possibleTypes == null) {
            if (getSchemaConcept() != null) return ImmutableList.of(getSchemaConcept().asType());

            Multimap<RelationType, Role> compatibleConfigurations = inferPossibleRelationConfigurations(sub);
            Set<Variable> untypedRoleplayers = Sets.difference(getRolePlayers(), getParentQuery().getVarTypeMap().keySet());
            Set<RelationAtom> untypedNeighbours = getNeighbours(RelationAtom.class)
                    .filter(at -> !Sets.intersection(at.getVarNames(), untypedRoleplayers).isEmpty())
                    .collect(toSet());

            ImmutableList.Builder<Type> builder = ImmutableList.builder();
            //prioritise relations with higher chance of yielding answers
            compatibleConfigurations.asMap().entrySet().stream()
                    //prioritise relations with more allowed roles
                    .sorted(Comparator.comparing(e -> -e.getValue().size()))
                    //prioritise relations with number of roles equal to arity
                    .sorted(Comparator.comparing(e -> e.getKey().roles().count() != getRelationPlayers().size()))
                    //prioritise relations having more instances
                    .sorted(Comparator.comparing(e -> -tx().getShardCount(e.getKey())))
                    //prioritise relations with highest number of possible types played by untyped role players
                    .map(e -> {
                        if (untypedNeighbours.isEmpty()) return new Pair<>(e.getKey(), 0L);

                        Iterator<RelationAtom> neighbourIterator = untypedNeighbours.iterator();
                        Set<Type> typesFromNeighbour = neighbourIterator.next().inferPossibleEntityTypePlayers(sub);
                        while (neighbourIterator.hasNext()) {
                            typesFromNeighbour = Sets.intersection(typesFromNeighbour, neighbourIterator.next().inferPossibleEntityTypePlayers(sub));
                        }

                        Set<Role> rs = e.getKey().roles().collect(toSet());
                        rs.removeAll(e.getValue());
                        return new Pair<>(
                                e.getKey(),
                                rs.stream().flatMap(Role::players).filter(typesFromNeighbour::contains).count()
                        );
                    })
                    .sorted(Comparator.comparing(p -> -p.getValue()))
                    //prioritise non-implicit relations
                    .sorted(Comparator.comparing(e -> e.getKey().isImplicit()))
                    .map(Pair::getKey)
                    //retain super types only
                    .filter(t -> Sets.intersection(ConceptUtils.nonMetaSups(t), compatibleConfigurations.keySet()).isEmpty())
                    .forEach(builder::add);

            //TODO need to add THING and meta relation type as well to make it complete
            this.possibleTypes = builder.build();
        }
        return possibleTypes;
    }

    /**
     * attempt to infer the relation type of this relation
     * @param sub extra instance information to aid entity type inference
     * @return either this if relation type can't be inferred or a fresh relation with inferred relation type
     */
    private RelationAtom inferRelationType(ConceptMap sub){
        if (getTypePredicate() != null) return this;
        if (sub.containsVar(getPredicateVariable())) return addType(sub.get(getPredicateVariable()).asType());
        List<Type> relationTypes = inferPossibleTypes(sub);
        if (relationTypes.size() == 1) return addType(Iterables.getOnlyElement(relationTypes));
        return this;
    }

    @Override
    public RelationAtom inferTypes(ConceptMap sub) {
        return this
                .inferRelationType(sub)
                .inferRoles(sub);
    }

    @Override
    public List<Atom> atomOptions(ConceptMap sub) {
        return this.inferPossibleTypes(sub).stream()
                .map(this::addType)
                .map(at -> at.inferRoles(sub))
                //order by number of distinct roles
                .sorted(Comparator.comparing(at -> -at.getRoleLabels().size()))
                .sorted(Comparator.comparing(Atom::isRuleResolvable))
                .collect(Collectors.toList());
    }

    @Override
    public Set<Variable> getRoleExpansionVariables(){
        return getRelationPlayers().stream()
                .map(RelationProperty.RolePlayer::getRole)
                .flatMap(Streams::optionalToStream)
                .filter(p -> p.var().isReturned())
                .filter(p -> !p.getType().isPresent())
                .map(Statement::var)
                .collect(Collectors.toSet());
    }

    @Override
    public Stream<Predicate> getInnerPredicates(){
        return Stream.concat(
                super.getInnerPredicates(),
                getRelationPlayers().stream()
                        .map(RelationProperty.RolePlayer::getRole)
                        .flatMap(Streams::optionalToStream)
                        .filter(vp -> vp.var().isReturned())
                        .map(vp -> new Pair<>(vp.var(), vp.getType().orElse(null)))
                        .filter(p -> Objects.nonNull(p.getValue()))
                        .map(p -> IdPredicate.create(p.getKey(), Label.of(p.getValue()), getParentQuery()))
        );
    }

    /**
     * attempt to infer role types of this relation and return a fresh relation with inferred role types
     * @return either this if nothing/no roles can be inferred or fresh relation with inferred role types
     */
    private RelationAtom inferRoles(ConceptMap sub){
        //return if all roles known and non-meta
        List<Role> explicitRoles = getExplicitRoles().collect(Collectors.toList());
        SetMultimap<Variable, Type> varTypeMap = getParentQuery().getVarTypeMap(sub);
        boolean allRolesMeta = explicitRoles.stream().allMatch(role -> Schema.MetaSchema.isMetaLabel(role.label()));
        boolean roleRecomputationViable = allRolesMeta && (!sub.isEmpty() || !Sets.intersection(varTypeMap.keySet(), getRolePlayers()).isEmpty());
        if (explicitRoles.size() == getRelationPlayers().size() && !roleRecomputationViable) return this;

        TransactionOLTP tx = getParentQuery().tx();
        Role metaRole = tx.getMetaRole();
        List<RelationProperty.RolePlayer> allocatedRelationPlayers = new ArrayList<>();
        SchemaConcept schemaConcept = getSchemaConcept();
        RelationType relType = null;
        if (schemaConcept != null && schemaConcept.isRelationType()) relType = schemaConcept.asRelationType();

        //explicit role types from castings
        List<RelationProperty.RolePlayer> inferredRelationPlayers = new ArrayList<>();
        getRelationPlayers().forEach(rp -> {
            Variable varName = rp.getPlayer().var();
            Statement rolePattern = rp.getRole().orElse(null);
            if (rolePattern != null) {
                String roleLabel = rolePattern.getType().orElse(null);
                //allocate if variable role or if label non meta
                if (roleLabel == null || !Schema.MetaSchema.isMetaLabel(Label.of(roleLabel))) {
                    inferredRelationPlayers.add(new RelationProperty.RolePlayer(rolePattern, new Statement(varName)));
                    allocatedRelationPlayers.add(rp);
                }
            }
        });

        //remaining roles
        //role types can repeat so no matter what has been allocated still the full spectrum of possibilities is present
        //TODO make restrictions based on cardinality constraints
        Set<Role> possibleRoles = relType != null?
                relType.roles().collect(toSet()) :
                inferPossibleTypes(sub).stream()
                        .filter(Concept::isRelationType)
                        .map(Concept::asRelationType)
                        .flatMap(RelationType::roles).collect(toSet());

        //possible role types for each casting based on its type
        Map<RelationProperty.RolePlayer, Set<Role>> mappings = new HashMap<>();
        getRelationPlayers().stream()
                .filter(rp -> !allocatedRelationPlayers.contains(rp))
                .forEach(rp -> {
                    Variable varName = rp.getPlayer().var();
                    Set<Type> types = varTypeMap.get(varName);
                    mappings.put(rp, top(ReasonerUtils.compatibleRoles(types, possibleRoles)));
                });


        //allocate all unambiguous mappings
        mappings.entrySet().stream()
                .filter(entry -> entry.getValue().size() == 1)
                .forEach(entry -> {
                    RelationProperty.RolePlayer rp = entry.getKey();
                    Variable varName = rp.getPlayer().var();
                    Role role = Iterables.getOnlyElement(entry.getValue());
                    Statement rolePattern = var().type(role.label().getValue());
                    inferredRelationPlayers.add(new RelationProperty.RolePlayer(rolePattern, new Statement(varName)));
                    allocatedRelationPlayers.add(rp);
                });

        //fill in unallocated roles with metarole
        getRelationPlayers().stream()
                .filter(rp -> !allocatedRelationPlayers.contains(rp))
                .forEach(rp -> {
                    Variable varName = rp.getPlayer().var();
                    Statement rolePattern = rp.getRole().orElse(null);

                    rolePattern = rolePattern != null ?
                            rolePattern.type(metaRole.label().getValue()) :
                            var().type(metaRole.label().getValue());
                    inferredRelationPlayers.add(new RelationProperty.RolePlayer(rolePattern, new Statement(varName)));
                });

        Statement relationPattern = relationPattern(getVarName(), inferredRelationPlayers);
        Statement newPattern =
                (isDirect()?
                        relationPattern.isaX(new Statement(getPredicateVariable())) :
                        relationPattern.isa(new Statement(getPredicateVariable()))
                );
        return create(newPattern, this.getPredicateVariable(), this.getTypeId(), this.getPossibleTypes(), this.getParentQuery());
    }

    /**
     * @return map containing roleType - (rolePlayer var - rolePlayer type) pairs
     */
    @Memoized
    public Multimap<Role, Variable> getRoleVarMap() {
        ImmutableMultimap.Builder<Role, Variable> builder = ImmutableMultimap.builder();

        TransactionOLTP tx = getParentQuery().tx();
        getRelationPlayers().forEach(c -> {
            Variable varName = c.getPlayer().var();
            Statement rolePattern = c.getRole().orElse(null);
            if (rolePattern != null) {
                //try directly
                String typeLabel = rolePattern.getType().orElse(null);
                Role role = typeLabel != null ? tx.getRole(typeLabel) : null;
                //try indirectly
                if (role == null && rolePattern.var().isReturned()) {
                    IdPredicate rolePredicate = getIdPredicate(rolePattern.var());
                    if (rolePredicate != null){
                        Role r = tx.getConcept(rolePredicate.getPredicate());
                        if (r == null) throw GraqlCheckedException.idNotFound(rolePredicate.getPredicate());
                        role = r;
                    }
                }
                if (role != null) builder.put(role, varName);
            }
        });
        return builder.build();
    }

    /**
     * @param parentAtom reference atom defining the mapping
     * @param unifierType type of match to be performed
     * @return set of possible COMPLETE mappings between this (child) and parent relation players
     */
    private Set<List<Pair<RelationProperty.RolePlayer, RelationProperty.RolePlayer>>> getRelationPlayerMappings(RelationAtom parentAtom, UnifierType unifierType) {
        SetMultimap<Variable, Type> childVarTypeMap = this.getParentQuery().getVarTypeMap(unifierType.inferTypes());
        SetMultimap<Variable, Type> parentVarTypeMap = parentAtom.getParentQuery().getVarTypeMap(unifierType.inferTypes());

        //establish compatible castings for each parent casting
        List<Set<Pair<RelationProperty.RolePlayer, RelationProperty.RolePlayer>>> compatibleMappingsPerParentRP = new ArrayList<>();
        if (parentAtom.getRelationPlayers().size() > this.getRelationPlayers().size()) return new HashSet<>();

        ReasonerQuery childQuery = getParentQuery();
        parentAtom.getRelationPlayers()
                .forEach(prp -> {
                    Statement parentRolePattern = prp.getRole().orElse(null);
                    if (parentRolePattern == null){
                        throw GraqlQueryException.rolePatternAbsent(parentAtom);
                    }
                    String parentRoleLabel = parentRolePattern.getType().isPresent()? parentRolePattern.getType().get() : null;
                    Role parentRole = parentRoleLabel != null? tx().getRole(parentRoleLabel) : null;
                    Variable parentRolePlayer = prp.getPlayer().var();
                    Set<Type> parentTypes = parentVarTypeMap.get(parentRolePlayer);

                    Set<RelationProperty.RolePlayer> compatibleRelationPlayers = new HashSet<>();
                    this.getRelationPlayers().stream()
                            //check for role compatibility
                            .filter(crp -> {
                                Statement childRolePattern = crp.getRole().orElse(null);
                                if (childRolePattern == null){
                                    throw GraqlQueryException.rolePatternAbsent(this);
                                }
                                String childRoleLabel = childRolePattern.getType().isPresent() ? childRolePattern.getType().get() : null;
                                Role childRole = childRoleLabel != null? tx().getRole(childRoleLabel) : null;

                                boolean varCompatibility = unifierType.equivalence() == null
                                        || parentRolePattern.var().isReturned() == childRolePattern.var().isReturned();
                                return varCompatibility && unifierType.roleCompatibility(parentRole, childRole);
                            })
                            //check for inter-type compatibility
                            .filter(crp -> {
                                Variable childVar = crp.getPlayer().var();
                                Set<Type> childTypes = childVarTypeMap.get(childVar);
                                return unifierType.typeCompatibility(parentTypes, childTypes)
                                        && parentTypes.stream().allMatch(parentType -> unifierType.typePlayability(childQuery, childVar, parentType));
                            })
                            //check for substitution compatibility
                            .filter(crp -> {
                                Set<Atomic> parentIds = parentAtom.getPredicates(prp.getPlayer().var(), IdPredicate.class).collect(toSet());
                                Set<Atomic> childIds = this.getPredicates(crp.getPlayer().var(), IdPredicate.class).collect(toSet());
                                return unifierType.idCompatibility(parentIds, childIds);
                            })
                            //check for value predicate compatibility
                            .filter(crp -> {
                                Set<Atomic> parentVP = parentAtom.getPredicates(prp.getPlayer().var(), ValuePredicate.class).collect(toSet());
                                Set<Atomic> childVP = this.getPredicates(crp.getPlayer().var(), ValuePredicate.class).collect(toSet());
                                return unifierType.valueCompatibility(parentVP, childVP);
                            })
                            //check linked resources
                            .filter(crp -> {
                                Variable parentVar = prp.getPlayer().var();
                                Variable childVar = crp.getPlayer().var();
                                return unifierType.attributeCompatibility(parentAtom.getParentQuery(), this.getParentQuery(), parentVar, childVar);
                            })
                            .forEach(compatibleRelationPlayers::add);

                    if (!compatibleRelationPlayers.isEmpty()) {
                        compatibleMappingsPerParentRP.add(
                                compatibleRelationPlayers.stream()
                                        .map(crp -> new Pair<>(crp, prp))
                                        .collect(Collectors.toSet())
                        );
                    }
                });

        return Sets.cartesianProduct(compatibleMappingsPerParentRP).stream()
                .filter(list -> !list.isEmpty())
                //check the same child rp is not mapped to multiple parent rps
                .filter(list -> {
                    List<RelationProperty.RolePlayer> listChildRps = list.stream().map(Pair::getKey).collect(Collectors.toList());
                    //NB: this preserves cardinality instead of removing all occurring instances which is what we want
                    return ReasonerUtils.listDifference(listChildRps, this.getRelationPlayers()).isEmpty();
                })
                //check all parent rps mapped
                .filter(list -> {
                    List<RelationProperty.RolePlayer> listParentRps = list.stream().map(Pair::getValue).collect(Collectors.toList());
                    return listParentRps.containsAll(parentAtom.getRelationPlayers());
                })
                .collect(toSet());
    }

    @Override
    public Unifier getUnifier(Atom pAtom, UnifierType unifierType){
        return getMultiUnifier(pAtom, unifierType).getUnifier();
    }

    @Override
    public MultiUnifier getMultiUnifier(Atom parentAtom, UnifierType unifierType) {
        Unifier baseUnifier = super.getUnifier(parentAtom, unifierType);
        if (baseUnifier == null){ return MultiUnifierImpl.nonExistent();}

        Set<Unifier> unifiers = new HashSet<>();
        if (parentAtom.isRelation()) {
            RelationAtom parent = parentAtom.toRelationAtom();
            Set<List<Pair<RelationProperty.RolePlayer, RelationProperty.RolePlayer>>> rpMappings = getRelationPlayerMappings(parent, unifierType);
            boolean containsRoleVariables = parent.getRelationPlayers().stream()
                    .map(RelationProperty.RolePlayer::getRole)
                    .flatMap(Streams::optionalToStream)
                    .anyMatch(rp -> rp.var().isReturned());

            //NB: if two atoms are equal and their rp mappings are complete we return the identity unifier
            //this is important for cases like unifying ($r1: $x, $r2: $y) with itself
            //this is only for cached queries to ensure they do not produce spurious answers
            if (containsRoleVariables
                    && unifierType != UnifierType.RULE
                    //for subsumptive unifiers we need a meaningful (with actual variables) inverse
                    && unifierType != UnifierType.SUBSUMPTIVE
                    && !rpMappings.isEmpty()
                    && rpMappings.stream().allMatch(mapping -> mapping.size() == getRelationPlayers().size())){
                boolean queriesEqual = ReasonerQueryEquivalence.Equality.equivalent(this.getParentQuery(), parent.getParentQuery());
                if (queriesEqual) return MultiUnifierImpl.trivial();
            }

            rpMappings
                    .forEach(mappingList -> {
                        Multimap<Variable, Variable> varMappings = HashMultimap.create();
                        mappingList.forEach(rpm -> {
                            //add role player mapping
                            varMappings.put(rpm.getKey().getPlayer().var(), rpm.getValue().getPlayer().var());

                            //add role var mapping if needed
                            Statement childRolePattern = rpm.getKey().getRole().orElse(null);
                            Statement parentRolePattern = rpm.getValue().getRole().orElse(null);
                            if (parentRolePattern != null && childRolePattern != null && containsRoleVariables){
                                varMappings.put(childRolePattern.var(), parentRolePattern.var());
                            }

                        });
                        unifiers.add(baseUnifier.merge(new UnifierImpl(varMappings)));
                    });
        } else {
            unifiers.add(baseUnifier);
        }

        if (!unifierType.allowsNonInjectiveMappings()
             && unifiers.stream().anyMatch(Unifier::isNonInjective) ){
            return MultiUnifierImpl.nonExistent();
        }
        return new MultiUnifierImpl(unifiers);
    }

    private HashMultimap<Variable, Role> getVarRoleMap() {
        HashMultimap<Variable, Role> map = HashMultimap.create();
        getRoleVarMap().asMap().forEach((key, value) -> value.forEach(var -> map.put(var, key)));
        return map;
    }
    @Override
    public SemanticDifference semanticDifference(Atom p, Unifier unifier) {
        SemanticDifference baseDiff = super.semanticDifference(p, unifier);
        if (!p.isRelation()) return baseDiff;
        RelationAtom parentAtom = (RelationAtom) p;
        Set<VariableDefinition> diff = new HashSet<>();

        Set<Variable> parentRoleVars= parentAtom.getRoleExpansionVariables();
        HashMultimap<Variable, Role> childVarRoleMap = this.getVarRoleMap();
        HashMultimap<Variable, Role> parentVarRoleMap = parentAtom.getVarRoleMap();
        unifier.mappings().forEach( m -> {
            Variable childVar = m.getKey();
            Variable parentVar = m.getValue();
            Set<Role> childRoles = childVarRoleMap.get(childVar);
            Set<Role> parentRoles = parentVarRoleMap.get(parentVar);
            Role role = null;
            if(parentRoleVars.contains(parentVar)){
                Set<Label> roleLabels = this.getRelationPlayers().stream()
                        .map(RelationProperty.RolePlayer::getRole)
                        .flatMap(Streams::optionalToStream)
                        .filter(roleStatement -> roleStatement.var().equals(childVar))
                        .map(Statement::getType)
                        .flatMap(Streams::optionalToStream)
                        .map(Label::of)
                        .collect(toSet());
                if (!roleLabels.isEmpty()){
                    role = tx().getRole(Iterables.getOnlyElement(roleLabels).getValue());
                }
            }
            diff.add(new VariableDefinition(childVar,null, role, bottom(Sets.difference(childRoles, parentRoles)), new HashSet<>()));
        });
        return baseDiff.merge(new SemanticDifference(diff));
    }

    private Relation findRelation(ConceptMap sub){
        ReasonerAtomicQuery query = ReasonerQueries.atomic(this).withSubstitution(sub);
        ConceptMap answer = tx().queryCache().getAnswerStream(query).findFirst().orElse(null);

        if (answer == null) tx().queryCache().ackDBCompleteness(query);
        return answer != null? answer.get(getVarName()).asRelation() : null;
    }
    
    @Override
    public Stream<ConceptMap> materialise(){
        RelationType relationType = getSchemaConcept().asRelationType();
        //in case the roles are variable, we wouldn't have enough information if converted to attribute
        if (relationType.isImplicit()){
            ConceptMap roleSub = getRoleSubstitution();
            return this.toAttributeAtom().materialise().map(ans -> ConceptUtils.mergeAnswers(ans, roleSub));
        }
        Multimap<Role, Variable> roleVarMap = getRoleVarMap();
        ConceptMap substitution = getParentQuery().getSubstitution();

        //NB: if the relation is implicit, it will be created as a reified relation
        //if the relation already exists, only assign roleplayers, otherwise create a new relation
        Relation relation;
        if (substitution.containsVar(getVarName())){
            relation = substitution.get(getVarName()).asRelation();
        } else {
            Relation foundRelation = findRelation(substitution);
            if (foundRelation == null) {
                Relation insertedRelation = RelationTypeImpl.from(relationType).addRelationInferred();
                relation = insertedRelation;
            } else {
                relation = foundRelation;
            }

        }

        //NB: this will potentially reify existing implicit relationships
        roleVarMap.asMap()
                .forEach((key, value) -> value.forEach(var -> relation.assign(key, substitution.get(var).asThing())));

        ConceptMap relationSub = ConceptUtils.mergeAnswers(
                getRoleSubstitution(),
                getVarName().isReturned()?
                        new ConceptMap(ImmutableMap.of(getVarName(), relation)) :
                        new ConceptMap()
        );

        ConceptMap answer = ConceptUtils.mergeAnswers(substitution, relationSub);
        return Stream.of(answer);
    }

    /**
     * if any Role variable of the parent is user defined rewrite ALL Role variables to user defined (otherwise unification is problematic)
     * @param parentAtom parent atom that triggers rewrite
     * @return new relation atom with user defined Role variables if necessary or this
     */
    private RelationAtom rewriteWithVariableRoles(Atom parentAtom){
        if (!parentAtom.requiresRoleExpansion()) return this;

        Statement relVar = getPattern().getProperty(IsaProperty.class)
                .map(prop -> new Statement(getVarName()).isa(prop.type()))
                .orElse(new StatementThing(getVarName()));

        for (RelationProperty.RolePlayer rp: getRelationPlayers()) {
            Statement rolePattern = rp.getRole().orElse(null);
            if (rolePattern != null) {
                Variable roleVar = rolePattern.var();
                String roleLabel = rolePattern.getType().orElse(null);
                relVar = relVar.rel(new Statement(roleVar.asReturnedVar()).type(roleLabel), rp.getPlayer());
            } else {
                relVar = relVar.rel(rp.getPlayer());
            }
        }
        return create(relVar, this.getPredicateVariable(), this.getTypeId(), this.getPossibleTypes(), this.getParentQuery());
    }

    /**
     * @param parentAtom parent atom that triggers rewrite
     * @return new relation atom with user defined name if necessary or this
     */
    private RelationAtom rewriteWithRelationVariable(Atom parentAtom){
        if (this.getVarName().isReturned() || !parentAtom.getVarName().isReturned()) return this;
        return rewriteWithRelationVariable();
    }

    @Override
    public RelationAtom rewriteWithRelationVariable(){
        StatementInstance newVar = new StatementThing(new Variable().asReturnedVar());
        Statement relVar = getPattern().getProperty(IsaProperty.class)
                .map(prop -> newVar.isa(prop.type()))
                .orElse(newVar);

        for (RelationProperty.RolePlayer c: getRelationPlayers()) {
            Statement roleType = c.getRole().orElse(null);
            if (roleType != null) {
                relVar = relVar.rel(roleType, c.getPlayer());
            } else {
                relVar = relVar.rel(c.getPlayer());
            }
        }
        return create(relVar, this.getPredicateVariable(), this.getTypeId(), this.getPossibleTypes(), this.getParentQuery());
    }

    @Override
    public RelationAtom rewriteWithTypeVariable(){
        return create(this.getPattern(), this.getPredicateVariable().asReturnedVar(), this.getTypeId(), this.getPossibleTypes(), this.getParentQuery());
    }

    @Override
    public Atom rewriteToUserDefined(Atom parentAtom){
        return this
                .rewriteWithRelationVariable(parentAtom)
                .rewriteWithVariableRoles(parentAtom)
                .rewriteWithTypeVariable(parentAtom);

    }

}
