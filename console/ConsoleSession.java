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

package grakn.core.console;

import grakn.client.GraknClient;
import grakn.client.exception.GraknClientException;
import grakn.core.console.exception.GraknConsoleException;
import grakn.core.console.printer.Printer;
import graql.lang.Graql;
import graql.lang.query.GraqlQuery;
import jline.console.ConsoleReader;
import jline.console.history.FileHistory;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.apache.commons.lang.StringEscapeUtils.unescapeJava;


/**
 * A Grakn Console Session that allows a user to interact with the Grakn Server
 */
public class ConsoleSession implements AutoCloseable {

    private static final String COPYRIGHT = "\n" +
            "Welcome to Grakn Console. You are now in Grakn Wonderland!\n" +
            "Copyright (C) 2019 Grakn Labs Limited\n\n";

    private static final String EDITOR = "editor";
    private static final String COMMIT = "commit";
    private static final String ROLLBACK = "rollback";
    private static final String LOAD = "load";
    private static final String CLEAR = "clear";
    private static final String EXIT = "exit";
    private static final String CLEAN = "clean";

    private static final String ANSI_PURPLE = "\u001B[35m";
    private static final String ANSI_RESET = "\u001B[0m";

    private static final String HISTORY_FILE = System.getProperty("user.home") + "/.grakn-console-history";
    private static final String UNIX_EDITOR_DEFAULT = "vim";
    private static final String WINDOWS_EDITOR_DEFAULT = "notepad";

    private static final String EDITOR_FILE = "/grakn-console-editor.gql";

    private final boolean infer;
    private final String keyspace;
    private final PrintStream printErr;
    private final ConsoleReader consoleReader;
    private final Printer<?> printer = Printer.stringPrinter(true);

    private final FileHistory historyFile;

    private final GraknClient client;
    private final GraknClient.Session session;
    private GraknClient.Transaction tx;
    private final AtomicBoolean terminated = new AtomicBoolean(false);


    ConsoleSession(String serverAddress, String keyspace, boolean infer, PrintStream printOut, PrintStream printErr) throws IOException {
        this.keyspace = keyspace;
        this.infer = infer;
        this.client = new GraknClient(serverAddress);
        this.session = client.session(keyspace);
        this.consoleReader = new ConsoleReader(System.in, printOut);
        this.consoleReader.setPrompt(ANSI_PURPLE + session.keyspace().name() + ANSI_RESET + "> ");
        this.printErr = printErr;

        File file = new File(HISTORY_FILE);
        file.createNewFile();
        this.historyFile = new FileHistory(file);
        this.consoleReader.setHistory(this.historyFile);
    }

    void load(Path filePath) throws IOException {
        consoleReader.println("Loading: " + filePath.toString());
        consoleReader.println("...");
        consoleReader.flush();

        tx = session.transaction().write();

        try {
            String queries = readFile(filePath);
            executeQuery(queries, false);
            commit();
            consoleReader.println("Successful commit: " + filePath.getFileName().toString());
        } catch (GraknClientException e) {
            String error = "Failed to load file: " + filePath.getFileName().toString();
            throw new GraknConsoleException(error, e);
        } finally {
            consoleReader.flush();
        }
    }

    void run() throws IOException, InterruptedException {
        consoleReader.setExpandEvents(false); // Disable JLine feature when seeing a '!'
        consoleReader.print(COPYRIGHT);

        tx = session.transaction().write();
        String input;

        while ((input = consoleReader.readLine()) != null && !terminated.get()) {
            if (input.equals(EDITOR)) {
                executeQuery(openTextEditor());

            } else if (input.startsWith(LOAD + ' ')) {
                try {
                    input = readFile(Paths.get(unescapeJava(input.substring(LOAD.length() + 1))));
                    executeQuery(input);
                } catch (NoSuchFileException e) {
                    System.err.println("File not found: " + e.getMessage());
                }
            } else if (input.equals(COMMIT)) {
                commit();

            } else if (input.equals(ROLLBACK)) {
                rollback();

            } else if (input.equals(CLEAN)) {
                clean();
                consoleReader.flush();
                return;

            } else if (input.equals(CLEAR)) {
                consoleReader.clearScreen();

            } else if (input.equals(EXIT)) {
                consoleReader.flush();
                return;

            } else if (!input.isEmpty()) {
                executeQuery(input);

            } // We ignore empty commands
        }
    }


    private static String readFile(Path filePath) throws IOException {
        List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
        return String.join("\n", lines);
    }

    private void executeQuery(String queryString) throws IOException {
        executeQuery(queryString, true);
    }

    private void executeQuery(String queryString, boolean catchRuntimeException) throws IOException {
        // We'll use streams so we can print the answer out much faster and smoother
        try {
            // Parse the string to get a stream of Graql Queries
            Stream<GraqlQuery> queries = Graql.parseList(queryString);

            // Get the stream of answers for each query (query.stream())
            // Get the  stream of printed answers (printer.toStream(..))
            // Combine the stream of printed answers into one stream (queries.flatMap(..))
            Stream<String> answers = queries.flatMap(query -> printer.toStream(tx.stream(query, infer)));

            // For each printed answer, print them on one line
            answers.forEach(answer -> {
                try {
                    consoleReader.println(answer);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        } catch (RuntimeException e) {
            // Flush out all answers from previous iterators in the stream
            consoleReader.flush();

            if (catchRuntimeException) {
                if (!e.getMessage().isEmpty()) {
                    printErr.println("Error: " + e.getMessage());
                } else {
                    printErr.println("Error: " + e.getClass().getName());
                }
                printErr.println("All uncommitted data is cleared");
                //If console session was terminated by shutdown hook thread, do not try to reopen transaction
                if (terminated.get()) return;
                reopenTransaction();
            } else {
                throw e;
            }
        }

        consoleReader.flush(); // Flush the ConsoleReader before the next command

        // It is important that we DO NOT close the transaction at the end of a query
        // The user may want to do consecutive operations onto the database
        // The transaction will only close once the user decides to COMMIT or ROLLBACK
    }

    private void commit() {
        try {
            tx.commit();
        } catch (RuntimeException e) {
            printErr.println(e.getMessage());
            printErr.println("All uncommitted data is cleared");
        } finally {
            reopenTransaction();
        }
    }

    private void rollback() {
        try {
            tx.close();
        } catch (RuntimeException e) {
            printErr.println(e.getMessage());
        } finally {
            reopenTransaction();
        }
    }

    private void clean() throws IOException {
        // Get user confirmation to clean graph
        consoleReader.println("Are you sure? CLEAN command will delete the current keyspace and its content.");
        consoleReader.println("Type 'confirm' to continue: ");

        String line = consoleReader.readLine();

        if (line != null && line.equals("confirm")) {
            consoleReader.println("Cleaning keyspace: " + keyspace);
            consoleReader.println("...");
            consoleReader.flush();
            client.keyspaces().delete(keyspace);
            consoleReader.println("Keyspace deleted: " + keyspace);
        } else {
            consoleReader.println("Clean command cancelled");
        }
    }

    private void reopenTransaction() {
        if (!tx.isClosed()) tx.close();
        tx = session.transaction().write();
    }

    @Override
    public final void close() {
        terminated.set(true);
        tx.close();
        session.close();
        client.close();
        try {
            historyFile.flush();
        } catch (IOException e) {
            // Print stacktrace to any available stream
            // nothing more to do here
            e.printStackTrace();
        }
    }

    /**
     * Open the user's preferred editor to write a query
     *
     * @return the string written in the editor
     */
    private String openTextEditor() throws IOException, InterruptedException {
        File tempFile = new File(System.getProperty("java.io.tmpdir") + EDITOR_FILE);
        tempFile.createNewFile();

        ProcessBuilder builder;

        if (isWindows()) {
            String editor = Optional.ofNullable(System.getenv().get("EDITOR")).orElse(WINDOWS_EDITOR_DEFAULT);
            builder = new ProcessBuilder("cmd", "/c", editor + " " + tempFile.getAbsolutePath());
        } else {
            String editor = Optional.ofNullable(System.getenv().get("EDITOR")).orElse(UNIX_EDITOR_DEFAULT);
            // Run the editor, pipe input into and out of tty so we can provide the input/output to the editor via Graql
            builder = new ProcessBuilder("/bin/bash", "-c", editor + " </dev/tty >/dev/tty " + tempFile.getAbsolutePath());
        }


        builder.start().waitFor();
        return String.join("\n", Files.readAllLines(tempFile.toPath()));
    }


    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

}
