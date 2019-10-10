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
import com.google.common.collect.HashMultimap;
import grakn.core.concept.type.Rule;
import grakn.core.concept.type.SchemaConcept;
import grakn.core.concept.type.Type;
import grakn.core.graql.reasoner.rule.InferenceRule;
import grakn.core.server.kb.Schema;
import grakn.core.server.session.TransactionOLTP;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Caches rules applicable to schema concepts and their conversion to InferenceRule object (parsing is expensive when large number of rules present).
 * NB: non-committed rules are also cached.
 */
public class RuleCache {

    private final HashMultimap<Type, Rule> ruleMap = HashMultimap.create();
    private final Map<Rule, InferenceRule> ruleConversionMap = new HashMap<>();
    private TransactionOLTP tx;

    //TODO: these should be eventually stored together with statistics
    private Set<Type> absentTypes = new HashSet<>();
    private Set<Type> checkedTypes = new HashSet<>();
    private Set<Rule> unmatchableRules = new HashSet<>();
    private Set<Rule> checkedRules = new HashSet<>();

    public RuleCache() {
    }

    public void setTx(TransactionOLTP tx) {
        this.tx = tx;
    }

    /**
     * @return set of inference rules contained in the graph
     */
    public Stream<Rule> getRules() {
        Rule metaRule = tx.getMetaRule();
        return metaRule.subs().filter(sub -> !sub.equals(metaRule));
    }

    /**
     * @param type rule head's type
     * @param rule to be appended
     */
    public void updateRules(Type type, Rule rule) {
        Set<Rule> match = ruleMap.get(type);
        if (match.isEmpty()) {
            getTypes(type, false)
                    .flatMap(SchemaConcept::thenRules)
                    .forEach(r -> ruleMap.put(type, r));
        }
        ruleMap.put(type, rule);
    }

    /**
     *
     * @param type of interest
     * @param direct true if type hierarchy shouldn't be included
     * @return relevant part (direct only or subs) of the type hierarchy of a type
     */
    private Stream<? extends Type> getTypes(Type type, boolean direct) {
        Stream<? extends Type> baseStream = direct ? Stream.of(type) : type.subs();
        if (type.isImplicit()) {
            return baseStream
                    .flatMap(t -> Stream.of(t, tx.getType(Schema.ImplicitType.explicitLabel(t.label()))))
                    .filter(Objects::nonNull);
        }
        return baseStream;
    }

    /**
     * @param type for which rules containing it in the head are sought
     * @return rules containing specified type in the head
     */
    @VisibleForTesting
    public Stream<Rule> getRulesWithType(Type type) {
        return getRulesWithType(type, false);
    }

    /**
     * @param types to check
     * @return true if any of the provided types is absent - doesn't have instances
     */
    public boolean absentTypes(Set<Type> types) {
        return types.stream().anyMatch(t -> !typeHasInstances(t));
    }

    /**
     * acknowledge addition of an instance of a specific type
     * @param type to be acked
     */
    public void ackTypeInstance(Type type){
        checkedTypes.add(type);
        absentTypes.remove(type);
    }

    /**
     * @param type   for which rules containing it in the head are sought
     * @param direct way of assessing isa edges
     * @return rules containing specified type in the head
     */
    public Stream<Rule> getRulesWithType(Type type, boolean direct) {
        if (type == null) return getRules();

        Set<Rule> match = ruleMap.get(type);
        if (!match.isEmpty()) return match.stream();

        getTypes(type, direct)
                .flatMap(SchemaConcept::thenRules)
                .filter(this::isRuleMatchable)
                .forEach(rule -> ruleMap.put(type, rule));

        return match.stream();
    }

    private boolean typeHasInstances(Type type){
        if (checkedTypes.contains(type)) return !absentTypes.contains(type);
        checkedTypes.add(type);
        boolean instancePresent = type.instances().findFirst().isPresent()
                || type.subs().flatMap(SchemaConcept::thenRules).anyMatch(this::isRuleMatchable);
        if (!instancePresent){
            absentTypes.add(type);
            type.whenRules().forEach(r -> unmatchableRules.add(r));
        }
        return instancePresent;
    }

    /**
     *
     * @param rule to be checked for matchability
     * @return true if rule is matchable (can provide answers)
     */
    private boolean isRuleMatchable(Rule rule){
        if (unmatchableRules.contains(rule)) return false;
        if (checkedRules.contains(rule)) return true;
        checkedRules.add(rule);
        return rule.whenPositiveTypes()
                .allMatch(this::typeHasInstances);
    }

    /**
     * @param rule      for which the parsed rule should be retrieved
     * @return parsed rule object
     */
    public InferenceRule getRule(Rule rule) {
        InferenceRule match = ruleConversionMap.get(rule);
        if (match != null) return match;

        InferenceRule newMatch = new InferenceRule(rule, tx);
        ruleConversionMap.put(rule, newMatch);
        return newMatch;
    }

    /**
     * cleans cache contents
     */
    public void clear() {
        ruleMap.clear();
        ruleConversionMap.clear();
        absentTypes.clear();
        checkedTypes.clear();
        checkedRules.clear();
        unmatchableRules.clear();
    }
}
