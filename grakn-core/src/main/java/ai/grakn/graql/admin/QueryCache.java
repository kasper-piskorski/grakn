package ai.grakn.graql.admin;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Stream;

public interface QueryCache<Q extends ReasonerQuery, T extends Iterable<Answer>> {
    void add(QueryCache<Q, T> c2);

    boolean contains(Q query);

    Set<Q> queries();

    Collection<CacheEntry<Q, T>> entries();

    void remove(QueryCache<Q, T> c2);

    void clear();

    /**
     * record answer iterable for a specific query and retrieve the updated answers
     * @param query to be recorded
     * @param answers to this query
     * @return updated answer iterable
     */
    T record(Q query, T answers);

    /**
     * record answer stream for a specific query and retrieve the updated stream
     * @param query to be recorded
     * @param answers answer stream of the query
     * @return updated answer stream
     */
    Stream<Answer> record(Q query, Stream<Answer> answers);

    /**
     * retrieve (possibly) cached answers for provided query
     * @param query for which to retrieve answers
     * @return unified cached answers
     */
    T getAnswers(Q query);

    Stream<Answer> getAnswerStream(Q query);

    /**
     * cache subtraction of specified queries
     * @param c2 subtraction right operand
     * @param queries to which answers shall be subtracted
     */
    void remove(QueryCache<Q, T> c2, Set<Q> queries);
}
