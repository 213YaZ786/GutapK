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

        Unreachable(String message) {
            super(message);
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
            // The works whose time is up end first, so a login shows them done.
            elapse(System.currentTimeMillis() / 1000L);
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
        Object body;
        String type = DEFAULT_TYPE;
        if (matchingStub == null) {
            Map<String, Object> notConfigured = new LinkedHashMap<String, Object>();
            notConfigured.put(field, STATUS);
            body = notConfigured;
            // As WireMock's journal keeps the requests no stub matched: each one with its
            // fields, so what the game sends can be read and learnt later.
            if (!known(name)) {
                String kept = keep(field, name, request);
                Server.LOG.info("Local game server: unknown command " + name + " " + request + ", answered " + STATUS + (kept == null ? "" : ", kept in " + kept));
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
        for (Object item : list(rules.get("answerRules"))) {
            Map<String, Object> rule = map(item);
            if (rule != null && name.equals(rule.get("command"))) {
                apply(rule, body, false, request);
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
        for (String key : new String[] {"rules", "answerRules"}) {
            for (Object item : list(rules.get(key))) {
                Map<String, Object> rule = map(item);
                if (rule != null && name.equals(rule.get("command"))) {
                    return true;
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

        /** The first segment: a message of the state, or the first document of the answer with that name, else a key of the answer. */
        private Object first(String token) {
            if (fromState) {
                Map<String, Object> valueNode = message(token);
                if (valueNode == null) {
                    throw new Unreachable("no message " + token);
                }
                return valueNode;
            }
            Object valueNode = named(target, token);
            if (valueNode == null && target instanceof Map && castMap(target).get(token) != null) {
                valueNode = castMap(target).get(token);
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
            if (pointer.tokens.size() < 2) {
                throw new Unreachable("a path names a field of a message or a document");
            }
            Patch patch = new Patch(target, fromState, command);
            if ("set".equals(op)) {
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
     * The timers of the rules: an element of a list whose field holds the value
     * of a work under way (an upgrade) ends it once its time is up, the time
     * looked up by the element's key in the durations the PC found in the game's
     * data. The end is applied to the element: a number up, the field set back,
     * the time set to the end.
     */
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/dex/java/io/gutapk/offline/GameServer.java#apply
    // not ported: the ops append and remove (a timer ends a work, it adds or removes nothing)
    private void elapse(long now) {
        for (Object item : list(rules.get("timers"))) {
            Map<String, Object> rule = map(item);
            String path = rule == null ? null : string(rule.get("list"));
            String field = rule == null ? null : string(rule.get("field"));
            String since = rule == null ? null : string(rule.get("since"));
            Map<String, Object> durations = rule == null ? null : map(rule.get("durations"));
            List<String> key = rule == null ? new ArrayList<String>() : strings(rule.get("key"));
            if (path == null || field == null || since == null || durations == null || key.size() != 2) {
                continue;
            }
            Object parentNode;
            try {
                parentNode = new Patch(state, true, new LinkedHashMap<String, Object>()).evaluate(Pointer.parse(path), false);
            } catch (Unreachable e) {
                continue;
            }
            for (Object element : list(parentNode)) {
                Map<String, Object> target = map(element);
                if (target == null || !same(valueAt(target, field), rule.get("value")) || !(valueAt(target, since) instanceof Number)) {
                    continue;
                }
                Object grade = valueAt(target, key.get(1));
                Object duration = durations.get(token(valueAt(target, key.get(0))) + "," + token(grade == null ? rule.get("default") : grade));
                if (!(duration instanceof Number)) {
                    continue;
                }
                long end = ((Number) valueAt(target, since)).longValue() + ((Number) duration).longValue();
                if (end > now) {
                    continue;
                }
                for (Object then : list(rule.get("then"))) {
                    Map<String, Object> op = map(then);
                    String inner = op == null ? null : string(op.get("path"));
                    if (inner == null) {
                        continue;
                    }
                    if ("increment".equals(op.get("op"))) {
                        Object valueNode = valueAt(target, inner);
                        put(target, inner, sum(valueNode instanceof Number ? valueNode : rule.get("default"), Integer.valueOf(1)));
                    } else if ("$end".equals(op.get("value"))) {
                        put(target, inner, (int) end == end ? (Object) Integer.valueOf((int) end) : (Object) Long.valueOf(end));
                    } else {
                        put(target, inner, copy(op.get("value")));
                    }
                }
                dirty = true;
                Server.LOG.info("Local game server: " + field + " " + rule.get("value") + " of " + path + " #" + target.get(ID) + " ended");
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
        Map<String, Object> rule = map(rules.get("economy"));
        if (rule == null) {
            return;
        }
        for (Object item : list(rule.get("effects"))) {
            Map<String, Object> effect = map(item);
            if (effect == null || !name.equals(effect.get("command")) || !matches(effect, command)) {
                continue;
            }
            String op = string(effect.get("op"));
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
                    long times = effect.get("times") == null ? 1 : number(command.get(string(effect.get("times"))));
                    for (Map.Entry<String, Object> entry : price.entrySet()) {
                        withdraw(rule, entry.getKey(), number(entry.getValue()) * times);
                    }
                } else if ("payGrade".equals(op)) {
                    Map<String, Object> target = element(string(effect.get("list")), command.get(string(effect.get("element"))));
                    Object grade = target.get(string(effect.get("grade")));
                    long next = (grade instanceof Number ? number(grade) : number(effect.get("default"))) + 1;
                    Map<String, Object> price = map(map(effect.get("prices")).get(token(target.get(string(effect.get("key")))) + "," + next));
                    for (Map.Entry<String, Object> entry : price.entrySet()) {
                        withdraw(rule, entry.getKey(), number(entry.getValue()));
                    }
                } else if ("spend".equals(op)) {
                    withdraw(rule, string(effect.get("resource")), number(command.get(string(effect.get("field")))));
                } else if ("gain".equals(op)) {
                    for (Map.Entry<String, Object> entry : map(command.get(string(effect.get("field")))).entrySet()) {
                        deposit(rule, entry.getKey(), number(entry.getValue()));
                    }
                } else {
                    throw new Unreachable("unknown op");
                }
                dirty = true;
            } catch (Unreachable e) {
                skipped(name, "economy " + op, String.valueOf(effect.get("field") != null ? effect.get("field") : effect.get("element")), e);
            } catch (NullPointerException e) {
                skipped(name, "economy " + op, String.valueOf(effect.get("field")), new Unreachable("nothing to read"));
            }
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
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/dex/java/io/gutapk/offline/GameServer.java#apply
    // Not ported: the ops set, increment and remove, Patch, Pointer (the request's own fields are kept, no rule says where)
    private String keep(String field, String name, Map<String, Object> command) {
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
        throw new Unreachable("no element " + id + " in " + path);
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
