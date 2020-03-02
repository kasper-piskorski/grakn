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

package grakn.core.graql.reasoner.atom.binary;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import grakn.common.util.Pair;
import grakn.core.common.util.Streams;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.core.Schema;
import grakn.core.graql.reasoner.ReasoningContext;
import grakn.core.graql.reasoner.atom.Atom;
import grakn.core.graql.reasoner.atom.predicate.IdPredicate;
import grakn.core.graql.reasoner.atom.predicate.Predicate;
import grakn.core.graql.reasoner.atom.task.convert.AtomConverter;
import grakn.core.graql.reasoner.atom.task.convert.RelationAtomConverter;
import grakn.core.graql.reasoner.atom.task.infer.RelationTypeReasoner;
import grakn.core.graql.reasoner.atom.task.infer.TypeReasoner;
import grakn.core.graql.reasoner.atom.task.materialise.RelationMaterialiser;
import grakn.core.graql.reasoner.atom.task.relate.RelationSemanticProcessor;
import grakn.core.graql.reasoner.atom.task.relate.SemanticProcessor;
import grakn.core.graql.reasoner.atom.task.validate.AtomValidator;
import grakn.core.graql.reasoner.atom.task.validate.RelationAtomValidator;
import grakn.core.graql.reasoner.cache.SemanticDifference;
import grakn.core.graql.reasoner.unifier.UnifierType;
import grakn.core.kb.concept.api.ConceptId;
import grakn.core.kb.concept.api.Label;
import grakn.core.kb.concept.api.Role;
import grakn.core.kb.concept.api.Rule;
import grakn.core.kb.concept.api.SchemaConcept;
import grakn.core.kb.concept.api.Type;
import grakn.core.kb.concept.manager.ConceptManager;
import grakn.core.kb.graql.reasoner.ReasonerCheckedException;
import grakn.core.kb.graql.reasoner.atom.Atomic;
import grakn.core.kb.graql.reasoner.query.ReasonerQuery;
import grakn.core.kb.graql.reasoner.unifier.MultiUnifier;
import grakn.core.kb.graql.reasoner.unifier.Unifier;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Atom implementation defining a relation atom corresponding to a combined RelationProperty
 * and (optional) IsaProperty. The relation atom is a TypeAtom with relation players.
 */
public class RelationAtom extends IsaAtomBase {

    private final ImmutableList<RelationProperty.RolePlayer> relationPlayers;
    private final ImmutableSet<Label> roleLabels;

    private final TypeReasoner<RelationAtom> typeReasoner;
    private final SemanticProcessor<RelationAtom> semanticProcessor;
    private final AtomValidator<RelationAtom> validator;
    private final AtomConverter<RelationAtom> converter = new RelationAtomConverter();

    private ImmutableList<Type> possibleTypes = null;

    // memoised computed values
    private int hashCode;
    private boolean hashCodeMemoized;
    private int alphaEquivalenceHashCode;
    private boolean alphaEquivalenceHashCodeMemoized;
    private Multimap<Role, String> roleConceptIdMap = null;
    private Multimap<Role, Type> roleTypeMap = null;
    private Multimap<Role, Variable> roleVarMap = null;

    private RelationAtom(
            Variable varName,
            Statement pattern,
            ReasonerQuery parentQuery,
            @Nullable ConceptId typeId,
            Variable predicateVariable,
            ImmutableList<RelationProperty.RolePlayer> relationPlayers,
            ImmutableSet<Label> roleLabels,
            ReasoningContext ctx) {
        super(varName, pattern, parentQuery, typeId, predicateVariable, ctx);
        this.relationPlayers = relationPlayers;
        this.roleLabels = roleLabels;

        this.typeReasoner = new RelationTypeReasoner();
        this.semanticProcessor = new RelationSemanticProcessor(ctx.conceptManager());
        this.validator = new RelationAtomValidator(ctx.conceptManager());
    }

    public static RelationAtom create(Statement pattern, Variable predicateVar, @Nullable ConceptId predicateId, ReasonerQuery parent, ReasoningContext ctx) {
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
        return new RelationAtom(pattern.var(), pattern, parent, predicateId, predicateVar, relationPlayers, roleLabels, ctx);
    }

    public static RelationAtom create(Statement pattern, Variable predicateVar, @Nullable ConceptId predicateId, @Nullable ImmutableList<Type> possibleTypes, ReasonerQuery parent, ReasoningContext ctx) {
        RelationAtom atom = create(pattern, predicateVar, predicateId, parent, ctx);
        atom.possibleTypes = possibleTypes;
        return atom;
    }

    /**
     * Copy constructor
     */
    private static RelationAtom create(RelationAtom a, ReasonerQuery parent) {
        RelationAtom atom = new RelationAtom(a.getVarName(), a.getPattern(), parent, a.getTypeId(), a.getPredicateVariable(), a.getRelationPlayers(), a.getRoleLabels(), a.context());
        atom.possibleTypes = a.possibleTypes;
        return atom;
    }

    public ImmutableList<RelationProperty.RolePlayer> getRelationPlayers() {
        return relationPlayers;
    }

    public ImmutableSet<Label> getRoleLabels() { return roleLabels; }


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

    @Override
    public int hashCode() {
        if (!hashCodeMemoized) {
            hashCode = Objects.hash(getTypeId(), getVarNames(), getRelationPlayers());
            hashCodeMemoized = true;
        }
        return hashCode;
    }

    @Override
    public Class<? extends VarProperty> getVarPropertyClass() {
        return RelationProperty.class;
    }

    @Override
    public RelationAtom toRelationAtom() {
        return converter.toRelationAtom(this, context());
    }

    @Override
    public AttributeAtom toAttributeAtom() {
        return converter.toAttributeAtom(this, context());
    }

    @Override
    public IsaAtom toIsaAtom() {
        return converter.toIsaAtom(this, context());
    }

    @Override
    public Set<Atom> rewriteToAtoms() {
        return this.getRelationPlayers().stream()
                .map(rp -> create(relationPattern(getVarName().asReturnedVar(), Sets.newHashSet(rp)), getPredicateVariable(), getTypeId(), null, getParentQuery(), context()))
                .collect(Collectors.toSet());
    }

    @Override
    public String toString() {
        String typeString;
        if (getSchemaConcept() != null) {
            typeString = getSchemaConcept().label().getValue();
        } else {
            String types = typeReasoner.inferPossibleTypes(this, new ConceptMap()).stream()
                    .map(rt -> rt.label().getValue())
                    .collect(Collectors.joining(", "));
            typeString = "{" + types + "}";
        }
        String relationString = (isUserDefined() ? getVarName() + " " : "") +
                typeString +
                (getPredicateVariable().isReturned() ? "(" + getPredicateVariable() + ")" : "") +
                (isDirect() ? "!" : "") +
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
     *
     * @return set of pairs of roleplayers arranged in terms of directionality
     */
    public Set<Pair<Variable, Variable>> varDirectionality() {
        Multimap<Role, Variable> roleVarMap = this.getRoleVarMap();
        Multimap<Variable, Role> varRoleMap = HashMultimap.create();
        roleVarMap.entries().forEach(e -> varRoleMap.put(e.getValue(), e.getKey()));

        List<Role> roleOrdering = roleVarMap.keySet().stream()
                .sorted(Comparator.comparing(r -> r.label().hashCode()))
                .distinct()
                .collect(Collectors.toList());

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
    public Set<Variable> getRolePlayers() {
        return getRelationPlayers().stream().map(c -> c.getPlayer().var()).collect(Collectors.toSet());
    }

    /**
     * @return set of user defined role variables if any
     */
    private Set<Variable> getRoleVariables() {
        return getRelationPlayers().stream()
                .map(RelationProperty.RolePlayer::getRole)
                .flatMap(Streams::optionalToStream)
                .map(Statement::var)
                .filter(Variable::isReturned)
                .collect(Collectors.toSet());
    }

    @Override
    public Atomic copy(ReasonerQuery parent) {
        return create(this, parent);
    }

    @Override
    protected Pattern createCombinedPattern() {
        if (getPredicateVariable().isReturned()) return super.createCombinedPattern();
        return getSchemaConcept() == null ?
                relationPattern() :
                isDirect() ?
                        relationPattern().isaX(getSchemaConcept().label().getValue()) :
                        relationPattern().isa(getSchemaConcept().label().getValue());
    }

    private Statement relationPattern() {
        return relationPattern(getVarName(), getRelationPlayers());
    }

    /**
     * construct a $varName (rolemap) isa $typeVariable relation
     *
     * @param varName         variable name
     * @param relationPlayers collection of rolePlayer-roleType mappings
     * @return corresponding Statement
     */
    public static Statement relationPattern(Variable varName, Collection<RelationProperty.RolePlayer> relationPlayers) {
        Statement var = new Statement(varName);
        for (RelationProperty.RolePlayer rp : relationPlayers) {
            Statement rolePattern = rp.getRole().orElse(null);
            var = rolePattern != null ? var.rel(rolePattern, rp.getPlayer()) : var.rel(rp.getPlayer());
        }
        return var;
    }

    @Override
    boolean isBaseEquivalent(Object obj) {
        if (!super.isBaseEquivalent(obj)) return false;
        RelationAtom that = (RelationAtom) obj;
        //check relation players equivalent
        return this.getRolePlayers().size() == that.getRolePlayers().size()
                && this.getRelationPlayers().size() == that.getRelationPlayers().size()
                && this.getRoleLabels().equals(that.getRoleLabels());
    }

    private int baseHashCode() {
        int baseHashCode = 1;
        baseHashCode = baseHashCode * 37 + (this.getTypeId() != null ? this.getTypeId().hashCode() : 0);
        baseHashCode = baseHashCode * 37 + this.getRoleLabels().hashCode();
        return baseHashCode;
    }

    @Override
    public int alphaEquivalenceHashCode() {
        if (!alphaEquivalenceHashCodeMemoized) {
            alphaEquivalenceHashCode = computeAlphaEquivalenceHashCode();
            alphaEquivalenceHashCodeMemoized = true;
        }
        return alphaEquivalenceHashCode;
    }

    private int computeAlphaEquivalenceHashCode() {
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
        equivalenceHashCode = equivalenceHashCode * 37 + this.computeRoleTypeMap(false).hashCode();
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
    public void checkValid() { validator.checkValid(this); }

    @Override
    public Set<String> validateAsRuleHead(Rule rule) {
        return validator.validateAsRuleHead(this, rule);
    }

    @Override
    public Set<String> validateAsRuleBody(Label ruleLabel) {
        return validator.validateAsRuleBody(this, ruleLabel);
    }

    public boolean typesRoleCompatibleWithMatchSemantics(Variable typedVar, Set<Type> parentTypes){
        return parentTypes.stream().allMatch(parentType -> isTypeRoleCompatible(typedVar, parentType, true));
    }

    public boolean typesRoleCompatibleWithInsertSemantics(Variable typedVar, Set<Type> parentTypes){
        return parentTypes.stream().allMatch(parentType -> isTypeRoleCompatible(typedVar, parentType, false));
    }

    private boolean isTypeRoleCompatible(Variable typedVar, Type parentType, boolean includeRoleHierarchy){
        if (parentType == null || Schema.MetaSchema.isMetaLabel(parentType.label())) return true;

        List<Role> roleRequirements = getRoleVarMap().entries().stream()
                //get roles this type needs to play
                .filter(e -> e.getValue().equals(typedVar))
                .map(Map.Entry::getKey)
                .filter(role -> !Schema.MetaSchema.isMetaLabel(role.label()))
                .collect(Collectors.toList());

        if (roleRequirements.isEmpty()) return true;

        Set<Type> parentTypes = parentType.subs().collect(Collectors.toSet());
        return roleRequirements.stream()
                //include sub roles
                .flatMap(role -> includeRoleHierarchy? role.subs() : Stream.of(role))
                //check if it can play it
                .flatMap(Role::players)
                .anyMatch(parentTypes::contains);
    }

    public Stream<IdPredicate> getRolePredicates(ConceptManager conceptManager) {
        return getRelationPlayers().stream()
                .map(RelationProperty.RolePlayer::getRole)
                .flatMap(Streams::optionalToStream)
                .filter(var -> var.var().isReturned())
                .filter(vp -> vp.getType().isPresent())
                .map(vp -> {
                    String label = vp.getType().orElse(null);
                    return IdPredicate.create(vp.var(), conceptManager.getRole(label).id(), getParentQuery());
                });
    }

    private <T extends Predicate> Multimap<Role, T> getRolePredicateMap(Class<T> type) {
        HashMultimap<Role, T> rolePredicateMap = HashMultimap.create();

        HashMultimap<Variable, T> predicateMap = HashMultimap.create();
        getPredicates(type).forEach(p -> p.getVarNames().forEach(v -> predicateMap.put(v, p)));
        Multimap<Role, Variable> roleMap = getRoleVarMap();

        roleMap.entries().stream()
                .filter(e -> predicateMap.containsKey(e.getValue()))
                .forEach(e -> rolePredicateMap.putAll(e.getKey(), predicateMap.get(e.getValue())));
        return rolePredicateMap;
    }

    /**
     * @return map of pairs role type - Id predicate describing the role player playing this role (substitution)
     */
    private Multimap<Role, String> getRoleConceptIdMap() {
        if (roleConceptIdMap == null) {
            roleConceptIdMap = computeRoleConceptIdMap();
        }
        return roleConceptIdMap;
    }

    private Multimap<Role, String> computeRoleConceptIdMap() {
        ImmutableMultimap.Builder<Role, String> builder = ImmutableMultimap.builder();
        getRolePredicateMap(IdPredicate.class)
                .entries()
                .forEach(e -> builder.put(e.getKey(), e.getValue().getPredicateValue()));
        return builder.build();
    }

    private Multimap<Role, Type> getRoleTypeMap() {
        if (roleTypeMap == null) {
            roleTypeMap = computeRoleTypeMap(false);
        }
        return roleTypeMap;
    }

    private Multimap<Role, Type> computeRoleTypeMap(boolean inferTypes) {
        ImmutableMultimap.Builder<Role, Type> builder = ImmutableMultimap.builder();
        Multimap<Role, Variable> roleMap = getRoleVarMap();
        SetMultimap<Variable, Type> varTypeMap = getParentQuery().getVarTypeMap(inferTypes);

        roleMap.entries().stream()
                .sorted(Comparator.comparing(e -> e.getKey().label()))
                .flatMap(e -> varTypeMap.get(e.getValue()).stream().map(type -> new Pair<>(e.getKey(), type)))
                .sorted(Comparator.comparing(Pair::hashCode))
                .forEach(p -> builder.put(p.first(), p.second()));
        return builder.build();
    }

    @Override
    public boolean isRuleApplicableViaAtom(Atom ruleAtom) {
        if (!(ruleAtom instanceof RelationAtom)) return isRuleApplicableViaAtom(ruleAtom.toRelationAtom());
        RelationAtom atomWithType = typeReasoner.inferTypes(this.addType(ruleAtom.getSchemaConcept()), new ConceptMap());
        return ruleAtom.isUnifiableWith(atomWithType);
    }

    @Override
    public RelationAtom addType(SchemaConcept type) {
        if (getTypeId() != null) return this;
        //NB: do not cache possible types
        return create(this.getPattern(), this.getPredicateVariable(), type.id(), this.getParentQuery(), this.context());
    }

    @Override
    public ImmutableList<Type> getPossibleTypes(ReasoningContext ctx) {
        return typeReasoner.inferPossibleTypes(this, new ConceptMap(), ctx);
    }

    @Override
    public RelationAtom inferTypes(ConceptMap sub, ReasoningContext ctx) {
        return typeReasoner.inferTypes(this, sub, ctx);
    }

    @Override
    public List<Atom> atomOptions(ConceptMap sub, ReasoningContext ctx) {
        return typeReasoner.atomOptions(this, sub, ctx);
    }

    @Override
    public Stream<ConceptMap> materialise() {
        return new RelationMaterialiser(context()).materialise(this);
    }


    @Override
    public Set<Variable> getRoleExpansionVariables() {
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
        ConceptManager conceptManager = context().conceptManager();
        return Stream.concat(
                super.getInnerPredicates(),
                getRelationPlayers().stream()
                        .map(RelationProperty.RolePlayer::getRole)
                        .flatMap(Streams::optionalToStream)
                        .filter(vp -> vp.var().isReturned())
                        .map(vp -> new Pair<>(vp.var(), vp.getType().orElse(null)))
                        .filter(p -> Objects.nonNull(p.second()))
                        .map(p -> new Pair<Variable, SchemaConcept>(p.first(), conceptManager.getSchemaConcept(Label.of(p.second()))))
                        .filter(p -> Objects.nonNull(p.second()))
                        .map(p -> IdPredicate.create(p.first(), p.second().id(), getParentQuery()))
        );
    }

    /**
     * @return map containing roleType - (rolePlayer var - rolePlayer type) pairs
     */
    public Multimap<Role, Variable> getRoleVarMap() {
        if (roleVarMap == null) {
            roleVarMap = computeRoleVarMap();
        }
        return roleVarMap;
    }

    private Multimap<Role, Variable> computeRoleVarMap() {
        ImmutableMultimap.Builder<Role, Variable> builder = ImmutableMultimap.builder();
        ConceptManager conceptManager = context().conceptManager();

        getRelationPlayers().forEach(c -> {
            Variable varName = c.getPlayer().var();
            Statement rolePattern = c.getRole().orElse(null);
            if (rolePattern != null) {
                //try directly
                String typeLabel = rolePattern.getType().orElse(null);
                Role role = typeLabel != null ? conceptManager.getRole(typeLabel) : null;
                //try indirectly
                if (role == null && rolePattern.var().isReturned()) {
                    IdPredicate rolePredicate = getIdPredicate(rolePattern.var());
                    if (rolePredicate != null) {
                        Role r = conceptManager.getConcept(rolePredicate.getPredicate()).asRole();
                        if (r == null) throw ReasonerCheckedException.idNotFound(rolePredicate.getPredicate());
                        role = r;
                    }
                }
                if (role != null) builder.put(role, varName);
            }
        });
        return builder.build();
    }

    @Override
    public Unifier getUnifier(Atom pAtom, UnifierType unifierType) {
        return semanticProcessor.getUnifier(this, pAtom, unifierType);
    }

    @Override
    public MultiUnifier getMultiUnifier(Atom parentAtom, UnifierType unifierType) {
        return semanticProcessor.getMultiUnifier(this, parentAtom, unifierType);
    }

    @Override
    public SemanticDifference computeSemanticDifference(Atom child, Unifier unifier) {
        return semanticProcessor.computeSemanticDifference(this, child, unifier);
    }

    public HashMultimap<Variable, Role> getVarRoleMap() {
        HashMultimap<Variable, Role> map = HashMultimap.create();
        getRoleVarMap().asMap().forEach((key, value) -> value.forEach(var -> map.put(var, key)));
        return map;
    }

    /**
     * if any Role variable of the parent is user defined rewrite ALL Role variables to user defined (otherwise unification is problematic)
     *
     * @param parentAtom parent atom that triggers rewrite
     * @return new relation atom with user defined Role variables if necessary or this
     */
    private RelationAtom rewriteWithVariableRoles(Atom parentAtom) {
        if (!parentAtom.requiresRoleExpansion()) return this;

        Statement relVar = getPattern().getProperty(IsaProperty.class)
                .map(prop -> new Statement(getVarName()).isa(prop.type()))
                .orElse(new StatementThing(getVarName()));

        for (RelationProperty.RolePlayer rp : getRelationPlayers()) {
            Statement rolePattern = rp.getRole().orElse(null);
            if (rolePattern != null) {
                Variable roleVar = rolePattern.var();
                String roleLabel = rolePattern.getType().orElse(null);
                relVar = relVar.rel(new Statement(roleVar.asReturnedVar()).type(roleLabel), rp.getPlayer());
            } else {
                relVar = relVar.rel(rp.getPlayer());
            }
        }
        return create(relVar, this.getPredicateVariable(), this.getTypeId(), this.getPossibleTypes(), this.getParentQuery(), this.context());
    }

    /**
     * @param parentAtom parent atom that triggers rewrite
     * @return new relation atom with user defined name if necessary or this
     */
    private RelationAtom rewriteWithRelationVariable(Atom parentAtom) {
        if (!parentAtom.getVarName().isReturned()) return this;
        return rewriteWithRelationVariable();
    }

    @Override
    public RelationAtom rewriteWithRelationVariable() {
        if (this.getVarName().isReturned()) return this;
        StatementInstance newVar = new StatementThing(new Variable().asReturnedVar());
        Statement relVar = getPattern().getProperty(IsaProperty.class)
                .map(prop -> newVar.isa(prop.type()))
                .orElse(newVar);

        for (RelationProperty.RolePlayer c : getRelationPlayers()) {
            Statement roleType = c.getRole().orElse(null);
            if (roleType != null) {
                relVar = relVar.rel(roleType, c.getPlayer());
            } else {
                relVar = relVar.rel(c.getPlayer());
            }
        }
        return create(relVar, this.getPredicateVariable(), this.getTypeId(), this.getPossibleTypes(), this.getParentQuery(), this.context());
    }

    @Override
    public RelationAtom rewriteWithTypeVariable() {
        return create(this.getPattern(), this.getPredicateVariable().asReturnedVar(), this.getTypeId(), this.getPossibleTypes(), this.getParentQuery(), this.context());
    }

    @Override
    public Atom rewriteToUserDefined(Atom parentAtom) {
        return this
                .rewriteWithRelationVariable(parentAtom)
                .rewriteWithVariableRoles(parentAtom)
                .rewriteWithTypeVariable(parentAtom);
    }
}
