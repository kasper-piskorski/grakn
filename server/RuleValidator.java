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

package grakn.core.server;

import com.google.common.collect.Iterables;
import grakn.core.common.exception.ErrorMessage;
import grakn.core.concept.impl.RuleImpl;
import grakn.core.core.Schema;
import grakn.core.graql.reasoner.query.CompositeQuery;
import grakn.core.graql.reasoner.query.ReasonerQueryFactory;
import grakn.core.kb.concept.api.GraknConceptException;
import grakn.core.kb.concept.api.Label;
import grakn.core.kb.concept.api.Rule;
import grakn.core.kb.concept.api.SchemaConcept;
import grakn.core.kb.concept.manager.ConceptManager;
import grakn.core.kb.graql.reasoner.atom.Atomic;
import grakn.core.kb.graql.reasoner.query.ReasonerQuery;
import graql.lang.Graql;
import graql.lang.pattern.Conjunction;
import graql.lang.pattern.Pattern;
import graql.lang.statement.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class RuleValidator {

    /**
     * The precedence of validation is: labelValidation -> ontologicalValidation -> clauseValidation
     * Each of the validation happens only if the preceding validation yields no errors
     * @param rule to be validated
     * @return Error messages if rule violates any validation constraints
     */
    static Set<String> validateRule(ConceptManager conceptManager, ReasonerQueryFactory reasonerQueryFactory, Rule rule) {
        Set<String> errors = validateRuleSchemaConceptExist(conceptManager, rule);
        if (errors.isEmpty()) {
            Set<String> ontologicalErrors = validateRuleOntologically(reasonerQueryFactory, rule);
            errors.addAll(ontologicalErrors);
            if (ontologicalErrors.isEmpty()) {
                errors.addAll(validateRuleIsValidClause(reasonerQueryFactory, rule));
            }
        }
        return errors;
    }

    /**
     * @param rule The rule to be validated
     * @return Error messages if the when or then of a rule refers to a non existent type
     */
    private static Set<String> validateRuleSchemaConceptExist(ConceptManager conceptManager, Rule rule) {
        Set<String> errors = new HashSet<>();
        errors.addAll(checkRuleSideInvalid(conceptManager, rule, Schema.VertexProperty.RULE_WHEN, rule.when()));
        errors.addAll(checkRuleSideInvalid(conceptManager, rule, Schema.VertexProperty.RULE_THEN, rule.then()));
        return errors;
    }

    /**
     * @param rule  the rule to be validated ontologically
     * @return Error messages if the rule has ontological inconsistencies
     */
    private static Set<String> validateRuleOntologically(ReasonerQueryFactory reasonerQueryFactory, Rule rule) {
        Set<String> errors = new HashSet<>();

        //both body and head refer to the same graph and have to be valid with respect to the schema that governs it
        //as a result the rule can be ontologically validated by combining them into a conjunction
        //this additionally allows to cross check body-head references
        ReasonerQuery combinedQuery = combinedRuleQuery(reasonerQueryFactory, rule);
        errors.addAll(combinedQuery.validateOntologically(rule.label()));
        return errors;
    }

    /**
     * NB: this only gets checked if the rule obeys the Horn clause form
     *
     * @param rule the rule to be validated
     * @return Error messages if the rule is not a valid clause (in implication form, conjunction in the body, single-atom conjunction in the head)
     */
    private static Set<String> validateRuleIsValidClause(ReasonerQueryFactory reasonerQueryFactory, Rule rule) {
        Set<String> errors = new HashSet<>();
        Set<Conjunction<Pattern>> patterns = rule.when().getNegationDNF().getPatterns();
        if (patterns.size() > 1) {
            errors.add(ErrorMessage.VALIDATION_RULE_DISJUNCTION_IN_BODY.getMessage(rule.label()));
        } else {
            errors.addAll(CompositeQuery.validateAsRuleBody(Iterables.getOnlyElement(patterns), rule, reasonerQueryFactory));
        }

        if (errors.isEmpty()) {
            errors.addAll(validateRuleHead(reasonerQueryFactory, rule));
        }
        return errors;
    }

    /**
     * @param rule  the rule to be cast into a combined conjunction query
     * @return a combined conjunction created from statements from both the body and the head of the rule
     */
    private static ReasonerQuery combinedRuleQuery(ReasonerQueryFactory reasonerQueryFactory, Rule rule) {
        ReasonerQuery bodyQuery = reasonerQueryFactory.create(Graql.and(rule.when().getDisjunctiveNormalForm().getPatterns().stream().flatMap(conj -> conj.getPatterns().stream()).collect(Collectors.toSet())));
        ReasonerQuery headQuery = reasonerQueryFactory.create(Graql.and(rule.then().getDisjunctiveNormalForm().getPatterns().stream().flatMap(conj -> conj.getPatterns().stream()).collect(Collectors.toSet())));
        return headQuery.conjunction(bodyQuery);
    }

    /**
     * @param rule  the rule to be validated
     * @return Error messages if the rule head is invalid - is not a single-atom conjunction, doesn't contain illegal atomics and is ontologically valid
     */
    private static Set<String> validateRuleHead(ReasonerQueryFactory reasonerQueryFactory, Rule rule) {
        Set<String> errors = new HashSet<>();
        Set<Conjunction<Statement>> headPatterns = rule.then().getDisjunctiveNormalForm().getPatterns();
        Set<Conjunction<Statement>> bodyPatterns = rule.when().getDisjunctiveNormalForm().getPatterns();

        if (headPatterns.size() != 1) {
            errors.add(ErrorMessage.VALIDATION_RULE_DISJUNCTION_IN_HEAD.getMessage(rule.label()));
        } else {
            ReasonerQuery bodyQuery = reasonerQueryFactory.create(Iterables.getOnlyElement(bodyPatterns));
            ReasonerQuery headQuery = reasonerQueryFactory.create(Iterables.getOnlyElement(headPatterns));
            ReasonerQuery combinedQuery = headQuery.conjunction(bodyQuery);

            Set<Atomic> headAtoms = headQuery.getAtoms();
            combinedQuery.getAtoms().stream()
                    .filter(headAtoms::contains)
                    .map(at -> at.validateAsRuleHead(rule))
                    .forEach(errors::addAll);
            Set<Atomic> selectableHeadAtoms = headAtoms.stream()
                    .filter(Atomic::isAtom)
                    .filter(Atomic::isSelectable)
                    .collect(Collectors.toSet());

            if (selectableHeadAtoms.size() > 1) {
                errors.add(ErrorMessage.VALIDATION_RULE_HEAD_NON_ATOMIC.getMessage(rule.label()));
            }
        }
        return errors;
    }

    /**
     * @param rule    The rule the pattern was extracted from
     * @param side    The side from which the pattern was extracted
     * @param pattern The pattern from which we will extract the types in the pattern
     * @return A list of errors if the pattern refers to any non-existent types in the graph
     */
    private static Set<String> checkRuleSideInvalid(ConceptManager conceptManager, Rule rule, Schema.VertexProperty side, Pattern pattern) {
        Set<String> errors = new HashSet<>();

        pattern.getNegationDNF().getPatterns().stream()
                .flatMap(conj -> conj.getPatterns().stream())
                .forEach(p -> p.statements().stream()
                        .flatMap(statement -> statement.innerStatements().stream())
                        .flatMap(statement -> statement.getTypes().stream())
                        .forEach(type -> {
                            SchemaConcept schemaConcept = conceptManager.getSchemaConcept(Label.of(type));
                            if(schemaConcept == null){
                                errors.add(ErrorMessage.VALIDATION_RULE_MISSING_ELEMENTS.getMessage(side, rule.label(), type));
                            } else {
                                if(Schema.VertexProperty.RULE_WHEN.equals(side)){
                                    if (schemaConcept.isType()){
                                        if (p.isNegation()){
                                            RuleImpl.from(rule).addNegativeHypothesis(schemaConcept.asType());
                                        } else {
                                            RuleImpl.from(rule).addPositiveHypothesis(schemaConcept.asType());
                                        }
                                    }
                                } else if (Schema.VertexProperty.RULE_THEN.equals(side)){
                                    if (schemaConcept.isType()) {
                                        RuleImpl.from(rule).addConclusion(schemaConcept.asType());
                                    }
                                } else {
                                    throw GraknConceptException.invalidPropertyUse(rule, side.toString());
                                }
                            }
                        }));
        return errors;
    }
}
