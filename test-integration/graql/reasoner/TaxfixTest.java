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

package grakn.core.graql.reasoner;

import grakn.core.api.Transaction;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.rule.GraknTestServer;
import grakn.core.server.keyspace.KeyspaceImpl;
import grakn.core.server.session.SessionImpl;
import graql.lang.Graql;
import graql.lang.query.GraqlGet;
import graql.lang.query.GraqlQuery;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.ClassRule;
import org.junit.Test;

public class TaxfixTest {

    @ClassRule
    public static final GraknTestServer server = new GraknTestServer();

    private static String resourcePath = "test-integration/graql/reasoner/stubs/";


    private static KeyspaceImpl keyspace = KeyspaceImpl.of("taxfix_reimport");
    //private static KeyspaceImpl keyspace = KeyspaceImpl.of("s_acadc9f5c73741329a75e0c40b5aa9c4"));

    @Test
    public void runQueries(){
        SessionImpl session = server.sessionFactory().session(keyspace);
        String gqlFile = "taxfix-queries.gql";

        try{
            System.out.println("Loading... " + resourcePath + gqlFile);
            InputStream inputStream = ReasoningIT.class.getClassLoader().getResourceAsStream(resourcePath + gqlFile);
            String s = new BufferedReader(new InputStreamReader(inputStream)).lines().collect(Collectors.joining("\n"));
            List<GraqlQuery> queries = Graql.parseList(s).collect(Collectors.toList());

            int[] count = {0};
            queries.stream().limit(25).forEach(query -> {
                try (Transaction tx = session.transaction().write()) {
                    System.out.println("Query no: " + count[0]);
                    List<ConceptMap> answers = tx.execute(query.asGet().asGet());
                    tx.commit();
                    count[0]++;
                }
            });

        } catch (Exception e) {
            System.err.println(e);
        }

        session.close();
    }

    @Test
    public void runQuery(){
        SessionImpl session = server.sessionFactory().session(keyspace);

        GraqlGet query = Graql.parse("" +
                "match " +
                "$taxpayer isa User;" +
                "(filer: $taxpayer, declaration: $declaration) isa files_tax_declaration;" +
                "$prefill isa FormDataSource;" +
                "$prefill has identifier == \"pre-fill\";" +
                "(declaration: $declaration, source: $prefill) isa uses_data_from;" +
                "$bonus_code_field (form: $prefill, field: $bonus_code);" +
                "$bonus_code_field isa has_form_field;" +
                "$bonus_code_field has identifier == \"730/QuadroC/PremiRisultato/$index/TipoLimite\";" +
                "$bonus_code_field has index $index;" +
                "get;"
        ).asGet();

        try (Transaction tx = session.transaction().write()) {
            List<ConceptMap> answers = tx.execute(query);
            tx.commit();
        }
    }
}
