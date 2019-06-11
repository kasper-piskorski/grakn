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

package grakn.core.graql.reasoner.rule;

import com.google.common.base.Equivalence;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import grakn.core.concept.type.Rule;
import grakn.core.concept.type.Type;
import grakn.core.graql.reasoner.atom.Atom;
import grakn.core.graql.reasoner.atom.AtomicEquivalence;
import grakn.core.graql.reasoner.query.ReasonerQueryImpl;
import grakn.core.graql.reasoner.utils.Pair;
import grakn.core.graql.reasoner.utils.TarjanSCC;
import grakn.core.server.kb.Schema;
import grakn.core.server.session.TransactionOLTP;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

/**
 *
 * <p>
 * Convenience class providing methods for operating with the rule graph.
 * </p>
 *
 *
 */
public class RuleUtils {

    /**
     * @param rules defining the subgraph by their when and then parts
     * @return type subgraph created by the input rules
     */
    private static HashMultimap<Type, Type> persistedTypeSubGraph(Set<InferenceRule> rules){
        HashMultimap<Type, Type> graph = HashMultimap.create();
        rules.stream()
                .flatMap(rule -> persistedRuleToTypePair.apply(rule))
                .forEach(p -> graph.put(p.getKey(), p.getValue()));
        return graph;
    }

    /**
     * @return a type graph (when->then) from possibly uncommited/invalid rules (no mapping to InferenceRule may exist).
     */
    private static HashMultimap<Type, Type> typeGraph(TransactionOLTP tx){
        HashMultimap<Type, Type> graph = HashMultimap.create();
        tx.getMetaRule().subs()
                .filter(rule -> !Schema.MetaSchema.isMetaLabel(rule.label()))
                .flatMap(rule -> ruleToTypePair.apply(rule))
                .forEach(p -> graph.put(p.getKey(), p.getValue()));
        return graph;
    }

    private static Function<InferenceRule, Stream<Pair<Type, Type>>> persistedRuleToTypePair = rule ->
            rule.getBody()
                    .getAtoms(Atom.class)
                    .flatMap(at -> at.getPossibleTypes().stream())
                    .flatMap(type -> Stream.concat(
                            Stream.of(type),
                            type.subs().filter(t -> !Schema.MetaSchema.isMetaLabel(t.label())))
                    )
                    .filter(t -> !t.isAbstract())
                    .flatMap(whenType ->
                            rule.getHead()
                                    .getAtom()
                                    .getPossibleTypes().stream()
                                    .flatMap(Type::sups)
                                    .filter(t -> !t.isAbstract())
                                    .map(thenType -> new Pair<>(whenType, thenType))
                    );

    private static Function<Rule, Stream<Pair<Type, Type>>> ruleToTypePair = rule ->
            rule.whenTypes()
                    .flatMap(Type::subs)
                    .filter(t -> !t.isAbstract())
                    .flatMap(whenType ->
                            rule.thenTypes()
                                    .flatMap(Type::sups)
                                    .filter(t -> !t.isAbstract())
                                    .map(thenType -> new Pair<>(whenType, thenType))
                    );

    /**
     * @param rules to be stratified (ordered)
     * @return stream of rules ordered in terms of priority (high priority first)
     */
    public static Stream<InferenceRule> stratifyRules(Set<InferenceRule> rules){
        if(rules.stream().allMatch(r -> r.getBody().isPositive())){
            return rules.stream()
                    .sorted(Comparator.comparing(r -> -r.resolutionPriority()));
        }
        Multimap<Type, InferenceRule> typeMap = HashMultimap.create();
        rules.forEach(r -> r.getRule().thenTypes().flatMap(Type::sups).forEach(t -> typeMap.put(t, r)));
        HashMultimap<Type, Type> typeGraph = persistedTypeSubGraph(rules);
        List<Set<Type>> scc = new TarjanSCC<>(typeGraph).getSCC();
        return Lists.reverse(scc).stream()
                .flatMap(strata -> strata.stream()
                        .flatMap(t -> typeMap.get(t).stream())
                        .sorted(Comparator.comparing(r -> -r.resolutionPriority()))
                );
    }

    /**
     * @param rules set of rules of interest forming a rule subgraph
     * @return true if the rule subgraph formed from provided rules contains loops
     */
    public static boolean subGraphIsCyclical(Set<InferenceRule> rules){
        return !new TarjanSCC<>(persistedTypeSubGraph(rules))
                .getCycles().isEmpty();
    }

    /**
     *
     * @return true if the rule subgraph is stratifiable (doesn't contain cycles with negation)
     */
    public static List<Set<Type>> negativeCycles(TransactionOLTP tx){
        HashMultimap<Type, Type> typeGraph = typeGraph(tx);
        return new TarjanSCC<>(typeGraph).getCycles().stream()
                .filter(cycle ->
                        cycle.stream().anyMatch(type ->
                                type.whenRules()
                                        .filter(rule -> rule.whenNegativeTypes().anyMatch(ntype -> ntype.equals(type)))
                                        .anyMatch(rule -> !Sets.intersection(cycle, rule.thenTypes().collect(toSet())).isEmpty())
                        )
                ).collect(toList());
    }

    /**
     * @param rules set of rules of interest forming a rule subgraph
     * @return true if the rule subgraph formed from provided rules contains any rule with head satisfying the body pattern
     */
    public static boolean subGraphHasRulesWithHeadSatisfyingBody(Set<InferenceRule> rules){
        return rules.stream()
                .anyMatch(InferenceRule::headSatisfiesBody);
    }

    /**
     * @param query top query
     * @return all rules that are reachable from the entry types
     */
    public static Set<InferenceRule> getDependentRules(ReasonerQueryImpl query){
        final AtomicEquivalence equivalence = AtomicEquivalence.AlphaEquivalence;

        Set<InferenceRule> rules = new HashSet<>();
        Set<Equivalence.Wrapper<Atom>> visitedAtoms = new HashSet<>();
        Stack<Equivalence.Wrapper<Atom>> atoms = new Stack<>();
        query.selectAtoms().map(equivalence::wrap).forEach(atoms::push);
        while(!atoms.isEmpty()) {
            Equivalence.Wrapper<Atom> wrappedAtom = atoms.pop();
             Atom atom = wrappedAtom.get();
            if (!visitedAtoms.contains(wrappedAtom) && atom != null){
                atom.getApplicableRules()
                        .peek(rules::add)
                        .flatMap(rule -> rule.getBody().selectAtoms())
                        .map(equivalence::wrap)
                        .filter(at -> !visitedAtoms.contains(at))
                        .filter(at -> !atoms.contains(at))
                        .forEach(atoms::add);
                visitedAtoms.add(wrappedAtom);
            }
        }
        return rules;
    }

}
