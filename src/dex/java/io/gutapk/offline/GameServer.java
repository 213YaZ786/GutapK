package io.gutapk.offline;

/*
 * A local game server for a game that speaks BSON commands, so a game whose
 * official server is gone keeps the player's progress on the phone. The
 * engine holds no game data: the commands, the answers recorded for them and
 * the edits a command makes come from rules.bson, the progress sits in
 * state.bson, both in the server folder of the cache (see Bson, Seed).
 *
 * A request is served the way WireMock serves a stub: the nearest recorded
 * request is the matching stub, the scenarios move on when it is served (here
 * the rules edit the state), and the response definition is transformed (here
 * the state is injected into a login, the answer rules applied).
 *
 * Edits go to a tree of maps and lists in place, at a path, the way
 * zjsonpatch applies add, replace and remove on a JSON tree: the parent of
 * the path is evaluated, then the last token is applied to it. The path is
 * the pointer of the patch with the segments of this game's rules: "/" apart,
 * "#x" for the list element whose id is x, a number for an index, "-" for the
 * end of a list, else a map key. A value or a segment x may be "$field", the
 * incoming command's own field.
 *
 * https://github.com/wiremock/wiremock/blob/master/wiremock-core/src/main/java/com/github/tomakehurst/wiremock/stubbing/StoreBackedStubMappings.java#serveFor
 * https://github.com/wiremock/wiremock/blob/master/wiremock-core/src/main/java/com/github/tomakehurst/wiremock/verification/NearMissCalculator.java#findNearestTo
 * https://github.com/EKA2L1/EKA2L1/blob/6ad873a07ee3d745cd17179026dca247f6d11f6b/src/emu/android/app/src/main/java/com/github/eka2l1/util/FileUtils.java#copyAssetFile
 * https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/main/java/com/flipkart/zjsonpatch/InPlaceApplyProcessor.java#add
 * https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/main/java/com/flipkart/zjsonpatch/InPlaceApplyProcessor.java#replace
 * https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/main/java/com/flipkart/zjsonpatch/InPlaceApplyProcessor.java#remove
 * https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/main/java/com/flipkart/zjsonpatch/InPlaceApplyProcessor.java#set
 * https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/main/java/com/flipkart/zjsonpatch/InPlaceApplyProcessor.java#addToObject
 * https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/main/java/com/flipkart/zjsonpatch/InPlaceApplyProcessor.java#addToArray
 *
 * Not ported: move, copy, test, show (no rule moves, copies or tests a value)
 * Not ported: result (the tree is the state or the answer, held by the caller)
 * Not ported: allowRootReplacement (a path always names a message or a document of the tree, never the root: a root is refused)
 * Not ported: CompatibilityFlags, JsonNodeWrapper (one fixed behaviour on plain maps and lists)
 * Not ported: applyV1Transformations, applyV2Transformations, triggerListeners, withIdDecoratedRequest, withPathParamDecoratedRequest, copyOf (no transformer extensions, listeners or serve events)
 * Not ported: getScenarioStateOrNull, sortAndTruncate (the nearest one only is served, a stub is in no scenario)
 * Not ported: copyAssetFile (Seed.copyAssetFile copies assets, save writes the state aside and renames it the same way)
 *
 * Copyright (C) 2011-2026 Thomas Akehurst, Apache License 2.0.
 * Copyright 2016 flipkart.com zjsonpatch, Apache License 2.0.
 * Copyright (c) 2020 EKA2L1 Team, GPL-3.0-or-later.
 */

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GameServer {

    public static final String RULES = "rules.bson";

    public static final String STATE = "state.bson";

    /** What Jungle Heat's server sends with BSON bodies. */
    public static final String DEFAULT_TYPE = "text/plain; charset=utf-8";

    /** The command a request nobody recorded is answered with, as StubMapping.NOT_CONFIGURED answers one no stub matches. */
    public static final String STATUS = "status";

    /** The field of a list element that "#x" is compared to. */
    static final String ID = "id";

    /** Fields of a command that tell nothing about what it asks, left out when two requests are compared. */
    private static final String[] IGNORED = {"n", "optime"};

    /** The index of the end of a list, as JsonPointer.LAST_INDEX, "-" in a path. */
    static final int LAST_INDEX = Integer.MIN_VALUE;

    /** What the server answers: the BSON body and its content type. */
    public static final class Response {
        public final byte[] body;
        public final String type;

        Response(byte[] body, String type) {
            this.body = body;
            this.type = type;
        }
    }

    /** A path that cannot be reached, a rule that cannot be applied: the rule is skipped, never thrown at the game. */
    static final class Unreachable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** True when this request caused it (an element nobody holds): the rule is fine, only this call is skipped. */
        final boolean ofRequest;

        Unreachable(String message) {
            this(message, false);
        }

        Unreachable(String message, boolean ofRequest) {
            super(message);
            this.ofRequest = ofRequest;
        }
    }

    /** What a request was served with, before it is encoded: the response definition. */
    private static final class ResponseDefinition {
        final Object body;
        final String type;

        ResponseDefinition(Object body, String type) {
            this.body = body;
            this.type = type;
        }
    }

    private final File folder;

    /** rules.bson, null when the folder holds none: the server is then not ready and handles nothing. */
    private Map<String, Object> rules;

    /** state.bson, { messages: [...] }. */
    private Map<String, Object> state;

    /** Set when a rule changed the state, cleared when it is saved. */
    private boolean dirty;

    /** The latest request of each command, for a progress rule naming its entry by one. */
    private final Map<String, Map<String, Object>> sent = new LinkedHashMap<String, Map<String, Object>>();

    /** The commands nothing recorded and nothing saved, with how many times each came since the server started. */
    private final Map<String, Integer> unknown = new LinkedHashMap<String, Integer>();

    /** Set when the fields of the last unknown command were kept by family (the element it names holds fields of its names), not by a rule of its own. */
    private boolean deduced;

    public GameServer(File folder) {
        this.folder = folder;
        File file = new File(folder, RULES);
        if (!file.isFile()) {
            return;
        }
        try {
            rules = Bson.decode(Store.readAll(file));
        } catch (IOException e) {
            Server.LOG.warning("Local game server: " + file + " unreadable: " + e);
            return;
        } catch (IllegalArgumentException e) {
            Server.LOG.warning("Local game server: " + file + " is not BSON: " + e.getMessage());
            return;
        }
        File saved = new File(folder, STATE);
        if (saved.isFile()) {
            try {
                state = Bson.decode(Store.readAll(saved));
                check(state);
            } catch (IOException e) {
                Server.LOG.warning("Local game server: " + saved + " unreadable: " + e);
                state = null;
            } catch (IllegalArgumentException e) {
                // Kept aside, never overwritten: the player may want it back.
                Server.LOG.warning("Local game server: " + saved + " is not a state (" + e.getMessage() + "), kept as " + STATE + ".bad");
                saved.renameTo(new File(folder, STATE + ".bad"));
                state = null;
            }
        }
        if (state == null) {
            state = new LinkedHashMap<String, Object>();
            state.put("messages", new ArrayList<Object>());
        }
    }

    /** True when the folder holds rules: only then does the server answer. */
    public boolean ready() {
        return rules != null;
    }

    /** The state as it would be saved, for the admin route. */
    public synchronized byte[] stateBytes() {
        return Bson.encode(state);
    }

    // https://raw.githubusercontent.com/213YaZ786/GutapK/c52520a/src/dex/java/io/gutapk/offline/GameServer.java#stateBytes
    // Not ported: stateBytes (the state is read by its own route, the commands nobody saved are counted here)
    /** The commands that came and nothing saved, by name with their count, for the admin route. */
    public synchronized Map<String, Integer> unknown() {
        return new LinkedHashMap<String, Integer>(unknown);
    }

    /** Counts a command nothing recorded and nothing kept. */
    private void unsaved(String name) {
        Integer seen = unknown.get(name);
        unknown.put(name, Integer.valueOf(seen == null ? 1 : seen.intValue() + 1));
    }

    /** Replaces the state by a BSON document with a messages list, and saves it. IllegalArgumentException when it is not one. */
    public synchronized void replaceState(byte[] bytes) throws IOException {
        Map<String, Object> replacement = Bson.decode(bytes);
        check(replacement);
        state = replacement;
        save();
    }

    private static void check(Map<String, Object> candidate) {
        if (!(candidate.get("messages") instanceof List)) {
            throw new IllegalArgumentException("a state holds a messages list");
        }
        for (Object message : (List<?>) candidate.get("messages")) {
            if (!(message instanceof Map)) {
                throw new IllegalArgumentException("every message of a state is a document");
            }
        }
    }

    /**
     * Answers one request body, null when it is not for this server: not
     * BSON, or without the command field of the rules. A pack answers every
     * command it holds, in order, then the state is saved once.
     */
    public synchronized Response answer(byte[] body) {
        if (rules == null) {
            return null;
        }
        Map<String, Object> request;
        try {
            request = Bson.decode(body);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String field = string(rules.get("command"));
        if (field == null || !(request.get(field) instanceof String)) {
            return null;
        }
        ResponseDefinition responseDefinition;
        try {
            // The works whose time is up end first, so a login shows them done, the units of the queues too.
            long now = System.currentTimeMillis() / 1000L;
            elapse(now);
            tick(now);
            Map<String, Object> pack = map(rules.get("pack"));
            String packName = pack == null ? null : string(pack.get("command"));
            String listField = pack == null ? null : string(pack.get("list"));
            if (packName != null && listField != null && packName.equals(request.get(field)) && request.get(listField) instanceof List) {
                List<Object> answers = new ArrayList<Object>();
                for (Object item : (List<?>) request.get(listField)) {
                    if (item instanceof Map) {
                        answers.add(serveFor(field, castMap(item)).body);
                    }
                }
                Map<String, Object> out = new LinkedHashMap<String, Object>();
                out.put(field, packName);
                out.put(listField, answers);
                responseDefinition = new ResponseDefinition(out, DEFAULT_TYPE);
            } else {
                responseDefinition = serveFor(field, request);
            }
            if (dirty) {
                save();
            }
        } catch (RuntimeException e) {
            Server.LOG.warning("Local game server: " + request.get(field) + " not answered: " + e);
            return null;
        } catch (IOException e) {
            Server.LOG.warning("Local game server: state not saved: " + e);
            return null;
        }
        if (!(responseDefinition.body instanceof Map)) {
            return null;
        }
        return new Response(Bson.encode(castMap(responseDefinition.body)), responseDefinition.type);
    }

    // https://github.com/wiremock/wiremock/blob/master/wiremock-core/src/main/java/com/github/tomakehurst/wiremock/stubbing/StoreBackedStubMappings.java#serveFor
    /** One command: the matching stub, the rules on the state once it is served, then its response definition transformed. */
    private ResponseDefinition serveFor(String field, Map<String, Object> request) {
        String name = (String) request.get(field);
        Map<String, Object> matchingStub = findNearestTo(name, request);
        // The command whose answer rules edit the answer: this one, or the one refused alike.
        String answered = name;
        Object body;
        String type = DEFAULT_TYPE;
        String refused = matchingStub == null && !known(name) ? refusedAs(request) : null;
        String asked = matchingStub == null && !known(name) && name.startsWith("get_") ? name.substring(4) : null;
        if (refused != null && findNearestTo(refused, request) != null) {
            // A command never recorded that carries a field of a request the server
            // refused (the signature of an attack) gets that refusal: the game cannot
            // play against another player on the phone.
            body = copy(findNearestTo(refused, request).get("answer"));
            answered = refused;
            unsaved(name);
            Server.LOG.info("Local game server: unknown command " + name + " " + request + ", answered as " + refused + " was");
        } else if (asked != null && message(asked) != null) {
            // get_<name> asks for the message of the state named so (get_duel_battle_state).
            body = copy(message(asked));
            unsaved(name);
            Server.LOG.info("Local game server: unknown command " + name + ", answered with the state's " + asked);
        } else if (matchingStub == null) {
            Map<String, Object> notConfigured = new LinkedHashMap<String, Object>();
            notConfigured.put(field, STATUS);
            body = notConfigured;
            // As WireMock's journal keeps the requests no stub matched: each one with its
            // fields, so what the game sends can be read and learnt later.
            if (!known(name)) {
                String kept = keep(field, name, request);
                if (kept == null) {
                    unsaved(name);
                }
                // A command the game's code names an answer for (hero_unlocked_result) gets
                // it, its result whether the phone kept what was asked, and the request's fields.
                Map<String, Object> results = map(rules.get("results"));
                String result = results == null ? null : string(results.get(name));
                if (result != null) {
                    Map<String, Object> answer = new LinkedHashMap<String, Object>();
                    answer.put(field, result);
                    answer.put("result", Boolean.valueOf(kept != null));
                    for (Map.Entry<String, Object> entry : request.entrySet()) {
                        if (!entry.getKey().equals(field) && !ignored(entry.getKey())) {
                            answer.put(entry.getKey(), entry.getValue());
                        }
                    }
                    body = answer;
                }
                Server.LOG.info("Local game server: unknown command " + name + " " + request + (deduced ? " (deduced by family)" : "") + ", answered " + (result == null ? STATUS : result + " " + body) + (kept == null ? "" : ", kept in " + kept));
            }
        } else {
            body = copy(matchingStub.get("answer"));
            if (matchingStub.get("type") instanceof String) {
                type = (String) matchingStub.get("type");
            }
        }
        // The transformers: a login carries the state, the answer rules edit what is left.
        if (strings(rules.get("login")).contains(name)) {
            body = inject(field, name, body);
            for (String time : strings(rules.get("time"))) {
                try {
                    now(body, time);
                } catch (Unreachable e) {
                    skipped(name, "time", time, e);
                }
            }
        }
        entries(name, body);
        for (Object item : list(rules.get("answerRules"))) {
            Map<String, Object> rule = map(item);
            if (rule != null && answered.equals(rule.get("command"))) {
                apply(rule, body, false, request);
                // A refusal of the server keeps its message, its reasons go.
                if ("remove".equals(rule.get("op"))) {
                    Server.LOG.info("Local game server: " + name + " refused as the server did, without " + rule.get("path"));
                }
            }
        }
        // The resources first, at the grade the element has before the rules move it.
        economy(name, request);
        // The scenarios move on: the rules of the command edit the state, those
        // learnt from requests like this one only.
        // The line comes when no rule of the command holds for this request, not when
        // the rules of another base are left out.
        boolean applied = false;
        Object unlearnt = null;
        for (Object item : list(rules.get("rules"))) {
            Map<String, Object> rule = map(item);
            if (rule == null || !name.equals(rule.get("command"))) {
                continue;
            }
            if (matches(rule, request)) {
                apply(rule, state, true, request);
                applied = true;
            } else if (unlearnt == null) {
                unlearnt = rule.get("when");
            }
        }
        if (!applied && unlearnt != null) {
            Server.LOG.info("Local game server: " + name + " was learnt from requests with " + unlearnt + " only, this one leaves the progress as it is: " + request);
        }
        production(name, request);
        work(name, request);
        progress(name, request);
        sent.put(name, request);
        return new ResponseDefinition(body, type);
    }

    // https://github.com/wiremock/wiremock/blob/master/wiremock-core/src/main/java/com/github/tomakehurst/wiremock/verification/NearMissCalculator.java#findNearestTo
    /** The recorded stub of the command whose request shares the most equal top-level fields with the incoming one, the first on a tie. */
    private Map<String, Object> findNearestTo(String name, Map<String, Object> request) {
        Map<String, Object> allMappings = map(rules.get("answers"));
        if (allMappings == null) {
            return null;
        }
        Map<String, Object> nearest = null;
        int most = -1;
        for (Object item : list(allMappings.get(name))) {
            Map<String, Object> stubMapping = map(item);
            if (stubMapping == null) {
                continue;
            }
            int shared = 0;
            Map<String, Object> recorded = map(stubMapping.get("request"));
            if (recorded != null) {
                for (Map.Entry<String, Object> entry : recorded.entrySet()) {
                    if (!ignored(entry.getKey()) && request.containsKey(entry.getKey()) && same(entry.getValue(), request.get(entry.getKey()))) {
                        shared++;
                    }
                }
            }
            if (shared > most) {
                most = shared;
                nearest = stubMapping;
            }
        }
        return nearest;
    }

    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/dex/java/io/gutapk/offline/GameServer.java#findNearestTo
    // Not ported: the count of the fields shared (a rule holds for every field of its when or not at all)
    /** True when the request holds every field of the rule's when with its value, as the requests the rule was learnt from did. */
    private static boolean matches(Map<String, Object> rule, Map<String, Object> request) {
        Map<String, Object> recorded = map(rule.get("when"));
        if (recorded == null) {
            return true;
        }
        for (Map.Entry<String, Object> entry : recorded.entrySet()) {
            if (!request.containsKey(entry.getKey()) || !same(entry.getValue(), request.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/dex/java/io/gutapk/offline/GameServer.java#matches
    // Not ported: matches (a field is enough, its value is the request's own, a signature)
    /** The command the server refused whose request held a field this one holds, null when none did. */
    private String refusedAs(Map<String, Object> request) {
        for (Object item : list(rules.get("refusals"))) {
            Map<String, Object> refusal = map(item);
            if (refusal == null) {
                continue;
            }
            for (String key : strings(refusal.get("fields"))) {
                if (request.containsKey(key)) {
                    return string(refusal.get("command"));
                }
            }
        }
        return null;
    }

    private static boolean ignored(String field) {
        for (String item : IGNORED) {
            if (item.equals(field)) {
                return true;
            }
        }
        return false;
    }

    /** True when a command is in the rules or the answers: only a command seen in none is logged as unknown. */
    private boolean known(String name) {
        Map<String, Object> answers = map(rules.get("answers"));
        if (answers != null && answers.containsKey(name)) {
            return true;
        }
        if (strings(rules.get("login")).contains(name)) {
            return true;
        }
        for (Object item : list(rules.get("production"))) {
            for (String part : new String[] {"train", "force", "left"}) {
                Map<String, Object> command = map(item) == null ? null : map(map(item).get(part));
                if (command != null && name.equals(command.get("command"))) {
                    return true;
                }
            }
        }
        for (String key : new String[] {"rules", "answerRules"}) {
            for (Object item : list(rules.get(key))) {
                Map<String, Object> rule = map(item);
                if (rule != null && name.equals(rule.get("command"))) {
                    return true;
                }
            }
        }
        // The commands that start a work or end it, the ones deduced by family too.
        for (String key : new String[] {"works", "timers"}) {
            for (Object item : list(rules.get(key))) {
                Map<String, Object> rule = map(item);
                Map<String, Object> start = rule == null ? null : map(rule.get("start"));
                if (start != null && name.equals(start.get("command"))) {
                    return true;
                }
                for (Object end : rule == null ? new ArrayList<Object>() : list(rule.get("ends"))) {
                    if (map(end) != null && name.equals(map(end).get("command"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Every document of the answer, at any depth and inside lists, named like
     * a message of the state is replaced by a copy of that message. The
     * document named like the command itself stays: it is the echo. A
     * replacement is not searched again.
     */
    private Object inject(String field, String command, Object node) {
        if (node instanceof Map) {
            Map<String, Object> document = castMap(node);
            Object name = document.get(field);
            if (name instanceof String && !name.equals(command)) {
                Map<String, Object> message = message((String) name);
                if (message != null) {
                    return copy(message);
                }
            }
            for (Map.Entry<String, Object> entry : document.entrySet()) {
                entry.setValue(inject(field, command, entry.getValue()));
            }
        } else if (node instanceof List) {
            List<Object> items = castList(node);
            for (int i = 0; i < items.size(); i++) {
                items.set(i, inject(field, command, items.get(i)));
            }
        }
        return node;
    }

    /** The message of the state with that name. */
    private Map<String, Object> message(String name) {
        String field = string(rules.get("command"));
        for (Object item : list(state.get("messages"))) {
            Map<String, Object> message = map(item);
            if (message != null && name.equals(message.get(field))) {
                return message;
            }
        }
        return null;
    }

    // --- Paths

    /** A path cut into its segments, as a JsonPointer is into reference tokens. */
    static final class Pointer {
        final List<String> tokens;

        Pointer(List<String> tokens) {
            this.tokens = tokens;
        }

        static Pointer parse(String path) {
            List<String> tokens = new ArrayList<String>();
            for (String token : path.split("/")) {
                if (!token.isEmpty()) {
                    tokens.add(token);
                }
            }
            return new Pointer(tokens);
        }

        boolean isRoot() {
            return tokens.isEmpty();
        }

        Pointer getParent() {
            return new Pointer(new ArrayList<String>(tokens.subList(0, tokens.size() - 1)));
        }

        String last() {
            return tokens.get(tokens.size() - 1);
        }

        Pointer append(String token) {
            List<String> longer = new ArrayList<String>(tokens);
            longer.add(token);
            return new Pointer(longer);
        }

        @Override
        public String toString() {
            return "/" + String.join("/", tokens);
        }
    }

    /** An in place patch of a tree: the state, whose first segment is a message name, or an answer, where it is the name of a document in it. */
    private final class Patch {
        private final Object target;
        private final boolean fromState;
        private final Map<String, Object> command;

        /** Whether the maps missing on the way are created, only the set op does. */
        boolean create;

        Patch(Object target, boolean fromState, Map<String, Object> command) {
            this.target = target;
            this.fromState = fromState;
            this.command = command;
        }

        /** Evaluates a path to the node it names, creating the maps missing on the way when asked. */
        Object evaluate(Pointer path, boolean create) {
            if (path.isRoot()) {
                return target;
            }
            Object node = first(path.tokens.get(0));
            for (int i = 1; i < path.tokens.size(); i++) {
                node = child(node, path.tokens.get(i), create);
            }
            return node;
        }

        /**
         * The first segment: a message of the state, or a key of the answer, else the
         * first document of the answer with that name. The key comes first: the PC
         * learns a path through the answer's keys, and an answer named like its key
         * (a location holding location/locationId) is not the document it names.
         */
        private Object first(String token) {
            if (fromState) {
                Map<String, Object> valueNode = message(token);
                if (valueNode == null) {
                    throw new Unreachable("no message " + token);
                }
                return valueNode;
            }
            Object valueNode = target instanceof Map ? castMap(target).get(token) : null;
            if (valueNode == null) {
                valueNode = named(target, token);
            }
            if (valueNode == null) {
                throw new Unreachable("no document " + token + " in the answer");
            }
            return valueNode;
        }

        private Object named(Object node, String name) {
            String field = string(rules.get("command"));
            if (node instanceof Map) {
                if (name.equals(castMap(node).get(field))) {
                    return node;
                }
                for (Object value : castMap(node).values()) {
                    Object valueNode = named(value, name);
                    if (valueNode != null) {
                        return valueNode;
                    }
                }
            } else if (node instanceof List) {
                for (Object value : castList(node)) {
                    Object valueNode = named(value, name);
                    if (valueNode != null) {
                        return valueNode;
                    }
                }
            }
            return null;
        }

        private Object child(Object node, String token, boolean create) {
            Object valueNode;
            if (node instanceof Map) {
                Map<String, Object> object = castMap(node);
                if (token.startsWith("#")) {
                    throw new Unreachable("a document has no element " + token);
                }
                if (!object.containsKey(token) && create) {
                    object.put(token, new LinkedHashMap<String, Object>());
                }
                valueNode = object.get(token);
            } else if (node instanceof List) {
                List<Object> array = castList(node);
                int index = getIndex(array, token);
                if (index < 0 || index >= array.size()) {
                    throw new Unreachable("no element " + token);
                }
                valueNode = array.get(index);
            } else {
                throw new Unreachable("cannot reference past scalar value at " + token);
            }
            if (valueNode == null) {
                throw new Unreachable("nothing at " + token);
            }
            return valueNode;
        }

        /** The index a token names in a list: "-" the end, "#x" the element whose id is x, a number itself, -1 when there is none. */
        int getIndex(List<Object> array, String token) {
            if ("-".equals(token)) {
                return LAST_INDEX;
            }
            if (token.startsWith("#")) {
                Object id = value(token.substring(1), command);
                for (int i = 0; i < array.size(); i++) {
                    if (array.get(i) instanceof Map && same(castMap(array.get(i)).get(ID), id)) {
                        return i;
                    }
                }
                return -1;
            }
            try {
                return Integer.parseInt(token);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        /** True when the path names something already. */
        boolean has(Pointer path) {
            Object parentNode = evaluate(path.getParent(), create);
            if (parentNode instanceof Map) {
                return castMap(parentNode).containsKey(path.last());
            }
            if (parentNode instanceof List) {
                int index = getIndex(castList(parentNode), path.last());
                return index >= 0 && index < castList(parentNode).size();
            }
            return false;
        }

        /** The value a path names, null when there is none. */
        Object get(Pointer path) {
            Object parentNode = evaluate(path.getParent(), create);
            if (parentNode instanceof Map) {
                return castMap(parentNode).get(path.last());
            }
            if (parentNode instanceof List) {
                int index = getIndex(castList(parentNode), path.last());
                return index >= 0 && index < castList(parentNode).size() ? castList(parentNode).get(index) : null;
            }
            throw new Unreachable("cannot reference past scalar value at " + path);
        }

        // https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/main/java/com/flipkart/zjsonpatch/InPlaceApplyProcessor.java#add
        void add(Pointer path, Object value) {
            set(path, value, "add");
        }

        // https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/main/java/com/flipkart/zjsonpatch/InPlaceApplyProcessor.java#replace
        void replace(Pointer path, Object value) {
            if (path.isRoot()) {
                throw new Unreachable("Cannot replace root document");
            }

            Object parentNode = evaluate(path.getParent(), false);
            String token = path.last();
            if (parentNode instanceof Map) {
                if (!castMap(parentNode).containsKey(token))
                    throw new Unreachable("Missing field \"" + token + "\" at " + path.getParent());
                castMap(parentNode).put(token, value);
            } else if (parentNode instanceof List) {
                int index = getIndex(castList(parentNode), token);
                if (index < 0 || index >= castList(parentNode).size())
                    throw new Unreachable("Array index " + token + " out of bounds at " + path.getParent());
                castList(parentNode).set(index, value);
            } else {
                throw new Unreachable("Can't reference past scalar value at " + path.getParent());
            }
        }

        // https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/main/java/com/flipkart/zjsonpatch/InPlaceApplyProcessor.java#remove
        void remove(Pointer path) {
            if (path.isRoot())
                throw new Unreachable("Cannot remove document root");

            Object parentNode = evaluate(path.getParent(), false);
            String token = path.last();
            if (parentNode instanceof Map) {
                // A key already gone is what the rule wanted, not a failure.
                castMap(parentNode).remove(token);
            } else if (parentNode instanceof List) {
                int index = getIndex(castList(parentNode), token);
                if (index < 0 || index >= castList(parentNode).size())
                    throw new Unreachable("Array index " + token + " out of bounds at " + path.getParent());
                castList(parentNode).remove(index);
            } else {
                throw new Unreachable("Cannot reference past scalar value at " + path.getParent());
            }
        }

        // https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/main/java/com/flipkart/zjsonpatch/InPlaceApplyProcessor.java#set
        void set(Pointer path, Object value, String forOp) {
            if (path.isRoot()) {
                throw new Unreachable("Cannot replace root document");
            }

            Object parentNode = evaluate(path.getParent(), create);
            if (!(parentNode instanceof Map) && !(parentNode instanceof List))
                throw new Unreachable("Cannot reference past scalar value at " + path.getParent());
            else if (parentNode instanceof List)
                addToArray(path, value, parentNode);
            else
                addToObject(path, parentNode, value);
        }

        // https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/main/java/com/flipkart/zjsonpatch/InPlaceApplyProcessor.java#addToObject
        private void addToObject(Pointer path, Object node, Object value) {
            final Map<String, Object> target = castMap(node);
            String key = path.last();
            target.put(key, value);
        }

        // https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/main/java/com/flipkart/zjsonpatch/InPlaceApplyProcessor.java#addToArray
        private void addToArray(Pointer path, Object value, Object parentNode) {
            final List<Object> target = castList(parentNode);
            int idx = getIndex(target, path.last());

            if (idx == LAST_INDEX) {
                // the end of the list, RFC 6902 section 4.1
                target.add(value);
            } else {
                if (idx < 0 || idx > target.size())
                    throw new Unreachable("Array index " + path.last() + " out of bounds at " + path.getParent());
                target.add(idx, value);
            }
        }

        /** Puts a value at a path: replaces what is there, adds what is not. */
        void put(Pointer path, Object value) {
            if (has(path)) {
                replace(path, value);
            } else {
                add(path, value);
            }
        }
    }

    /** One rule on the state or on an answer. A rule that cannot be applied is skipped with one log line. */
    private void apply(Map<String, Object> rule, Object target, boolean fromState, Map<String, Object> command) {
        String name = String.valueOf(rule.get("command"));
        String op = rule.get("op") instanceof String ? (String) rule.get("op") : "set";
        String path = string(rule.get("path"));
        try {
            if (path == null) {
                throw new Unreachable("no path");
            }
            Pointer pointer = Pointer.parse(path);
            // A value read from the state may fill a field of the answer itself.
            if (pointer.tokens.size() < 2 && (fromState || !"state".equals(op))) {
                throw new Unreachable("a path names a field of a message or a document");
            }
            Patch patch = new Patch(target, fromState, command);
            if ("state".equals(op)) {
                if (fromState) {
                    throw new Unreachable("the state is read into an answer, not into itself");
                }
                patch.put(pointer, apply(rule, command));
            } else if ("set".equals(op)) {
                patch.create = true;
                patch.put(pointer, value(rule.get("value"), command));
            } else if ("append".equals(op)) {
                Object value = value(rule.get("value"), command);
                Object parentNode = patch.evaluate(pointer.getParent(), false);
                if (parentNode instanceof Map && !castMap(parentNode).containsKey(pointer.last())) {
                    castMap(parentNode).put(pointer.last(), new ArrayList<Object>());
                }
                if (!(patch.get(pointer) instanceof List)) {
                    throw new Unreachable("not a list");
                }
                // An element with the same id is replaced, else the value goes to the end.
                Object id = value instanceof Map ? castMap(value).get(ID) : null;
                Pointer same = id == null ? null : pointer.append("#" + id);
                if (same != null && patch.has(same)) {
                    patch.replace(same, value);
                } else {
                    patch.add(pointer.append("-"), value);
                }
            } else if ("increment".equals(op)) {
                Object by = rule.containsKey("by") ? value(rule.get("by"), command) : Integer.valueOf(1);
                Object initial = rule.containsKey("default") ? value(rule.get("default"), command) : Integer.valueOf(0);
                Object valueNode = patch.has(pointer) ? patch.get(pointer) : null;
                patch.put(pointer, sum(valueNode instanceof Number ? valueNode : initial, by));
            } else if ("remove".equals(op)) {
                patch.remove(pointer);
            } else {
                throw new Unreachable("unknown op " + op);
            }
            if (fromState) {
                dirty = true;
            }
        } catch (Unreachable e) {
            skipped(name, op, path, e);
        }
    }

    /**
     * The value an answer rule reads from the state when the answer is served: the
     * value at "from", or with "each" a list with one element per document of it,
     * the template with its "$field" read from that document.
     */
    // https://github.com/wiremock/wiremock-state-extension/blob/HEAD/src/main/java/org/wiremock/extensions/state/extensions/StateHandlerbarHelper.java#apply
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/dex/java/io/gutapk/offline/GameServer.java#apply
    // Not ported: Options, HandlebarsHelper, ContextManager (the state is the server's own), property, defaultValue, getProperty, convertToPropertySpecificDefault, SpecialProperties, handleError (an Unreachable is skipped by the rule's apply with one line)
    private Object apply(Map<String, Object> rule, Map<String, Object> command) {
        String contextName = string(rule.get("from"));
        Object each = rule.get("each");
        if (contextName == null || contextName.isEmpty()) {
            throw new Unreachable("'from' cannot be empty");
        }
        Object list = getList(contextName, command);
        if (each == null) {
            return list;
        }
        if (!(list instanceof List)) {
            throw new Unreachable("'each' needs a list of documents at " + contextName);
        }
        List<Object> documents = new ArrayList<Object>();
        for (Object document : castList(list)) {
            if (!(document instanceof Map)) {
                throw new Unreachable("'each' needs documents at " + contextName);
            }
            documents.add(getUnitUpgradeLevel(each, castMap(document), command));
        }
        return documents;
    }

    /**
     * An "each" template read for one document: "$field" is the document's own, a
     * {grade: ...} entry the level the state holds for the unit the document
     * names (the entry of the list the laboratory merged), the default when the
     * unit is not in it.
     */
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitUpgradeComponent.cs#GetTotalSeconds
    // https://raw.githubusercontent.com/213YaZ786/GutapK/c52520a/src/dex/java/io/gutapk/offline/GameServer.java#elapse
    // Not ported: GetTotalSeconds, elapse (the time of a work is getTotalSeconds above, this reads the level only)
    private Object getUnitUpgradeLevel(Object each, Map<String, Object> document, Map<String, Object> command) {
        if (!(each instanceof List)) {
            return value(each, document);
        }
        List<Object> out = new ArrayList<Object>();
        for (Object item : castList(each)) {
            Map<String, Object> grade = map(item) == null ? null : map(map(item).get("grade"));
            if (grade == null) {
                out.add(value(item, document));
                continue;
            }
            Object unit = document.get(string(grade.get("of")));
            Object level = grade.get("default");
            try {
                for (Object entry : list(getList(string(grade.get("list")), command))) {
                    if (map(entry) != null && same(map(entry).get(string(grade.get("match"))), unit)) {
                        level = map(entry).get(string(grade.get("field")));
                    }
                }
            } catch (Unreachable e) {
                skipped(String.valueOf(command.get(string(rules.get("command")))), "grade", string(grade.get("list")), e);
            }
            out.add(copy(level));
        }
        return out;
    }

    /**
     * A copy of what the state holds at a path. A * walks every element of the list
     * before it, the lists found at the end are one list in order: a document without
     * the rest of the path is passed over.
     */
    // https://github.com/wiremock/wiremock-state-extension/blob/HEAD/src/main/java/org/wiremock/extensions/state/extensions/StateHandlerbarHelper.java#getList
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/dex/java/io/gutapk/offline/GameServer.java#apply
    // Not ported: the JsonPath query (the path is a Pointer, which the rules already use), Optional, the default of an absent list
    private Object getList(String contextName, Map<String, Object> command) {
        Pointer list = Pointer.parse(contextName);
        if (list.isRoot()) {
            throw new Unreachable("'from' cannot be empty");
        }
        Patch patch = new Patch(state, true, command);
        List<Object> found = new ArrayList<Object>();
        found.add(patch.first(list.tokens.get(0)));
        boolean every = false;
        for (String token : list.tokens.subList(1, list.tokens.size())) {
            List<Object> next = new ArrayList<Object>();
            for (Object node : found) {
                if (token.startsWith("*")) {
                    if (!(node instanceof List)) {
                        throw new Unreachable("* needs a list, not " + node.getClass().getSimpleName());
                    }
                    // *key=value: the elements holding that value in that field only (the camps).
                    int equal = token.indexOf('=');
                    for (Object element : castList(node)) {
                        if (equal < 0 || (map(element) != null && token(map(element).get(token.substring(1, equal))).equals(token.substring(equal + 1)))) {
                            next.add(element);
                        }
                    }
                    every = true;
                    continue;
                }
                try {
                    next.add(patch.child(node, token, false));
                } catch (Unreachable e) {
                    if (!every) {
                        throw e;
                    }
                }
            }
            found = next;
        }
        if (!every) {
            return copy(found.get(0));
        }
        if ("*".equals(list.last())) {
            return copy(found);
        }
        List<Object> lists = new ArrayList<Object>();
        for (Object node : found) {
            if (!(node instanceof List)) {
                throw new Unreachable("the lists of " + contextName + " hold a " + node.getClass().getSimpleName());
            }
            lists.addAll(castList(copy(node)));
        }
        return lists;
    }

    /**
     * The works of the rules, the timers among them: an element of a list whose
     * field holds the value of a work under way (an upgrade, a clearing, a
     * research) ends it once its time is up, the time looked up by the
     * element's key in the durations the PC found in the game's data. The end
     * is applied to the element: a number up, the field set back, the time set
     * to the end, a list merged by key, or the element removed from its list.
     */
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Obstacle.cs#ClearingFinished
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitUpgradeComponent.cs#FinishUpgrading
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitUpgradeComponent.cs#GetTotalSeconds
    // https://raw.githubusercontent.com/213YaZ786/GutapK/c52520a/src/dex/java/io/gutapk/offline/GameServer.java#elapse
    // Not ported: m_vLevel.WorkerManager.DeallocateWorker (no worker is held), the rewards and the clear counter Obstacle.cs lists as todo, SetUnitUpgradeLevel (the level is the entry of the list the element holds)
    // Not ported: GetCurrentlyUpgradedUnit, GetRemainingSeconds (the unit is a field of the element, the time left is its end minus now)
    private void elapse(long now) {
        List<Object> every = new ArrayList<Object>(list(rules.get("timers")));
        every.addAll(list(rules.get("works")));
        for (Object item : every) {
            Map<String, Object> rule = map(item);
            String path = rule == null ? null : string(rule.get("list"));
            String field = rule == null ? null : string(rule.get("field"));
            String since = rule == null ? null : string(rule.get("since"));
            Map<String, Object> durations = rule == null ? null : map(rule.get("durations"));
            List<String> key = rule == null ? new ArrayList<String>() : strings(rule.get("key"));
            if (path == null || field == null || since == null || durations == null || key.isEmpty()) {
                continue;
            }
            Object parentNode;
            try {
                parentNode = new Patch(state, true, new LinkedHashMap<String, Object>()).evaluate(Pointer.parse(path), false);
            } catch (Unreachable e) {
                continue;
            }
            // A copy: an end may take its element out of the list.
            for (Object element : new ArrayList<Object>(list(parentNode))) {
                Map<String, Object> target = map(element);
                if (target == null || !same(valueAt(target, field), rule.get("value")) || !(valueAt(target, since) instanceof Number)) {
                    continue;
                }
                Long seconds = getTotalSeconds(rule, target);
                if (seconds == null) {
                    continue;
                }
                long end = ((Number) valueAt(target, since)).longValue() + seconds.longValue();
                if (end > now) {
                    continue;
                }
                clearingFinished(rule, list(parentNode), target, end);
                Server.LOG.info("Local game server: " + field + " " + rule.get("value") + " of " + path + " #" + target.get(ID) + " ended");
            }
        }
    }

    /** The seconds a work takes for an element: the durations by its key, then its grade, null when the data gives none. */
    private static Long getTotalSeconds(Map<String, Object> rule, Map<String, Object> target) {
        Map<String, Object> durations = map(rule.get("durations"));
        List<String> key = strings(rule.get("key"));
        if (durations == null || key.isEmpty() || valueAt(target, key.get(0)) == null) {
            return null;
        }
        String index = token(valueAt(target, key.get(0)));
        Map<String, Object> grade = map(rule.get("grade"));
        if (key.size() == 2) {
            Object level = valueAt(target, key.get(1));
            index += "," + token(level == null ? rule.get("default") : level);
        } else if (grade != null) {
            // The level a unit has is the entry of the list the element holds, the default when the unit is not there.
            Object level = rule.get("default");
            for (Object entry : list(valueAt(target, string(grade.get("list"))))) {
                if (map(entry) != null && same(map(entry).get(string(grade.get("match"))), valueAt(target, key.get(0)))) {
                    level = map(entry).get(string(grade.get("field")));
                }
            }
            index += "," + token(level);
        }
        Object duration = durations.get(index);
        return duration instanceof Number ? Long.valueOf(((Number) duration).longValue()) : null;
    }

    /** The end of a work on an element: removed from its list when the work removes it, else the end applied to it. */
    private void clearingFinished(Map<String, Object> rule, List<Object> parentNode, Map<String, Object> target, long end) {
        for (Object then : list(rule.get("then"))) {
            Map<String, Object> op = map(then);
            if (op != null && "remove".equals(op.get("op")) && op.get("path") == null) {
                parentNode.remove(target);
                dirty = true;
                return;
            }
        }
        finishUpgrading(rule, target, end);
    }

    /** The ops of the end on the element: a number up, a field set (the end for "$end"), a list merged by key, a field removed. */
    private void finishUpgrading(Map<String, Object> rule, Map<String, Object> target, long end) {
        for (Object then : list(rule.get("then"))) {
            Map<String, Object> op = map(then);
            String inner = op == null ? null : string(op.get("path"));
            if (inner == null) {
                continue;
            }
            if ("increment".equals(op.get("op"))) {
                Object valueNode = valueAt(target, inner);
                put(target, inner, sum(valueNode instanceof Number ? valueNode : rule.get("default"), Integer.valueOf(1)));
            } else if ("merge".equals(op.get("op"))) {
                Object key = valueAt(target, string(op.get("from")));
                String match = string(op.get("match"));
                String counter = string(op.get("field"));
                if (!(valueAt(target, inner) instanceof List)) {
                    put(target, inner, new ArrayList<Object>());
                }
                Map<String, Object> found = null;
                for (Object entry : list(valueAt(target, inner))) {
                    if (map(entry) != null && same(map(entry).get(match), key)) {
                        found = map(entry);
                    }
                }
                if (found != null) {
                    found.put(counter, sum(found.get(counter), Integer.valueOf(1)));
                } else {
                    Map<String, Object> entry = new LinkedHashMap<String, Object>();
                    entry.put(match, copy(key));
                    entry.put(counter, sum(op.get("default"), Integer.valueOf(1)));
                    castList(valueAt(target, inner)).add(entry);
                }
            } else if ("remove".equals(op.get("op"))) {
                String[] tokens = inner.split("/");
                Object parentNode = tokens.length == 1 ? target : valueAt(target, inner.substring(0, inner.lastIndexOf('/')));
                if (parentNode instanceof Map) {
                    castMap(parentNode).remove(tokens[tokens.length - 1]);
                }
            } else if ("$end".equals(op.get("value"))) {
                put(target, inner, (int) end == end ? (Object) Integer.valueOf((int) end) : (Object) Long.valueOf(end));
            } else {
                put(target, inner, copy(op.get("value")));
            }
        }
        dirty = true;
    }

    /**
     * The commands of the works: the one that starts a work puts its element
     * under way, the ones that hurry it end it at once, the ones that cancel
     * it put the element back as it was and give the price back. The ends
     * the PC deduced by family (a stem of the start's name) are told by
     * their mark in the log.
     */
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Obstacle.cs#SpeedUpClearing
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Obstacle.cs#CancelClearing
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitUpgradeComponent.cs#StartUpgrading
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitUpgradeComponent.cs#SpeedUp
    // https://raw.githubusercontent.com/213YaZ786/GutapK/c52520a/src/dex/java/io/gutapk/offline/GameServer.java#elapse
    // Not ported: GamePlayUtil.GetSpeedUpCost, HasEnoughDiamonds, UseDiamonds (the price of a hurry is the economy's spend, the diamonds a command sent), m_vLevel.WorkerManager (no worker is held)
    // Not ported: CanStartUpgrading's levels (the laboratory, the barracks: no level of a building is checked, one work at a time is), the timer object (the end is the element's own time plus the data's)
    private void work(String name, Map<String, Object> command) {
        long now = System.currentTimeMillis() / 1000L;
        long time = command.get("optime") instanceof Number ? number(command.get("optime")) : now;
        List<Object> every = new ArrayList<Object>(list(rules.get("timers")));
        every.addAll(list(rules.get("works")));
        for (Object item : every) {
            Map<String, Object> rule = map(item);
            Map<String, Object> start = rule == null ? null : map(rule.get("start"));
            if (rule == null || start == null) {
                continue;
            }
            try {
                // A timer's start is the rules': only a work with sets starts here.
                if (name.equals(start.get("command")) && map(start.get("sets")) != null && matches(rule, command)) {
                    startUpgrading(rule, command, time);
                }
                for (Object entry : list(rule.get("ends"))) {
                    Map<String, Object> end = map(entry);
                    if (end == null || !name.equals(end.get("command"))) {
                        continue;
                    }
                    boolean deduced = Boolean.TRUE.equals(end.get("deduced"));
                    if (deduced ? !agrees(rule, command) : !matches(rule, command)) {
                        continue;
                    }
                    if ("cancel".equals(end.get("role"))) {
                        cancelClearing(rule, end, command, time);
                    } else if (removes(rule)) {
                        speedUpClearing(rule, end, command, time);
                    } else {
                        speedUp(rule, end, command, time);
                    }
                    Server.LOG.info("Local game server: " + name + " " + end.get("role") + "s the work of " + start.get("command") + (deduced ? " (deduced by family)" : ""));
                }
            } catch (Unreachable e) {
                skipped(name, "work", string(rule.get("list")), e);
            }
        }
    }

    /** True when the request holds no field of the rule's when with another value: a command nobody recorded may lack the field. */
    private static boolean agrees(Map<String, Object> rule, Map<String, Object> request) {
        Map<String, Object> recorded = map(rule.get("when"));
        if (recorded != null) {
            for (Map.Entry<String, Object> entry : recorded.entrySet()) {
                if (request.containsKey(entry.getKey()) && !same(entry.getValue(), request.get(entry.getKey()))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** True when the end of the work takes the element out of its list (a clearing). */
    private static boolean removes(Map<String, Object> rule) {
        for (Object then : list(rule.get("then"))) {
            Map<String, Object> op = map(then);
            if (op != null && "remove".equals(op.get("op")) && op.get("path") == null) {
                return true;
            }
        }
        return false;
    }

    /** The element a command names that has the work under way, else the call is skipped. */
    private Map<String, Object> underWay(Map<String, Object> rule, Map<String, Object> end, Map<String, Object> command) {
        Object named = command.get(string(end.get("element")));
        String by = string(end.get("by"));
        // UnitUpgradeComponent.SpeedUp finishes the unit its building holds: the element is of the kind the start names
        // ("*articleId=40", the laboratory) and holds the unit the command names at "by", it is not an element whose id that is.
        // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitUpgradeComponent.cs#SpeedUp
        // Not ported: SpeedUp (speedUp above finishes the work, this only finds the component that holds the unit under way)
        Map<String, Object> begun = map(rule.get("start"));
        String at = begun == null ? null : string(begun.get("at"));
        int equal = at == null ? -1 : at.indexOf('=');
        for (Object item : components(rule)) {
            Map<String, Object> target = map(item);
            if (target != null && named != null && (equal < 0 || token(target.get(at.substring(1, equal))).equals(at.substring(equal + 1))) && same(by == null ? target.get(ID) : valueAt(target, by), named)) {
                if (!same(valueAt(target, string(rule.get("field"))), rule.get("value"))) {
                    throw new Unreachable("nothing under way in #" + target.get(ID), true);
                }
                return target;
            }
        }
        throw new Unreachable("no element " + named + " in " + rule.get("list") + (by == null ? "" : " holds it at " + by), true);
    }

    /** A work starts on the element the command names, or on the first of its kind with nothing under way. Its end is the data's time after the start. */
    private void startUpgrading(Map<String, Object> rule, Map<String, Object> command, long time) {
        Map<String, Object> start = map(rule.get("start"));
        String path = string(rule.get("list"));
        String field = string(rule.get("field"));
        Map<String, Object> target = null;
        if (start.get("element") != null) {
            target = element(path, command.get(string(start.get("element"))));
        } else {
            // *key=value: the elements of that kind, as a camp is.
            String at = string(start.get("at"));
            int equal = at == null ? -1 : at.indexOf('=');
            if (equal < 0) {
                throw new Unreachable("no element is named for the start");
            }
            for (Object item : components(rule)) {
                Map<String, Object> candidate = map(item);
                if (candidate != null && token(candidate.get(at.substring(1, equal))).equals(at.substring(equal + 1)) && !same(valueAt(candidate, field), rule.get("value"))) {
                    target = candidate;
                    break;
                }
            }
            if (target == null) {
                throw new Unreachable("no element " + at + " is free in " + path, true);
            }
        }
        if (same(valueAt(target, field), rule.get("value"))) {
            throw new Unreachable("a work is under way in #" + target.get(ID), true);
        }
        // The time is looked up on a copy first, from the fields the start puts in: a time the data does not give starts nothing.
        Map<String, Object> trial = castMap(copy(target));
        beginning(map(start.get("sets")), trial, command, time, -1);
        Long seconds = getTotalSeconds(rule, trial);
        if (seconds == null) {
            // UnitUpgradeComponent.StartUpgrading starts the unit even when GetTotalSeconds finds no level for it (0),
            // and SpeedUp finishes a unit held without a timer: the sets are applied, without an end, so the force
            // or a later login ends the work. The element is never left out of the work it was sent for.
            // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitUpgradeComponent.cs#GetTotalSeconds
            // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitUpgradeComponent.cs#SpeedUp
            // Not ported: GetTotalSeconds, SpeedUp (the time is getTotalSeconds above, SpeedUp is speedUp)
            beginning(map(start.get("sets")), target, command, time, -1);
            dirty = true;
            Server.LOG.info("Local game server: work started without a time: " + start.get("command") + " puts " + field + " " + rule.get("value") + " in " + path + " #" + target.get(ID) + ", the data gives none, a force or a login ends it");
            return;
        }
        long end = number(valueAt(trial, string(rule.get("since")))) + seconds.longValue();
        beginning(map(start.get("sets")), target, command, time, end);
        dirty = true;
        Server.LOG.info("Local game server: work started: " + start.get("command") + " puts " + field + " " + rule.get("value") + " in " + path + " #" + target.get(ID) + ", ends in " + seconds + " s");
        if (seconds.longValue() < 1) {
            clearingFinished(rule, components(rule), target, end);
        }
    }

    /** The fields a start puts in the element: a constant, "$optime" the command's time, "$end" the end, "$field" the command's own. */
    private static void beginning(Map<String, Object> sets, Map<String, Object> target, Map<String, Object> command, long time, long end) {
        for (Map.Entry<String, Object> entry : sets.entrySet()) {
            Object put;
            if ("$optime".equals(entry.getValue())) {
                put = typed(valueAt(target, entry.getKey()), time);
            } else if ("$end".equals(entry.getValue())) {
                if (end < 0) {
                    continue;
                }
                put = typed(valueAt(target, entry.getKey()), end);
            } else {
                put = value(entry.getValue(), command);
            }
            put(target, entry.getKey(), put);
        }
    }

    /** A work that removes its element ends at once, the diamonds being the economy's spend. */
    private void speedUpClearing(Map<String, Object> rule, Map<String, Object> end, Map<String, Object> command, long time) {
        Map<String, Object> target = underWay(rule, end, command);
        clearingFinished(rule, components(rule), target, time);
    }

    /** Any other work ends at once. */
    private void speedUp(Map<String, Object> rule, Map<String, Object> end, Map<String, Object> command, long time) {
        Map<String, Object> target = underWay(rule, end, command);
        finishUpgrading(rule, target, time);
    }

    /** A work cancelled: the element is idle again, the fields the end resets back to their constants, and the price of the start comes back. */
    private void cancelClearing(Map<String, Object> rule, Map<String, Object> end, Map<String, Object> command, long time) {
        Map<String, Object> target = underWay(rule, end, command);
        String field = string(rule.get("field"));
        if (rule.get("idle") == null) {
            throw new Unreachable("no idle value for " + field);
        }
        economy(string(map(rule.get("start")).get("command")), command, true);
        put(target, field, copy(rule.get("idle")));
        put(target, string(rule.get("since")), typed(valueAt(target, string(rule.get("since"))), time));
        for (Object then : list(rule.get("then"))) {
            Map<String, Object> op = map(then);
            String inner = op == null ? null : string(op.get("path"));
            if (inner != null && "set".equals(op.get("op")) && !"$end".equals(op.get("value")) && !inner.equals(field)) {
                put(target, inner, copy(op.get("value")));
            }
        }
        dirty = true;
    }

    /** The elements of the list a production rule works on. */
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/dex/java/io/gutapk/offline/GameServer.java#elapse
    // Not ported: elapse (the timers keep their loop, components is the part of it that reaches the list of the state)
    private List<Object> components(Map<String, Object> rule) {
        return list(new Patch(state, true, new LinkedHashMap<String, Object>()).evaluate(Pointer.parse(string(rule.get("list"))), false));
    }

    /** The camps: every element holding the key of the camp the rule learnt. */
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/dex/java/io/gutapk/offline/GameServer.java#elapse
    // Not ported: elapse (the timers keep their loop, campsOf is its walk over the elements of a list, with the key of the camp for its filter)
    private List<Map<String, Object>> campsOf(Map<String, Object> rule) {
        List<Map<String, Object>> camps = new ArrayList<Map<String, Object>>();
        Map<String, Object> kind = map(rule.get("camp"));
        for (Object item : components(rule)) {
            Map<String, Object> c = map(item);
            if (c != null && kind != null && same(c.get(string(kind.get("key"))), kind.get("value"))) {
                camps.add(c);
            }
        }
        return camps;
    }

    /** The units of an element, as the slots of its queue: the list is made when the element holds none. */
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/dex/java/io/gutapk/offline/GameServer.java#elapse
    // Not ported: elapse (the timers keep their loop, units reads and makes the list a timer's element holds)
    private List<Object> units(Map<String, Object> rule, Map<String, Object> component) {
        String queue = string(rule.get("queue"));
        if (!(valueAt(component, queue) instanceof List)) {
            put(component, queue, new ArrayList<Object>());
        }
        return castList(valueAt(component, queue));
    }

    /** The seconds a unit takes in the production, from the durations the PC found in the game's data. */
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/dex/java/io/gutapk/offline/GameServer.java#elapse
    // Not ported: elapse (the timers keep their loop, duration is the lookup of its durations by key)
    private static long duration(Map<String, Object> rule, Object unit) {
        Map<String, Object> durations = map(rule.get("durations"));
        Object seconds = durations == null ? null : durations.get(token(unit));
        if (!(seconds instanceof Number)) {
            throw new Unreachable("no duration for the unit " + unit);
        }
        return ((Number) seconds).longValue();
    }

    /**
     * The units of the queues whose time is up go to the camps, head first: the
     * next one ends one duration after the one before, as elapse ends a work.
     */
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitProductionComponent.cs#ProductionCompleted
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/dex/java/io/gutapk/offline/GameServer.java#elapse
    // Not ported: elapse (the timers keep their loop, tick is its twin for the queues), CanAddUnit (the capacity of a camp is not in the recordings)
    // Not ported: the m_vIsWaitingForSpace flag (a head with no camp is logged and tried again at the next request)
    private void tick(long now) {
        for (Object item : list(rules.get("production"))) {
            Map<String, Object> rule = map(item);
            String finish = rule == null ? null : string(rule.get("finish"));
            if (finish == null) {
                continue;
            }
            try {
                for (Object element : components(rule)) {
                    Map<String, Object> producer = map(element);
                    long end = producer == null ? 0 : number(valueAt(producer, finish));
                    while (end > 0 && end <= now && !units(rule, producer).isEmpty()) {
                        if (!productionCompleted(rule, producer, end)) {
                            break;
                        }
                        dirty = true;
                        end = number(valueAt(producer, finish));
                    }
                }
            } catch (Unreachable e) {
                skipped("production", "tick", string(rule.get("list")), e);
            }
        }
    }

    /**
     * The head of the queue ends at "end": the closest camp, by the position
     * fields, takes the unit, then the next head starts. Without a camp the unit
     * stays and the production waits.
     */
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitProductionComponent.cs#ProductionCompleted
    // Not ported: ComponentFilter, AddIgnoreObject, GetClosestComponent (the camps are listed and the nearest kept), CanAddUnit (no capacity in the recordings)
    private boolean productionCompleted(Map<String, Object> rule, Map<String, Object> producer, long end) {
        boolean result = false;
        List<Object> queue = units(rule, producer);
        Map<String, Object> head = queue.isEmpty() ? null : map(queue.get(0));
        Object unit = head == null ? null : head.get(string(rule.get("unit")));
        // The closest camp is the one with the least distance over the position fields, the first on a tie.
        Map<String, Object> c = null;
        long nearest = 0;
        for (Map<String, Object> camp : campsOf(rule)) {
            long distance = 0;
            for (String field : strings(map(rule.get("camp")).get("near"))) {
                long x = number(camp.get(field)) - number(producer.get(field));
                distance += x * x;
            }
            if (c == null || distance < nearest) {
                c = camp;
                nearest = distance;
            }
        }
        if (c != null && head != null) {
            addUnit(rule, c, unit, 1);
            startProducingNextUnit(rule, producer, end);
            result = true;
            Server.LOG.info("Local game server: unit " + unit + " of #" + producer.get(ID) + " moved to #" + c.get(ID));
        } else {
            Server.LOG.info("Local game server: unit " + unit + " of #" + producer.get(ID) + " waits, " + (head == null ? "nothing queued" : "no camp"));
        }
        return result;
    }

    /** The units of an element are sent at once, head first, while a camp takes them. */
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitProductionComponent.cs#SpeedUp
    // Not ported: HasHousingSpaceForSpeedUp (the housing is not in the recordings)
    private void speedUp(Map<String, Object> rule, Map<String, Object> producer, long now) {
        String finish = string(rule.get("finish"));
        while (!units(rule, producer).isEmpty()) {
            long end = number(valueAt(producer, finish));
            if (!productionCompleted(rule, producer, end > 0 ? end : now)) {
                break;
            }
        }
    }

    /**
     * A unit asked for goes to the slot of its unit, or to a new slot, and the
     * timer starts when none runs: it ends one duration after "time".
     */
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitProductionComponent.cs#AddUnitToProductionQueue
    // Not ported: CanAddUnitToQueue (the room of a barracks is not in the recordings), GetUnitUpgradeLevel (a unit has one duration here)
    private void addUnitToProductionQueue(Map<String, Object> rule, Map<String, Object> producer, Object unit, long amount, long time) {
        long trainingTime = duration(rule, unit);
        if (amount <= 0) {
            throw new Unreachable("nothing to train");
        }
        addUnit(rule, producer, unit, amount);
        // UCS starts the timer with a new slot only, a queue left with no timer is started too.
        String finish = string(rule.get("finish"));
        if (!(number(valueAt(producer, finish)) > 0)) {
            put(producer, finish, typed(valueAt(producer, finish), time + trainingTime));
        }
    }

    /** The timer stops, then the head that ended leaves its slot and the next head starts. */
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitProductionComponent.cs#ProductionCompleted
    // Not ported: StartProducingNextUnit as a method of its own class, it is the step ProductionCompleted calls
    private void startProducingNextUnit(Map<String, Object> rule, Map<String, Object> producer, long end) {
        String finish = string(rule.get("finish"));
        put(producer, finish, typed(valueAt(producer, finish), 0));
        List<Object> queue = units(rule, producer);
        if (!queue.isEmpty()) {
            removeUnit(rule, producer, map(queue.get(0)).get(string(rule.get("unit"))), end);
        }
    }

    /**
     * One unit less in its slot, the slot gone at zero. The next head starts at
     * the end of the one before; UCS starts it only when a slot is gone, the
     * recordings show one unit leaving each duration. An empty queue is idle.
     */
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitProductionComponent.cs#ProductionCompleted
    // Not ported: GetTotalCount, GetTotalRemainingSeconds, Save, Load (the state is the server's own document)
    private void removeUnit(Map<String, Object> rule, Map<String, Object> producer, Object unit, long end) {
        String finish = string(rule.get("finish"));
        List<Object> queue = units(rule, producer);
        for (int i = 0; i < queue.size(); i++) {
            Map<String, Object> slot = map(queue.get(i));
            if (slot != null && same(slot.get(string(rule.get("unit"))), unit)) {
                long left = number(slot.get(string(rule.get("count")))) - 1;
                if (left > 0) {
                    slot.put(string(rule.get("count")), typed(slot.get(string(rule.get("count"))), left));
                } else {
                    queue.remove(i);
                }
                break;
            }
        }
        if (!queue.isEmpty()) {
            put(producer, finish, typed(valueAt(producer, finish), end + duration(rule, map(queue.get(0)).get(string(rule.get("unit"))))));
            return;
        }
        // Idle, as the command that sends the queue leaves the element: its constants, else no timer.
        Map<String, Object> force = map(rule.get("force"));
        Map<String, Object> idle = force == null ? null : map(force.get("idle"));
        if (idle != null) {
            for (Map.Entry<String, Object> entry : idle.entrySet()) {
                put(producer, entry.getKey(), copy(entry.getValue()));
            }
        }
        if (idle == null || !idle.containsKey(finish)) {
            put(producer, finish, typed(valueAt(producer, finish), 0));
        }
    }

    /** Units go to a camp: the slot of their unit grows, else a slot is made. */
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitStorageComponent.cs#AddUnit
    // Not ported: AddUnitImpl, CanAddUnit, GetUnitTypeIndex, GetUsedCapacity, GetMaxCapacity (no capacity, no level in the recordings), SetUnitCount of the avatar (the avatar is the state itself)
    private void addUnit(Map<String, Object> rule, Map<String, Object> storage, Object unit, long count) {
        List<Object> queue = units(rule, storage);
        String unitField = string(rule.get("unit"));
        String countField = string(rule.get("count"));
        for (Object item : queue) {
            Map<String, Object> slot = map(item);
            if (slot != null && same(slot.get(unitField), unit)) {
                slot.put(countField, typed(slot.get(countField), number(slot.get(countField)) + count));
                return;
            }
        }
        Map<String, Object> slot = new LinkedHashMap<String, Object>();
        slot.put(unitField, copy(unit));
        slot.put(countField, typed(null, count));
        queue.add(slot);
    }

    /**
     * The production commands: the one that trains puts its units in the queue of
     * the element it names, the one that hurries sends the whole queue, the one
     * that ends a battle sets the camps to the units left.
     */
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitProductionComponent.cs#AddUnitToProductionQueue
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitProductionComponent.cs#SpeedUp
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitStorageComponent.cs#AddUnit
    // Not ported: RemoveUnits, RemoveUnitsImpl (the camps are set to the units left, not reduced)
    private void production(String name, Map<String, Object> command) {
        long now = System.currentTimeMillis() / 1000L;
        for (Object item : list(rules.get("production"))) {
            Map<String, Object> rule = map(item);
            if (rule == null) {
                continue;
            }
            Map<String, Object> train = map(rule.get("train"));
            Map<String, Object> force = map(rule.get("force"));
            Map<String, Object> left = map(rule.get("left"));
            try {
                if (train != null && name.equals(train.get("command"))) {
                    Map<String, Object> producer = element(string(rule.get("list")), command.get(string(train.get("element"))));
                    long time = command.get("optime") instanceof Number ? number(command.get("optime")) : now;
                    addUnitToProductionQueue(rule, producer, command.get(string(train.get("unit"))), number(command.get(string(train.get("count")))), time);
                    dirty = true;
                    Server.LOG.info("Local game server: " + command.get(string(train.get("count"))) + " of unit " + command.get(string(train.get("unit"))) + " queued in #" + producer.get(ID));
                } else if (force != null && name.equals(force.get("command"))) {
                    speedUp(rule, element(string(rule.get("list")), command.get(string(force.get("element")))), now);
                    dirty = true;
                } else if (left != null && name.equals(left.get("command"))) {
                    Object slots = valueAt(command, string(left.get("path")));
                    List<Map<String, Object>> camps = campsOf(rule);
                    if (!(slots instanceof List) || camps.isEmpty()) {
                        throw new Unreachable("no units left or no camp");
                    }
                    for (Map<String, Object> camp : camps) {
                        units(rule, camp).clear();
                    }
                    for (Object slot : castList(slots)) {
                        long count = number(map(slot) == null ? null : map(slot).get(string(left.get("count"))));
                        if (count > 0) {
                            addUnit(rule, camps.get(0), map(slot).get(string(left.get("unit"))), count);
                            Server.LOG.info("Local game server: " + count + " of unit " + map(slot).get(string(left.get("unit"))) + " left, kept in #" + camps.get(0).get(ID));
                        }
                    }
                    dirty = true;
                }
            } catch (Unreachable e) {
                skipped(name, "production", string(rule.get("list")), e);
            }
        }
    }

    /**
     * The economy the PC learnt: what a command adds to the player's resources
     * and takes from them. A collect moves what the game says it collected from
     * the producer to the storages, the producer keeping what does not fit and
     * starting again from now. A price is taken from the data the PC copied, by
     * the article a field names or by the next grade of the element named. A
     * resource of the wallet is taken from or added to it, any other one to the
     * storages, each up to its capacity at its grade.
     */
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/dex/java/io/gutapk/offline/GameServer.java#apply
    // Not ported: the ops set, append, increment and remove, Patch.put (an effect adds to totals or takes from them)
    private void economy(String name, Map<String, Object> command) {
        economy(name, command, false);
    }

    /**
     * The same, with refund: the prices a command took come back (the cancel of
     * a work gives back the price of its start), what it collected or gained
     * does not. An effect that fails for its own sake (a path nothing holds)
     * is dropped with one log line, one that fails for this request (an
     * element nobody holds) is skipped for this call only.
     */
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Obstacle.cs#CancelClearing
    // Not ported: GetClearingResource, ClearCost, CommodityCountChangeHelper (the price is the effects' own, the avatar is the state)
    private void economy(String name, Map<String, Object> command, boolean refund) {
        Map<String, Object> rule = map(rules.get("economy"));
        if (rule == null) {
            return;
        }
        for (Object item : new ArrayList<Object>(list(rule.get("effects")))) {
            Map<String, Object> effect = map(item);
            if (effect == null || !name.equals(effect.get("command")) || !matches(effect, command)) {
                continue;
            }
            String op = string(effect.get("op"));
            if (refund && !("pay".equals(op) || "payGrade".equals(op) || "spend".equals(op))) {
                continue;
            }
            try {
                if ("collect".equals(op)) {
                    Map<String, Object> target = element(string(effect.get("list")), command.get(string(effect.get("element"))));
                    List<String> producers = strings(map(effect.get("producers")).get(token(target.get(string(effect.get("key"))))));
                    long amount = number(command.get(string(effect.get("field"))));
                    for (String key : producers) {
                        Map<String, Object> parentNode = holder(target, key);
                        if (parentNode != null) {
                            parentNode.put(key, typed(parentNode.get(key), deposit(rule, resource(rule, key), amount)));
                        }
                    }
                    String since = string(effect.get("since"));
                    if (since != null && command.get("optime") instanceof Number) {
                        put(target, since, typed(valueAt(target, since), number(command.get("optime"))));
                    }
                } else if ("pay".equals(op)) {
                    Map<String, Object> price = map(map(effect.get("prices")).get(token(command.get(string(effect.get("field"))))));
                    if (price == null) {
                        throw new Unreachable("no price for " + command.get(string(effect.get("field"))), true);
                    }
                    long times = effect.get("times") == null ? 1 : number(command.get(string(effect.get("times"))));
                    for (Map.Entry<String, Object> entry : price.entrySet()) {
                        charge(rule, entry.getKey(), number(entry.getValue()) * times, refund);
                    }
                } else if ("payGrade".equals(op)) {
                    Map<String, Object> target = element(string(effect.get("list")), command.get(string(effect.get("element"))));
                    Object grade = target.get(string(effect.get("grade")));
                    long next = (grade instanceof Number ? number(grade) : number(effect.get("default"))) + 1;
                    Map<String, Object> price = map(map(effect.get("prices")).get(token(target.get(string(effect.get("key")))) + "," + next));
                    if (price == null) {
                        throw new Unreachable("no price for the grade " + next, true);
                    }
                    for (Map.Entry<String, Object> entry : price.entrySet()) {
                        charge(rule, entry.getKey(), number(entry.getValue()), refund);
                    }
                } else if ("spend".equals(op)) {
                    charge(rule, string(effect.get("resource")), number(command.get(string(effect.get("field")))), refund);
                } else if ("gain".equals(op)) {
                    for (Map.Entry<String, Object> entry : map(command.get(string(effect.get("field")))).entrySet()) {
                        deposit(rule, entry.getKey(), number(entry.getValue()));
                    }
                } else {
                    throw new Unreachable("unknown op");
                }
                dirty = true;
            } catch (Unreachable e) {
                String field = String.valueOf(effect.get("field") != null ? effect.get("field") : effect.get("element"));
                if (e.ofRequest || refund) {
                    skipped(name, "economy " + op, field, e);
                } else {
                    // Dropped: a rule that cannot be read fails alike every time, one line says so.
                    castList(rule.get("effects")).remove(item);
                    Server.LOG.warning("Local game server: economy " + op + " " + field + " of " + name + " skipped: " + e.getMessage() + ", dropped");
                }
            } catch (NullPointerException e) {
                String field = String.valueOf(effect.get("field") != null ? effect.get("field") : effect.get("element"));
                if (refund) {
                    skipped(name, "economy " + op, field, new Unreachable("nothing to read"));
                } else {
                    castList(rule.get("effects")).remove(item);
                    Server.LOG.warning("Local game server: economy " + op + " " + field + " of " + name + " skipped: nothing to read, dropped");
                }
            }
        }
    }

    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Obstacle.cs#CancelClearing
    // https://raw.githubusercontent.com/213YaZ786/GutapK/c52520a/src/dex/java/io/gutapk/offline/GameServer.java#elapse
    // Not ported: CancelClearing, elapse (cancelClearing and elapse stand above, this only takes the price of a resource or gives it back)
    /** A price taken from the resource, or given back to it when refunded. */
    private void charge(Map<String, Object> rule, String resource, long amount, boolean refund) {
        if (refund) {
            deposit(rule, resource, amount);
        } else {
            withdraw(rule, resource, amount);
        }
    }

    /**
     * A command the recordings never showed, kept as the state keeps such things.
     * A message named like the command keeps the request's numbers, in their order,
     * as one entry of its list, the entry with the same first number replaced, as
     * an append replaces the element with the same id: Jungle Heat's libgame.so
     * reads tutorial_progress/progress entries as [tutorialId, step], by index.
     * Else the element a rule names by one of its fields, in the list of a rule its
     * request matches, takes those of its fields it holds. Returns what was kept,
     * null when nothing was.
     */
    /**
     * The progress rules of a command: the entry of the answer a rule names, by
     * the id the latest request of its key command held (the mission attacked),
     * takes the values of this request (its stars), in the message of the state
     * named like that answer. The entry is the one the state kept, else the
     * recorded answer's.
     */
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/dex/java/io/gutapk/offline/GameServer.java#apply
    // Not ported: the ops set, append, increment and remove, Patch, Pointer (an entry is named by its first number, no path reaches it)
    private void progress(String name, Map<String, Object> command) {
        String field = string(rules.get("command"));
        for (Object item : list(rules.get("progress"))) {
            Map<String, Object> rule = map(item);
            if (rule == null || !name.equals(rule.get("command"))) {
                continue;
            }
            String answer = string(rule.get("answer"));
            String path = string(rule.get("path"));
            Map<String, Object> key = map(rule.get("key"));
            Map<String, Object> keyRequest = key == null ? null : sent.get(string(key.get("command")));
            Object id = keyRequest == null ? null : keyRequest.get(string(key.get("field")));
            if (answer == null || path == null || id == null) {
                Server.LOG.info("Local game server: " + name + " changes no entry of " + answer + ", no " + key + " was sent before it");
                continue;
            }
            Map<String, Object> message = message(answer);
            List<Object> entry = message == null ? null : entryOf(message.get(path), id);
            Map<String, Object> answers = map(rules.get("answers"));
            for (Object stub : answers == null ? new ArrayList<Object>() : list(answers.get(answer))) {
                if (entry == null && map(stub) != null && map(map(stub).get("answer")) != null) {
                    entry = entryOf(map(map(stub).get("answer")).get(path), id);
                }
            }
            if (entry == null) {
                Server.LOG.info("Local game server: " + name + " changes no entry of " + answer + ", none is " + id);
                continue;
            }
            entry = castList(copy(entry));
            try {
                for (Map.Entry<String, Object> value : castMap(rule.get("values")).entrySet()) {
                    int index = Integer.parseInt(value.getKey());
                    if (index < entry.size()) {
                        entry.set(index, value(value.getValue(), command));
                    }
                }
            } catch (Unreachable e) {
                skipped(name, "progress", answer + "/" + path, e);
                continue;
            }
            if (message == null) {
                message = new LinkedHashMap<String, Object>();
                message.put(field, answer);
                castList(state.get("messages")).add(message);
            }
            if (!(message.get(path) instanceof List)) {
                message.put(path, new ArrayList<Object>());
            }
            List<Object> entries = castList(message.get(path));
            List<Object> same = entryOf(entries, id);
            if (same == null) {
                entries.add(entry);
            } else {
                same.clear();
                same.addAll(entry);
            }
            dirty = true;
            Server.LOG.info("Local game server: " + name + " kept " + answer + "/" + path + " " + entry);
        }
    }

    /** An answer a progress rule changes: its entries take those the state kept. */
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/dex/java/io/gutapk/offline/GameServer.java#apply
    // Not ported: the ops set, append, increment and remove, Patch, Pointer (an entry is named by its first number, no path reaches it)
    private void entries(String name, Object body) {
        for (Object item : list(rules.get("progress"))) {
            Map<String, Object> rule = map(item);
            Map<String, Object> message = message(name);
            String path = rule == null ? null : string(rule.get("path"));
            if (path == null || message == null || !name.equals(rule.get("answer")) || map(body) == null) {
                continue;
            }
            List<Object> entries = list(map(body).get(path));
            for (Object kept : list(message.get(path))) {
                List<Object> same = kept instanceof List && !castList(kept).isEmpty() ? entryOf(entries, castList(kept).get(0)) : null;
                if (same != null) {
                    same.clear();
                    same.addAll(castList(copy(kept)));
                }
            }
        }
    }

    /** The entry of a list of lists whose first number is the id, null when none is. */
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/dex/java/io/gutapk/offline/GameServer.java#element
    // Not ported: Patch, Pointer (the list is at hand)
    private static List<Object> entryOf(Object entries, Object id) {
        for (Object item : list(entries)) {
            if (item instanceof List && !castList(item).isEmpty() && token(castList(item).get(0)).equals(token(id))) {
                return castList(item);
            }
        }
        return null;
    }

    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/dex/java/io/gutapk/offline/GameServer.java#apply
    // Not ported: the ops set, increment and remove, Patch, Pointer (the request's own fields are kept, no rule says where)
    private String keep(String field, String name, Map<String, Object> command) {
        deduced = false;
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        List<Object> numbers = new ArrayList<Object>();
        for (Map.Entry<String, Object> entry : command.entrySet()) {
            if (!entry.getKey().equals(field) && !ignored(entry.getKey())) {
                value.put(entry.getKey(), entry.getValue());
                if (entry.getValue() instanceof Number) {
                    numbers.add(entry.getValue());
                }
            }
        }
        if (value.isEmpty()) {
            return null;
        }
        // A request naming a document the state holds instances of (a hero unlocked)
        // adds its element, made as the PC found it in the game's data, as an append
        // adds an element with an id the list does not hold yet.
        // https://github.com/213YaZ786/GutapK/blob/c52520a/src/dex/java/io/gutapk/offline/GameServer.java#apply
        // Not ported: the ops set, increment and remove, the value of a rule (the element is made already)
        for (Object item : list(rules.get("instances"))) {
            String path = map(item) == null ? null : string(map(item).get("path"));
            for (Object entry : path == null ? new ArrayList<Object>() : list(map(item).get("entries"))) {
                Map<String, Object> element = map(entry);
                Object id = element == null ? null : element.get(ID);
                boolean named = false;
                for (Object number : numbers) {
                    named = named || token(number).equals(token(id));
                }
                if (id == null || !named) {
                    continue;
                }
                try {
                    Object parentNode = new Patch(state, true, new LinkedHashMap<String, Object>()).evaluate(Pointer.parse(path), false);
                    for (Object have : list(parentNode)) {
                        if (map(have) != null && token(map(have).get(ID)).equals(token(id))) {
                            // A held element takes the made one's shape: the fields the made
                            // one lacks are removed (a hero locked carries a restriction).
                            // https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/main/java/com/flipkart/zjsonpatch/InPlaceApplyProcessor.java#remove
                            // Not ported: the array and scalar cases, the flags (an object is at hand)
                            Map<String, Object> objectValue = map(have);
                            String fields = "";
                            for (String token : new ArrayList<String>(objectValue.keySet())) {
                                if (!element.containsKey(token)) {
                                    objectValue.remove(token);
                                    fields += ", without " + token;
                                    dirty = true;
                                }
                            }
                            return path + " #" + id + ", held already" + fields;
                        }
                    }
                    if (parentNode instanceof List) {
                        castList(parentNode).add(copy(element));
                        dirty = true;
                        return path + " #" + id;
                    }
                } catch (Unreachable e) {
                    skipped(name, "instance", path, e);
                }
            }
        }
        Map<String, Object> message = message(name);
        if (message != null && !numbers.isEmpty()) {
            for (Map.Entry<String, Object> entry : message.entrySet()) {
                if (!(entry.getValue() instanceof List)) {
                    continue;
                }
                List<Object> parentNode = castList(entry.getValue());
                for (int i = 0; i < parentNode.size(); i++) {
                    Object same = parentNode.get(i);
                    if (same instanceof List && !((List<?>) same).isEmpty() && same(((List<?>) same).get(0), numbers.get(0))) {
                        parentNode.set(i, numbers);
                        dirty = true;
                        return name + "/" + entry.getKey() + " " + numbers;
                    }
                }
                parentNode.add(numbers);
                dirty = true;
                return name + "/" + entry.getKey() + " " + numbers;
            }
        }
        // A request holding a field some rule holds for one value only (the base it is
        // for) is read with those rules alone.
        boolean conditioned = false;
        for (Object item : list(rules.get("rules"))) {
            Map<String, Object> when = map(item) == null ? null : map(map(item).get("when"));
            if (when != null && !Collections.disjoint(when.keySet(), command.keySet())) {
                conditioned = true;
            }
        }
        for (Object item : list(rules.get("rules"))) {
            Map<String, Object> rule = map(item);
            String path = rule == null ? null : string(rule.get("path"));
            int at = path == null ? -1 : path.indexOf("/#$");
            if (at < 0 || !matches(rule, command) || (conditioned && rule.get("when") == null)) {
                continue;
            }
            String id = path.substring(at + 3).split("/")[0];
            if (!command.containsKey(id)) {
                continue;
            }
            Map<String, Object> target;
            try {
                target = element(path.substring(0, at), command.get(id));
            } catch (Unreachable e) {
                continue;
            }
            List<String> kept = new ArrayList<String>();
            for (Map.Entry<String, Object> entry : value.entrySet()) {
                Object valueNode = target.get(entry.getKey());
                if (entry.getKey().equals(id) || valueNode == null) {
                    continue;
                }
                if (valueNode instanceof Number && entry.getValue() instanceof Number) {
                    target.put(entry.getKey(), typed(valueNode, number(entry.getValue())));
                } else if (valueNode instanceof String && entry.getValue() instanceof String) {
                    target.put(entry.getKey(), entry.getValue());
                } else {
                    continue;
                }
                kept.add(entry.getKey());
            }
            if (!kept.isEmpty()) {
                dirty = true;
                // MoveBuildingCommand sets the position the command carries: any command naming an element so is read alike.
                deduced = true;
                return path.substring(0, at) + " #" + command.get(id) + " " + kept;
            }
        }
        return null;
    }

    /** The element of a list of the state whose id is the one given. */
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/dex/java/io/gutapk/offline/GameServer.java#apply
    private Map<String, Object> element(String path, Object id) {
        Object parentNode = new Patch(state, true, new LinkedHashMap<String, Object>()).evaluate(Pointer.parse(path), false);
        for (Object item : list(parentNode)) {
            Map<String, Object> target = map(item);
            if (target != null && token(target.get(ID)).equals(token(id))) {
                return target;
            }
        }
        throw new Unreachable("no element " + id + " in " + path, true);
    }

    /** The document of an element holding a field, its parameters searched through. Null when it holds none. */
    private static Map<String, Object> holder(Object node, String key) {
        if (node instanceof Map) {
            Map<String, Object> document = castMap(node);
            if (document.get(key) instanceof Number) {
                return document;
            }
            for (Object value : document.values()) {
                Map<String, Object> found = holder(value, key);
                if (found != null) {
                    return found;
                }
            }
        } else if (node instanceof List) {
            for (Object value : (List<?>) node) {
                Map<String, Object> found = holder(value, key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The resource a field holds: the longest resource its name ends with after an underscore. */
    private static String resource(Map<String, Object> rule, String key) {
        String best = null;
        for (String name : strings(rule.get("resources"))) {
            if (key.endsWith("_" + name) && (best == null || name.length() > best.length())) {
                best = name;
            }
        }
        return best;
    }

    /** The wallet when it holds the resource, null when the storages do. */
    private Map<String, Object> wallet(Map<String, Object> rule, String resource) {
        String path = string(rule.get("wallet"));
        if (path == null) {
            return null;
        }
        try {
            Object node = new Patch(state, true, new LinkedHashMap<String, Object>()).evaluate(Pointer.parse(path), false);
            Map<String, Object> wallet = map(node);
            return wallet != null && wallet.get(resource) instanceof Number ? wallet : null;
        } catch (Unreachable e) {
            return null;
        }
    }

    /**
     * The storages of a resource, each with the field holding it and its
     * capacity at its grade, in the order of their lists.
     */
    private List<Object[]> storages(Map<String, Object> rule, String resource) {
        List<Object[]> out = new ArrayList<Object[]>();
        for (Object item : list(rule.get("storages"))) {
            Map<String, Object> storage = map(item);
            Map<String, Object> capacities = storage == null ? null : map(storage.get("capacities"));
            if (capacities == null) {
                continue;
            }
            Object parentNode;
            try {
                parentNode = new Patch(state, true, new LinkedHashMap<String, Object>()).evaluate(Pointer.parse(string(storage.get("list"))), false);
            } catch (Unreachable e) {
                continue;
            }
            for (Object element : list(parentNode)) {
                Map<String, Object> target = map(element);
                if (target == null) {
                    continue;
                }
                Object grade = target.get(string(storage.get("grade")));
                Map<String, Object> capacity = map(capacities.get(token(target.get(string(storage.get("key")))) + "," + token(grade == null ? storage.get("default") : grade)));
                if (capacity == null) {
                    continue;
                }
                for (Map.Entry<String, Object> entry : capacity.entrySet()) {
                    Map<String, Object> document = resource.equals(resource(rule, entry.getKey())) ? holder(target, entry.getKey()) : null;
                    if (document != null) {
                        out.add(new Object[] {document, entry.getKey(), Long.valueOf(number(entry.getValue()))});
                    }
                }
            }
        }
        return out;
    }

    /** Adds to a resource: the wallet, else the storages up to their capacity. Returns what did not fit. */
    private long deposit(Map<String, Object> rule, String resource, long amount) {
        Map<String, Object> wallet = wallet(rule, resource);
        if (wallet != null) {
            wallet.put(resource, typed(wallet.get(resource), number(wallet.get(resource)) + amount));
            return 0;
        }
        long left = amount;
        for (Object[] storage : storages(rule, resource)) {
            Map<String, Object> document = castMap(storage[0]);
            String key = (String) storage[1];
            long held = number(document.get(key));
            long added = Math.max(0, Math.min(left, ((Long) storage[2]).longValue() - held));
            document.put(key, typed(document.get(key), held + added));
            left -= added;
        }
        return left;
    }

    /** Takes from a resource: the wallet, else the storages in turn, none going below zero. */
    private void withdraw(Map<String, Object> rule, String resource, long amount) {
        Map<String, Object> wallet = wallet(rule, resource);
        if (wallet != null) {
            wallet.put(resource, typed(wallet.get(resource), Math.max(0, number(wallet.get(resource)) - amount)));
            return;
        }
        long left = amount;
        for (Object[] storage : storages(rule, resource)) {
            Map<String, Object> document = castMap(storage[0]);
            String key = (String) storage[1];
            long held = number(document.get(key));
            long taken = Math.min(left, held);
            document.put(key, typed(document.get(key), held - taken));
            left -= taken;
        }
    }

    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0;
    }

    /** A number in the type the state held there: an int32 while it fits. */
    private static Object typed(Object old, long value) {
        return !(old instanceof Long) && (int) value == value ? (Object) Integer.valueOf((int) value) : (Object) Long.valueOf(value);
    }

    /** The value at a path of a document, its segments joined by "/", null when it is not there. */
    private static Object valueAt(Map<String, Object> document, String path) {
        Object valueNode = document;
        for (String token : path.split("/")) {
            valueNode = valueNode instanceof Map ? castMap(valueNode).get(token) : null;
        }
        return valueNode;
    }

    /** Sets the value at a path of a document, making the documents on its way. */
    private static void put(Map<String, Object> document, String path, Object value) {
        String[] tokens = path.split("/");
        Map<String, Object> parentNode = document;
        for (int i = 0; i < tokens.length - 1; i++) {
            if (!(parentNode.get(tokens[i]) instanceof Map)) {
                parentNode.put(tokens[i], new LinkedHashMap<String, Object>());
            }
            parentNode = castMap(parentNode.get(tokens[i]));
        }
        parentNode.put(tokens[tokens.length - 1], value);
    }

    /** A key segment: a number written without its width, as the PC writes the durations. */
    private static String token(Object value) {
        return value instanceof Number && !(value instanceof Double) ? String.valueOf(((Number) value).longValue()) : String.valueOf(value);
    }

    private static void skipped(String command, String op, String path, Unreachable why) {
        Server.LOG.warning("Local game server: " + op + " " + path + " of " + command + " skipped: " + why.getMessage());
    }

    /** Sets a path of an answer to the time now, in the type the answer holds there. */
    private void now(Object answer, String path) {
        Pointer pointer = Pointer.parse(path);
        if (pointer.tokens.size() < 2) {
            throw new Unreachable("a path names a field of a document");
        }
        Patch patch = new Patch(answer, false, new LinkedHashMap<String, Object>());
        Object seconds = value("$now", null);
        Object valueNode = patch.has(pointer) ? patch.get(pointer) : null;
        if (valueNode instanceof Long) {
            seconds = Long.valueOf(((Number) seconds).longValue());
        } else if (valueNode instanceof Double) {
            seconds = Double.valueOf(((Number) seconds).doubleValue());
        }
        patch.put(pointer, seconds);
    }

    /** a + b, an Integer when both are Integers and the sum fits, else a Long, or a Double when one is. */
    static Object sum(Object a, Object b) {
        if (!(a instanceof Number) || !(b instanceof Number)) {
            throw new Unreachable("not a number");
        }
        if (a instanceof Double || b instanceof Double) {
            return Double.valueOf(((Number) a).doubleValue() + ((Number) b).doubleValue());
        }
        long total = ((Number) a).longValue() + ((Number) b).longValue();
        if (a instanceof Integer && b instanceof Integer && (int) total == total) {
            return Integer.valueOf((int) total);
        }
        return Long.valueOf(total);
    }

    /**
     * A value of a rule: "$field" is the command's field with its BSON type,
     * "$now" the unix seconds, a document or list is copied with its "$..."
     * strings replaced. A field the command does not have makes the rule
     * unreachable.
     */
    static Object value(Object value, Map<String, Object> command) {
        if (value instanceof String && ((String) value).startsWith("$")) {
            String name = ((String) value).substring(1);
            if ("now".equals(name)) {
                long seconds = System.currentTimeMillis() / 1000L;
                return (int) seconds == seconds ? (Object) Integer.valueOf((int) seconds) : (Object) Long.valueOf(seconds);
            }
            if (command == null || !command.containsKey(name)) {
                throw new Unreachable("the command has no field " + name);
            }
            return copy(command.get(name));
        }
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> entry : castMap(value).entrySet()) {
                out.put(entry.getKey(), value(entry.getValue(), command));
            }
            return out;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<Object>();
            for (Object item : castList(value)) {
                out.add(value(item, command));
            }
            return out;
        }
        return copy(value);
    }

    /** A deep copy of a tree of maps and lists. */
    static Object copy(Object value) {
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> entry : castMap(value).entrySet()) {
                out.put(entry.getKey(), copy(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<Object>();
            for (Object item : castList(value)) {
                out.add(copy(item));
            }
            return out;
        }
        if (value instanceof byte[]) {
            return ((byte[]) value).clone();
        }
        return value;
    }

    /** Two values are the same when equal, a number of any width equal to another, or equal as text when one is a text (an id from a path). */
    static boolean same(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number && b instanceof Number) {
            if (a instanceof Double || b instanceof Double) {
                return ((Number) a).doubleValue() == ((Number) b).doubleValue();
            }
            return ((Number) a).longValue() == ((Number) b).longValue();
        }
        if (a instanceof String || b instanceof String) {
            return String.valueOf(a).equals(String.valueOf(b));
        }
        return a.equals(b);
    }

    // https://github.com/EKA2L1/EKA2L1/blob/6ad873a07ee3d745cd17179026dca247f6d11f6b/src/emu/android/app/src/main/java/com/github/eka2l1/util/FileUtils.java#copyAssetFile
    /** The state written aside and renamed, so a stop half way leaves no truncated file. */
    private void save() throws IOException {
        folder.mkdirs();
        File aside = new File(folder, STATE + ".tmp");
        FileOutputStream out = new FileOutputStream(aside);
        try {
            out.write(Bson.encode(state));
        } finally {
            out.close();
        }
        Store.replace(aside, new File(folder, STATE));
        dirty = false;
    }

    // --- Typed reads of a tree decoded from BSON

    private static String string(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? castMap(value) : null;
    }

    private static List<Object> list(Object value) {
        return value instanceof List ? castList(value) : new ArrayList<Object>();
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<String>();
        for (Object item : list(value)) {
            if (item instanceof String) {
                out.add((String) item);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return (List<Object>) value;
    }
}
