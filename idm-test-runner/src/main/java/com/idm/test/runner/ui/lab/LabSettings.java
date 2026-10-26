package com.idm.test.runner.ui.lab;

import com.idm.test.connector.ldap.client.LdapDirectories;
import com.idm.test.engine.ScenarioLoader;
import com.idm.test.engine.i18n.Messages;
import com.idm.test.engine.model.Scenario;
import com.idm.test.engine.result.Secrets;
import com.idm.test.engine.spi.Message;
import com.idm.test.runner.SecretMask;
import com.idm.test.runner.SecretsEnv;
import com.idm.test.runner.UsageException;
import com.idm.test.runner.Workspace;
import com.idm.test.runner.ui.WorkspaceFiles;
import com.idm.test.runner.ui.api.ApiError;
import com.idm.test.wiring.DirectoryScenarioSource;
import com.idm.test.wiring.RealEnvironmentGuards;
import com.idm.test.wiring.ScenarioNeeds;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The lab panel's settings: which systems a workspace needs (those its scenarios use, {@link ScenarioNeeds},
 * and those already configured), each one's fields as {@code idmtest run} would resolve them, saving them
 * (plain values to {@code config/application-<profile>.yml}, secrets to {@code secrets.env}, referenced as
 * {@code ${NAME}}), and testing them. Settings resolve as the runner's do: an environment variable of the
 * property's name ({@code IDM_TEST_LDAP_URL}) first, then the profile file, then {@code config/application.yml};
 * {@code ${NAME}} in a value reads the environment, then {@code secrets.env}.
 * <p>
 * A secret's value is never returned: fields carry {@code "set"}, {@code "not set"}, {@code "environment"}
 * or {@code "application.yml"} only, and every text is masked with the known secret values. A system is
 * {@code ready} when a connection test passed, in this server's lifetime, for exactly its current values —
 * remembered by a SHA-256 of the system and its values, held in memory only. Reading never writes a file.
 * <p>
 * A bean of the workbench's context, made by {@link com.idm.test.runner.ui.UiCommand} with the environment
 * the server was started with (tests pass their own).
 */
public final class LabSettings {

    public static final String SET = "set";
    public static final String NOT_SET = "not set";
    public static final String ENVIRONMENT = "environment";
    public static final String APPLICATION_YML = "application.yml";

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern EXACT_REFERENCE = Pattern.compile("^\\$\\{([^}:]+)(?::[^}]*)?}$");
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([^}:]+)");
    private static final String REGISTRATION = "spring.security.oauth2.client.registration.";
    private static final String PROVIDER = "spring.security.oauth2.client.provider.";

    private final Map<String, String> environment;
    /** The last test result per fingerprint of a system and its values; in memory only, and bounded. */
    private final Map<String, ConnectionTester.Result> results = new ConcurrentHashMap<>();
    private static final int MAX_RESULTS = 1000;

    /** @param environment the server's environment variables (System.getenv() in production) */
    public LabSettings(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
    }

    // --- systems and fields ---------------------------------------------------------------------

    public enum Kind {
        LDAP, DATABASE, REST, SOAP;

        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A field of a system's form: its key (the binding key under the system's prefix) and how it is shown. */
    record FieldSpec(String key, String labelKey, boolean secret, boolean oauth) {
    }

    private static final List<FieldSpec> LDAP_FIELDS = List.of(
            new FieldSpec("label", "runner.ui.lab.field.label", false, false),
            new FieldSpec("url", "runner.ui.lab.field.url", false, false),
            new FieldSpec("base-dn", "runner.ui.lab.field.baseDn", false, false),
            new FieldSpec("user-dn", "runner.ui.lab.field.userDn", false, false),
            new FieldSpec("password", "runner.ui.lab.field.password", true, false));
    private static final List<FieldSpec> DATABASE_FIELDS = List.of(
            new FieldSpec("label", "runner.ui.lab.field.label", false, false),
            new FieldSpec("jdbc-url", "runner.ui.lab.field.jdbcUrl", false, false),
            new FieldSpec("username", "runner.ui.lab.field.username", false, false),
            new FieldSpec("password", "runner.ui.lab.field.password", true, false),
            new FieldSpec("driver-class-name", "runner.ui.lab.field.driverClassName", false, false));
    private static final List<FieldSpec> REST_FIELDS = List.of(
            new FieldSpec("label", "runner.ui.lab.field.label", false, false),
            new FieldSpec("base-url", "runner.ui.lab.field.baseUrl", false, false),
            new FieldSpec("username", "runner.ui.lab.field.username", false, false),
            new FieldSpec("password", "runner.ui.lab.field.password", true, false),
            new FieldSpec("use-oauth", "runner.ui.lab.field.useOauth", false, false),
            new FieldSpec("oauth-client-name", "runner.ui.lab.field.oauthClientName", false, false),
            new FieldSpec("client-id", "runner.ui.lab.field.clientId", false, true),
            new FieldSpec("client-secret", "runner.ui.lab.field.clientSecret", true, true),
            new FieldSpec("token-uri", "runner.ui.lab.field.tokenUri", false, true),
            new FieldSpec("scope", "runner.ui.lab.field.scope", false, true));
    private static final List<FieldSpec> SOAP_FIELDS = List.of(
            new FieldSpec("label", "runner.ui.lab.field.label", false, false),
            new FieldSpec("url", "runner.ui.lab.field.roleServiceUrl", false, false),
            new FieldSpec("resource-url", "runner.ui.lab.field.resourceServiceUrl", false, false),
            new FieldSpec("username", "runner.ui.lab.field.username", false, false),
            new FieldSpec("password", "runner.ui.lab.field.password", true, false));

    /**
     * A lab system by id: {@code ldap} (the {@code idm.test.ldap} block), {@code directory:<name>},
     * {@code database:<name>}, {@code rest:<name>} or {@code soap}. Names are letters, digits, {@code -}
     * and {@code _}.
     */
    public record SystemRef(String id, Kind kind, String name) {

        /** @throws IllegalArgumentException when {@code id} names no system */
        public static SystemRef parse(String id) {
            if (id == null) {
                throw new IllegalArgumentException("no system id");
            }
            if (id.equals("ldap")) {
                return new SystemRef(id, Kind.LDAP, null);
            }
            if (id.equals("soap")) {
                return new SystemRef(id, Kind.SOAP, null);
            }
            int colon = id.indexOf(':');
            String name = colon < 0 ? "" : id.substring(colon + 1);
            if (colon < 0 || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("not a system id: " + id);
            }
            return switch (id.substring(0, colon)) {
                case "directory" -> new SystemRef(id, Kind.LDAP, name);
                case "database" -> new SystemRef(id, Kind.DATABASE, name);
                case "rest" -> new SystemRef(id, Kind.REST, name);
                default -> throw new IllegalArgumentException("not a system id: " + id);
            };
        }

        /** The prefix of the system's own settings, e.g. {@code idm.test.databases.hr}. */
        public String prefix() {
            return switch (kind) {
                case LDAP -> name == null ? "idm.test.ldap" : "idm.test.directories." + name;
                case DATABASE -> "idm.test.databases." + name;
                case REST -> "idm.test.rest." + name;
                case SOAP -> "idm.test.soap";
            };
        }

        List<FieldSpec> fields() {
            return switch (kind) {
                case LDAP -> LDAP_FIELDS;
                case DATABASE -> DATABASE_FIELDS;
                case REST -> REST_FIELDS;
                case SOAP -> SOAP_FIELDS;
            };
        }

        FieldSpec field(String key) {
            return fields().stream().filter(f -> f.key().equals(key)).findFirst().orElse(null);
        }

        /**
         * The property a field is bound from; an OAuth field lives under its registration
         * ({@code spring.security.oauth2.client.registration.<client>.client-id}, the provider's
         * {@code token-uri}), so it has none while no client name is known.
         */
        String property(String key, String oauthClient) {
            FieldSpec field = field(key);
            if (field == null || !field.oauth()) {
                return prefix() + "." + key;
            }
            if (oauthClient == null || oauthClient.isBlank()) {
                return null;
            }
            return key.equals("token-uri") ? PROVIDER + oauthClient + ".token-uri" : REGISTRATION + oauthClient + "." + key;
        }

        private Message displayName() {
            return switch (kind) {
                case LDAP -> name == null ? Message.of("runner.ui.lab.name.ldap") : Message.of("runner.ui.lab.name.directory", name);
                case DATABASE -> Message.of("runner.ui.lab.name.database", name);
                case REST -> Message.of("runner.ui.lab.name.rest", name);
                case SOAP -> Message.of("runner.ui.lab.name.soap");
            };
        }

        private int order() {
            return switch (kind) {
                case LDAP -> name == null ? 0 : 1;
                case DATABASE -> 2;
                case REST -> 3;
                case SOAP -> 4;
            };
        }
    }

    // --- the JSON -------------------------------------------------------------------------------

    /** A field as the lab panel shows it; {@code value} is always null for a secret. */
    public record Field(String key, String label, String value, boolean secret, String state, boolean required) {
    }

    public record Deletion(boolean allowed, String reason, List<String> files) {
    }

    public record SystemView(String id, String kind, String name, List<String> usedByScenarios, List<Field> fields,
                             String status, ConnectionTester.Result lastTest, Deletion deletion) {
    }

    /**
     * The default directory, when a run would refuse the scenarios over it: steps without {@code directory:}
     * and no default the runner can pick ({@code missing}: unset, and several directories), or a configured
     * {@code idm.test.default-directory} that names none of the directories ({@code invalid}) while any
     * ldap.* step is used — a run checks it before any step. {@code usedByScenarios} are the scenarios with
     * steps without {@code directory:}, or, when there are none, those with any ldap.* step; {@code choices}
     * the directories to choose from (sorted, as the runner names them); {@code value} the configured name
     * (null when unset) and {@code source} where it comes from: {@code profile}, {@code application.yml} or
     * {@code environment} (null when unset).
     */
    public record DefaultDirectory(List<String> usedByScenarios, List<String> choices, String value, String source,
                                   String state) {
    }

    /** The lab: its systems, and {@code defaultDirectory} null unless one has to be chosen. */
    public record View(List<SystemView> systems, DefaultDirectory defaultDirectory) {
    }

    // --- reading --------------------------------------------------------------------------------

    /** The workspace's settings files for a profile and its secrets, read once. */
    private record Snapshot(Workspace workspace, String profile, String profileText, Map<String, String> profileFlat,
                            Map<String, String> sharedFlat, Map<String, String> secrets) {
    }

    /** A setting as the runner would resolve it: its effective value, what the files say, and its state. */
    private record Resolved(String value, String shown, String state, String reference) {
    }

    /** The lab view of {@code workspace} for {@code profile}. Reads only. */
    public View read(Path workspace, String profile, Locale locale) {
        Snapshot snapshot = snapshot(workspace, profile);
        SecretMask mask = mask(snapshot, List.of());
        Map<String, Set<String>> users = new LinkedHashMap<>();
        Set<String> needDefault = new TreeSet<>();
        Set<String> ldapUsers = new TreeSet<>();
        DefaultChoice defaultChoice = defaultChoice(snapshot);
        ScenarioReferenceScan.Inspection deletionScan = ScenarioReferenceScan.inspect(snapshot.workspace().scenarios());
        boolean[] soapUse = new boolean[2];
        for (Map.Entry<String, ScenarioNeeds> entry : scenarioNeeds(snapshot).entrySet()) {
            ScenarioNeeds needs = entry.getValue();
            if (!needs.directories().isEmpty()) {
                ldapUsers.add(entry.getKey());
            }
            for (String directory : needs.directories()) {
                if (!ScenarioNeeds.DEFAULT_DIRECTORY.equals(directory)) {
                    use(users, directoryId(snapshot, directory), entry.getKey());
                } else if (defaultChoice.systemId() != null) {
                    use(users, defaultChoice.systemId(), entry.getKey());
                } else {
                    // the runner refuses this run (runner.guard.noDefaultDirectory): ask for the default
                    needDefault.add(entry.getKey());
                }
            }
            needs.databases().forEach(db -> use(users, "database:" + db, entry.getKey()));
            needs.restTargets().forEach(target -> use(users, "rest:" + target, entry.getKey()));
            if (needs.soap()) {
                use(users, "soap", entry.getKey());
                soapUse[0] |= needs.roles();
                soapUse[1] |= needs.resources();
            }
        }
        configuredSystems(snapshot).forEach(id -> users.computeIfAbsent(id, k -> new TreeSet<>()));

        Map<String, SystemRef> systems = new TreeMap<>();
        for (String id : users.keySet()) {
            try {
                SystemRef ref = SystemRef.parse(id);
                systems.put(ref.order() + ":" + (ref.name() == null ? "" : ref.name()), ref);
            } catch (IllegalArgumentException e) {
                // a name the lab panel cannot address (e.g. with a dot): left out
            }
        }
        List<SystemView> views = new ArrayList<>();
        for (SystemRef ref : systems.values()) {
            views.add(view(snapshot, ref, new ArrayList<>(users.get(ref.id())), soapUse, locale, mask,
                    defaultChoice, deletionScan));
        }
        // a run fails to start on an invalid default-directory (the directories are built up front), even
        // when every step names its directory or no scenario has an ldap.* step: then usedByScenarios may be empty
        boolean invalid = "invalid".equals(defaultChoice.state());
        Set<String> refused = !needDefault.isEmpty() ? needDefault : invalid ? ldapUsers : Set.of();
        DefaultDirectory defaultDirectory = refused.isEmpty() && !invalid ? null : new DefaultDirectory(
                List.copyOf(refused), defaultChoice.choices(), mask.apply(defaultChoice.value()),
                defaultChoice.source(), defaultChoice.state());
        return new View(views, defaultDirectory);
    }

    private static void use(Map<String, Set<String>> users, String id, String scenario) {
        users.computeIfAbsent(id, k -> new TreeSet<>()).add(scenario);
    }

    private SystemView view(Snapshot snapshot, SystemRef ref, List<String> usedBy, boolean[] soapUse, Locale locale,
                            SecretMask mask, DefaultChoice defaultChoice, ScenarioReferenceScan.Inspection deletionScan) {
        Map<String, Resolved> resolved = resolveAll(snapshot, ref);
        Map<String, String> effective = new LinkedHashMap<>();
        resolved.forEach((key, r) -> effective.put(key, r.value()));
        Set<String> required = required(ref, effective, soapUse);
        List<Field> fields = new ArrayList<>();
        boolean missing = false;
        for (FieldSpec spec : ref.fields()) {
            Resolved r = resolved.get(spec.key());
            boolean needed = required.contains(spec.key());
            if (needed && blank(r.value())) {
                missing = true;
            }
            String shown = spec.secret() ? null : mask.apply(r.shown());
            fields.add(new Field(spec.key(), Messages.render(Message.of(spec.labelKey()), locale), shown, spec.secret(),
                    r.state(), needed));
        }
        ConnectionTester.Result last = results.get(fingerprint(snapshot.workspace().root(), ref, effective));
        String status = missing ? "missing" : last != null && last.ok() ? "ready" : "untested";
        String display = blank(effective.get("label")) ? Messages.render(ref.displayName(), locale) : mask.apply(effective.get("label"));
        return new SystemView(ref.id(), ref.kind().json(), display, usedBy, fields,
                status, last, deletion(snapshot, ref, defaultChoice, deletionScan));
    }

    private Deletion deletion(Snapshot snapshot, SystemRef ref, DefaultChoice defaultChoice,
                              ScenarioReferenceScan.Inspection inspection) {
        String prefix = ProfileYamlEditor.uniform(ref.prefix() + ".");
        boolean owned = snapshot.profileFlat().keySet().stream()
                .anyMatch(key -> ProfileYamlEditor.uniform(key).startsWith(prefix));
        if (!owned) return new Deletion(false, "not-configured", List.of());
        boolean shared = snapshot.sharedFlat().keySet().stream()
                .anyMatch(key -> ProfileYamlEditor.uniform(key).startsWith(prefix));
        if (shared) return new Deletion(false, "shared", List.of());
        String envPrefix = ref.prefix().toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_') + "_";
        if (environment.keySet().stream().anyMatch(key -> key.startsWith(envPrefix))) {
            return new Deletion(false, "environment", List.of());
        }
        Path file = snapshot.workspace().profileFile(snapshot.profile());
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(file)
                    || !file.toRealPath().startsWith(snapshot.workspace().root().toRealPath())) {
                return new Deletion(false, "read-only", List.of());
            }
        } catch (IOException e) {
            return new Deletion(false, "read-only", List.of());
        }
        boolean configuredDefault = ref.kind() == Kind.LDAP && defaultChoice.value() != null
                && (defaultChoice.value().equals(ref.name()) || ref.id().equals("ldap")
                    && defaultChoice.value().equals("default") && !directoryNames(snapshot).contains("default"));
        if (configuredDefault) {
            return new Deletion(false, "default-directory", List.of());
        }
        ScenarioReferenceScan.Result scan = ScenarioReferenceScan.scan(inspection, ref, defaultChoice.systemId());
        if (!scan.referenced().isEmpty()) return new Deletion(false, "referenced", scan.referenced());
        if (!scan.uncertain().isEmpty()) return new Deletion(false, "invalid-scenario", scan.uncertain());
        return new Deletion(true, null, List.of());
    }

    /** Rechecks dependencies while holding the profile-file lock, then removes just this connection block. */
    public void delete(Path root, String profile, String systemId) {
        SystemRef ref = system(systemId);
        Path file = new Workspace(root).profileFile(profile);
        if (Files.isSymbolicLink(file)) {
            throw ApiError.badRequest("runner.ui.lab.deleteBlocked", systemId, "read-only", "");
        }
        try {
            WorkspaceFiles.locked(file, () -> {
                Snapshot snapshot = snapshot(root, profile);
                Deletion gate = deletion(snapshot, ref, defaultChoice(snapshot),
                        ScenarioReferenceScan.inspect(snapshot.workspace().scenarios()));
                if (!gate.allowed()) {
                    throw ApiError.badRequest("runner.ui.lab.deleteBlocked", systemId, gate.reason(),
                            String.join(", ", gate.files()));
                }
                ProfileYamlEditor.removeSubtree(file, ref.prefix(), snapshot.profileText());
                return null;
            });
        } catch (ProfileYamlEditor.CannotEditException e) {
            throw ApiError.badRequest("runner.ui.lab.deleteBlocked", systemId, "changed", "");
        } catch (IOException e) {
            throw ApiError.badRequest("runner.ui.lab.deleteBlocked", systemId, "read-only", "");
        }
    }

    /** The fields a system needs: its URL, the OAuth client when it uses OAuth, a password when a user is given. */
    private static Set<String> required(SystemRef ref, Map<String, String> effective, boolean[] soapUse) {
        Set<String> required = new LinkedHashSet<>();
        switch (ref.kind()) {
            case LDAP -> {
                required.add("url");
                if (!blank(effective.get("user-dn"))) {
                    required.add("password");
                }
            }
            case DATABASE -> {
                required.add("jdbc-url");
                if (!blank(effective.get("username"))) {
                    required.add("password");
                }
            }
            case REST -> {
                required.add("base-url");
                if ("true".equalsIgnoreCase(String.valueOf(effective.get("use-oauth")).strip())) {
                    required.addAll(List.of("oauth-client-name", "client-id", "client-secret", "token-uri"));
                } else if (!blank(effective.get("username"))) {
                    required.add("password");
                }
            }
            case SOAP -> {
                if (soapUse[0] || !soapUse[1]) {
                    required.add("url");
                }
                if (soapUse[1]) {
                    required.add("resource-url");
                }
                if (!blank(effective.get("username"))) {
                    required.add("password");
                }
            }
        }
        return required;
    }

    /** Each field of {@code ref} resolved; the OAuth fields under the resolved client name. */
    private Map<String, Resolved> resolveAll(Snapshot snapshot, SystemRef ref) {
        Map<String, Resolved> resolved = new LinkedHashMap<>();
        String client = null;
        if (ref.kind() == Kind.REST) {
            client = resolve(snapshot, ref.property("oauth-client-name", null)).value();
        }
        for (FieldSpec spec : ref.fields()) {
            String property = ref.property(spec.key(), client);
            resolved.put(spec.key(), property == null ? new Resolved(null, null, NOT_SET, null) : resolve(snapshot, property));
        }
        return resolved;
    }

    private Resolved resolve(Snapshot snapshot, String property) {
        String variable = RealEnvironmentGuards.variableFor(property);
        if (environment.containsKey(variable)) {
            String value = environment.get(variable);
            return new Resolved(value, value, ENVIRONMENT, null);
        }
        String raw = ProfileYamlEditor.get(snapshot.profileFlat(), property);
        String state = SET;
        if (raw == null) {
            raw = ProfileYamlEditor.get(snapshot.sharedFlat(), property);
            state = APPLICATION_YML;
        }
        if (raw == null) {
            return new Resolved(null, null, NOT_SET, null);
        }
        Matcher exact = EXACT_REFERENCE.matcher(raw);
        String reference = exact.matches() ? exact.group(1) : null;
        boolean fromEnvironment = false;
        Matcher references = REFERENCE.matcher(raw);
        while (references.find()) {
            fromEnvironment |= environment.containsKey(references.group(1));
        }
        String value = placeholders(snapshot).apply(raw);
        if (blank(value)) {
            return new Resolved(value, raw, fromEnvironment ? ENVIRONMENT : NOT_SET, reference);
        }
        return new Resolved(value, raw, fromEnvironment ? ENVIRONMENT : state, reference);
    }

    /** Resolves {@code ${NAME}} as a run does: the environment, then {@code secrets.env}; null when one cannot be. */
    private java.util.function.UnaryOperator<String> placeholders(Snapshot snapshot) {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(new MapPropertySource("environment", new LinkedHashMap<>(environment)));
        sources.addLast(new MapPropertySource("secretsEnv", new LinkedHashMap<>(snapshot.secrets())));
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(sources);
        return raw -> {
            try {
                return resolver.resolveRequiredPlaceholders(raw);
            } catch (IllegalArgumentException e) {
                return null;
            }
        };
    }

    /** Each scenario's needs, by its name; scenarios that do not load are left out. */
    private static Map<String, ScenarioNeeds> scenarioNeeds(Snapshot snapshot) {
        Map<String, ScenarioNeeds> needs = new LinkedHashMap<>();
        DirectoryScenarioSource source = new DirectoryScenarioSource(snapshot.workspace().scenarios());
        try {
            for (String name : source.names()) {
                try (InputStream in = source.open(name)) {
                    if (in != null) {
                        Scenario scenario = ScenarioLoader.load(in, name);
                        needs.put(name, ScenarioNeeds.of(List.of(scenario)));
                    }
                } catch (RuntimeException | IOException e) {
                    // a scenario that does not load needs nothing the lab panel can show; the editor reports it
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return needs;
    }

    /** The system an ldap.* step's named directory is: {@code default} is the old block unless one is named so. */
    private static String directoryId(Snapshot snapshot, String directory) {
        return LdapDirectories.DEFAULT.equals(directory) && !directoryNames(snapshot).contains(directory)
                ? "ldap" : "directory:" + directory;
    }

    /**
     * The default directory as {@code idmtest run} picks it: {@code systemId} the system steps without
     * {@code directory:} use, or null when the runner refuses them — {@code state} then says why
     * ({@code missing} or {@code invalid}). {@code choices} are the directories the runner knows,
     * {@code value} the configured {@code idm.test.default-directory} (null when unset) and {@code source}
     * where it comes from, as a field's state says it.
     */
    private record DefaultChoice(String systemId, List<String> choices, String value, String source, String state) {
    }

    private DefaultChoice defaultChoice(Snapshot snapshot) {
        Set<String> named = directoryNames(snapshot);
        Set<String> names = RealEnvironmentGuards.directoryCandidates(named, resolve(snapshot, "idm.test.ldap.url").value());
        List<String> choices = List.copyOf(names);
        Resolved resolved = resolve(snapshot, "idm.test.default-directory");
        String configured = resolved.value();
        String value = blank(configured) ? null : configured;
        String source = value == null ? null : switch (resolved.state()) {
            case SET -> "profile";
            case APPLICATION_YML -> "application.yml";
            case ENVIRONMENT -> "environment";
            default -> null;
        };
        try {
            return LdapDirectories.resolveDefault(configured, names)
                    .map(chosen -> new DefaultChoice(LdapDirectories.DEFAULT.equals(chosen) && !named.contains(chosen)
                            ? "ldap" : "directory:" + chosen, choices, value, source, null))
                    .orElseGet(() -> new DefaultChoice(null, choices, value, source, "missing"));
        } catch (IllegalStateException e) {
            return new DefaultChoice(null, choices, value, source, "invalid");
        }
    }

    private static Set<String> directoryNames(Snapshot snapshot) {
        Set<String> names = new TreeSet<>();
        for (String key : merged(snapshot)) {
            String[] parts = key.split("\\.");
            if (parts.length >= 4 && ProfileYamlEditor.uniform(parts[0] + "." + parts[1] + "." + parts[2])
                    .equals("idm.test.directories")) {
                names.add(parts[3]);
            }
        }
        return names;
    }

    /** The systems the settings files already configure. */
    private static Set<String> configuredSystems(Snapshot snapshot) {
        Set<String> ids = new LinkedHashSet<>();
        for (String key : merged(snapshot)) {
            String[] parts = key.split("\\.");
            if (parts.length < 4 || !ProfileYamlEditor.uniform(parts[0] + "." + parts[1]).equals("idm.test")) {
                continue;
            }
            switch (ProfileYamlEditor.uniform(parts[2])) {
                case "ldap" -> ids.add("ldap");
                case "soap" -> ids.add("soap");
                case "directories" -> ids.add("directory:" + parts[3]);
                case "databases" -> {
                    if (parts.length >= 5) {
                        ids.add("database:" + parts[3]);
                    }
                }
                case "rest" -> {
                    if (parts.length >= 5) {
                        ids.add("rest:" + parts[3]);
                    }
                }
                default -> {
                    // another idm.test setting
                }
            }
        }
        return ids;
    }

    private static Set<String> merged(Snapshot snapshot) {
        Set<String> keys = new LinkedHashSet<>(snapshot.sharedFlat().keySet());
        keys.addAll(snapshot.profileFlat().keySet());
        return keys;
    }

    private static Snapshot snapshot(Path root, String profile) {
        Workspace workspace = new Workspace(root);
        Path profileFile = workspace.profileFile(profile);
        try {
            String profileText = Files.isRegularFile(profileFile)
                    ? Files.readString(profileFile, StandardCharsets.UTF_8) : "";
            Map<String, String> profileFlat = ProfileYamlEditor.flatten(profileText);
            Map<String, String> shared = ProfileYamlEditor.read(workspace.config().resolve(ProfileYamlEditor.SHARED_FILE));
            return new Snapshot(workspace, profile, profileText, profileFlat, shared, SecretsEnv.read(workspace.secretsFile()));
        } catch (ProfileYamlEditor.CannotEditException e) {
            throw ApiError.badRequest("runner.ui.lab.settingsUnreadable", e.getMessage());
        } catch (UsageException e) {
            throw ApiError.badRequest("runner.ui.lab.settingsUnreadable", e.detail());
        } catch (IOException e) {
            throw ApiError.badRequest("runner.ui.lab.settingsUnreadable", e.getClass().getSimpleName());
        }
    }

    /**
     * A mask of every secret value this workspace and server know: {@code secrets.env}'s values, the
     * environment's secret-looking variables and those the settings refer to, the resolved secret
     * fields, and {@code extra} (values typed in a request).
     */
    private SecretMask mask(Snapshot snapshot, List<String> extra) {
        List<String> values = new ArrayList<>(snapshot.secrets().values());
        environment.forEach((name, value) -> {
            if (Secrets.isSecret(name) || snapshot.secrets().containsKey(name)
                    || snapshot.profileText().contains("${" + name)) {
                values.add(value);
            }
        });
        values.addAll(extra);
        return SecretMask.ofValues(values);
    }

    // --- saving ---------------------------------------------------------------------------------

    /**
     * Saves {@code values} (plain fields) into the profile file and {@code secrets} into {@code secrets.env},
     * each secret referenced from the profile file as {@code ${NAME}}: the name the profile file, the shared
     * file or the template line already uses, else the property's environment variable name
     * ({@code IDM_TEST_DATABASES_HR_PASSWORD}) — or a profile-scoped name when another profile already
     * uses that one (see {@link #secretName}). Everything is checked before anything is written.
     */
    public void save(Path root, String profile, String systemId, Map<String, String> values, Map<String, String> secrets) {
        SystemRef ref = system(systemId);
        Snapshot snapshot = snapshot(root, profile);
        Map<String, String> plain = values == null ? Map.of() : values;
        Map<String, String> secret = secrets == null ? Map.of() : secrets;
        check(snapshot, ref, plain, secret);

        String client = ref.kind() == Kind.REST
                ? firstNonBlank(plain.get("oauth-client-name"), resolve(snapshot, ref.property("oauth-client-name", null)).value())
                : null;
        Map<String, String> yaml = new LinkedHashMap<>();
        Map<String, String> env = new LinkedHashMap<>();
        boolean oauthWritten = false;
        for (FieldSpec spec : ref.fields()) {
            boolean isPlain = plain.containsKey(spec.key());
            boolean isSecret = secret.containsKey(spec.key());
            if (!isPlain && !isSecret) {
                continue;
            }
            String property = ref.property(spec.key(), client);
            if (property == null) {
                throw ApiError.badRequest("runner.ui.lab.oauthClientName", spec.key());
            }
            oauthWritten |= spec.oauth();
            if (isPlain) {
                yaml.put(property, nullToEmpty(plain.get(spec.key())));
                continue;
            }
            String name = secretName(snapshot, property, yaml, env.keySet());
            env.put(name, nullToEmpty(secret.get(spec.key())));
        }
        if (oauthWritten) {
            String grant = REGISTRATION + client + ".authorization-grant-type";
            if (ProfileYamlEditor.get(snapshot.profileFlat(), grant) == null
                    && ProfileYamlEditor.get(snapshot.sharedFlat(), grant) == null) {
                yaml.put(grant, "client_credentials");
            }
        }

        write(root, snapshot, yaml, env);
    }

    /**
     * Saves {@code name} as {@code idm.test.default-directory} in the profile file (uncommenting the
     * template's line when there is one). {@code name} must be one of the directories the runner knows.
     */
    public void saveDefaultDirectory(Path root, String profile, String name) {
        Snapshot snapshot = snapshot(root, profile);
        DefaultChoice choice = defaultChoice(snapshot);
        if (name == null || !choice.choices().contains(name)) {
            throw ApiError.badRequest("runner.ui.lab.badDefaultDirectory", String.valueOf(name), choice.choices());
        }
        String property = "idm.test.default-directory";
        // set by the environment: its own variable, or a ${VAR} reference in the files that the environment sets
        if (ENVIRONMENT.equals(resolve(snapshot, property).state())) {
            throw ApiError.badRequest("runner.ui.lab.fromEnvironment", "default-directory", variableOf(snapshot, property));
        }
        Map<String, String> yaml = new LinkedHashMap<>();
        yaml.put(property, name);
        write(root, snapshot, yaml, Map.of());
    }

    /**
     * Writes {@code yaml} into the snapshot's profile file and {@code env} into {@code secrets.env}: the
     * profile file must not be {@code application.yml} and both must be inside the workspace, and the
     * edit is tried before {@code secrets.env} changes.
     */
    private static void write(Path root, Snapshot snapshot, Map<String, String> yaml, Map<String, String> env) {
        Path profileFile = snapshot.workspace().profileFile(snapshot.profile());
        String shown = "config/" + profileFile.getFileName();
        // dry run first, so a profile file that cannot be edited stops the save before secrets.env changes
        Path secretsFile = snapshot.workspace().secretsFile();
        try {
            Path sharedFile = snapshot.workspace().config().resolve(ProfileYamlEditor.SHARED_FILE);
            Path target = real(profileFile);
            if (target.getFileName().toString().equalsIgnoreCase(ProfileYamlEditor.SHARED_FILE)
                    || Files.exists(sharedFile) && Files.exists(profileFile) && Files.isSameFile(profileFile, sharedFile)) {
                throw ApiError.badRequest("runner.ui.lab.cannotEdit", shown, ProfileYamlEditor.SHARED_FILE);
            }
            Path realRoot = root.toRealPath();
            if (!inside(target, realRoot)) {
                throw ApiError.badRequest("runner.ui.lab.cannotEdit", shown, "outside the workspace");
            }
            if (!env.isEmpty() && !inside(real(secretsFile), realRoot)) {
                throw ApiError.badRequest("runner.ui.lab.cannotEdit", "secrets.env", "outside the workspace");
            }
        } catch (IOException e) {
            throw ApiError.badRequest("runner.ui.lab.cannotEdit", shown, e.getClass().getSimpleName());
        }
        try {
            String text = snapshot.profileText();
            for (Map.Entry<String, String> entry : yaml.entrySet()) {
                text = ProfileYamlEditor.edit(text, entry.getKey(), entry.getValue());
            }
        } catch (ProfileYamlEditor.CannotEditException | IllegalArgumentException e) {
            throw ApiError.badRequest("runner.ui.lab.cannotEdit", shown, e.getMessage());
        }
        try {
            if (!env.isEmpty()) {
                SecretsEnvEditor.set(real(secretsFile), env);
            }
            if (!yaml.isEmpty() || !Files.exists(profileFile)) {
                ProfileYamlEditor.setAll(real(profileFile), yaml);
            }
        } catch (ProfileYamlEditor.CannotEditException | IllegalArgumentException e) {
            throw ApiError.badRequest("runner.ui.lab.cannotEdit", shown, e.getMessage());
        } catch (IOException e) {
            throw new ApiError(HttpStatus.INTERNAL_SERVER_ERROR, Message.of("runner.ui.file.writeFailed",
                    shown, e.getClass().getSimpleName()));
        }
    }

    /**
     * Whether {@code target} (a real path, or a path whose last part does not exist yet) is inside
     * {@code realRoot}: its folder resolved to its real location, so a link anywhere on the way counts.
     */
    private static boolean inside(Path target, Path realRoot) throws IOException {
        Path folder = target.toAbsolutePath().getParent();
        Path realFolder = Files.exists(folder) ? folder.toRealPath() : null;
        if (realFolder == null) {
            // a folder still to be made (config/): its nearest existing parent decides
            Path existing = folder;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            return existing != null && existing.toRealPath().startsWith(realRoot);
        }
        return realFolder.resolve(target.getFileName().toString()).startsWith(realRoot);
    }

    /** Where a file really is (a symbolic link followed), so a save replaces the file and not the link. */
    private static Path real(Path file) throws IOException {
        return Files.exists(file) ? file.toRealPath() : file;
    }

    /**
     * The {@code secrets.env} name for a secret property, adding to {@code yaml} the reference to it when
     * the profile file does not already make one. {@code secrets.env} is one file for every profile, so
     * a name the current profile file does not itself reference is only taken when nothing else uses it:
     * a name inherited from {@code application.yml}, one {@code secrets.env} already holds a value for, or
     * one another profile file refers to becomes the profile's own name, {@code IDM_TEST_<PROFILE>_<rest>}
     * (with {@code _2}, {@code _3}, … appended while that is taken too), and the override goes into the
     * current profile file only.
     */
    private static String secretName(Snapshot snapshot, String property, Map<String, String> yaml,
                                     Set<String> taken) {
        String inProfile = ProfileYamlEditor.get(snapshot.profileFlat(), property);
        String name = reference(inProfile);
        if (name != null) {
            return name;
        }
        boolean inherited = false;
        if (inProfile == null) {
            name = reference(ProfileYamlEditor.get(snapshot.sharedFlat(), property));
            inherited = name != null;
            if (name == null) {
                name = reference(ProfileYamlEditor.commentedValue(snapshot.profileText(), property));
            }
        }
        if (name == null) {
            name = RealEnvironmentGuards.variableFor(property);
        }
        if (inherited || usedElsewhere(snapshot, name, taken, true)) {
            String rest = name.startsWith("IDM_TEST_") ? name.substring("IDM_TEST_".length()) : name;
            String base = "IDM_TEST_" + snapshot.profile().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_") + "_" + rest;
            name = base;
            // a profile-scoped name is this profile's own: only a file that can be read and names it takes it
            for (int n = 2; usedElsewhere(snapshot, name, taken, false); n++) {
                name = base + "_" + n;
            }
        }
        yaml.put(property, "${" + name + "}");
        return name;
    }

    /**
     * Whether {@code name} is in use outside the current profile file: a value in {@code secrets.env}
     * (an empty line, such as the copied example's, holds nothing to lose), a reference from another
     * {@code config/*.yml}, or another secret of this save.
     */
    private static boolean usedElsewhere(Snapshot snapshot, String name, Set<String> taken,
                                         boolean unreadableMeansUsed) {
        if (taken.contains(name)) {
            return true;
        }
        String value = snapshot.secrets().get(name);
        if (value != null && !value.isEmpty()) {
            return true;
        }
        Path config = snapshot.workspace().config();
        if (!Files.exists(config)) {
            return false;
        }
        Path current = snapshot.workspace().profileFile(snapshot.profile());
        List<Path> files;
        try (var listing = Files.list(config)) {
            files = listing.toList();
        } catch (IOException | UncheckedIOException e) {
            // fail closed: without the other profiles' files no name can be known to be free
            throw ApiError.badRequest("runner.ui.lab.settingsUnreadable", "config/");
        }
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            if (file.equals(current) || !fileName.endsWith(".yml") && !fileName.endsWith(".yaml")
                    || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (text.contains("${" + name + "}") || text.contains("${" + name + ":")) {
                    return true;
                }
            } catch (IOException | UncheckedIOException e) {
                // unreadable or not UTF-8 (MalformedInputException): it may use the name
                if (unreadableMeansUsed) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String reference(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = EXACT_REFERENCE.matcher(raw);
        return matcher.matches() && Pattern.matches("[A-Za-z_][A-Za-z0-9_]*", matcher.group(1)) ? matcher.group(1) : null;
    }

    /**
     * Refuses, before anything happens: an unknown field, a secret under {@code values} or a plain field
     * under {@code secrets}, a line break, and a field the environment sets.
     */
    private void check(Snapshot snapshot, SystemRef ref, Map<String, String> plain, Map<String, String> secret) {
        Map<String, Resolved> resolved = resolveAll(snapshot, ref);
        String shownName = Messages.render(ref.displayName(), Locale.ENGLISH);
        for (Map.Entry<String, String> entry : plain.entrySet()) {
            FieldSpec spec = ref.field(entry.getKey());
            if (spec == null) {
                throw ApiError.badRequest("runner.ui.lab.unknownField", entry.getKey(), shownName);
            }
            if (spec.secret()) {
                throw ApiError.badRequest("runner.ui.lab.secretField", entry.getKey());
            }
        }
        for (Map.Entry<String, String> entry : secret.entrySet()) {
            FieldSpec spec = ref.field(entry.getKey());
            if (spec == null) {
                throw ApiError.badRequest("runner.ui.lab.unknownField", entry.getKey(), shownName);
            }
            if (!spec.secret()) {
                throw ApiError.badRequest("runner.ui.lab.notSecret", entry.getKey());
            }
        }
        List<Map.Entry<String, String>> all = new ArrayList<>(plain.entrySet());
        all.addAll(secret.entrySet());
        for (Map.Entry<String, String> entry : all) {
            String value = entry.getValue();
            if (value != null && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)) {
                throw ApiError.badRequest("runner.ui.lab.lineBreak", entry.getKey());
            }
        }
        checkValues(plain, secret);
        for (Map.Entry<String, String> entry : all) {
            Resolved r = resolved.get(entry.getKey());
            if (ENVIRONMENT.equals(r.state())) {
                throw ApiError.badRequest("runner.ui.lab.fromEnvironment", entry.getKey(), variableOf(snapshot, ref, entry.getKey()));
            }
        }
    }

    /**
     * Refuses a value with {@code ${} (the workbench writes the references itself, and a typed one would
     * read another setting or secret) and an OAuth client name that cannot be a key of the settings.
     */
    private static void checkValues(Map<String, String> plain, Map<String, String> secret) {
        List<Map.Entry<String, String>> all = new ArrayList<>(plain.entrySet());
        all.addAll(secret.entrySet());
        for (Map.Entry<String, String> entry : all) {
            if (entry.getValue() != null && entry.getValue().contains("${")) {
                throw ApiError.badRequest("runner.ui.lab.reference", entry.getKey());
            }
        }
        String client = plain.get("oauth-client-name");
        if (client != null && !client.isEmpty() && !NAME.matcher(client).matches()) {
            throw ApiError.badRequest("runner.ui.lab.badClientName", client);
        }
    }

    /** The environment variable that sets a field: the property's own name, or the one its ${…} reads. */
    private String variableOf(Snapshot snapshot, SystemRef ref, String key) {
        String client = ref.kind() == Kind.REST ? resolve(snapshot, ref.property("oauth-client-name", null)).value() : null;
        return variableOf(snapshot, ref.property(key, client));
    }

    /** The environment variable that sets {@code property}: its own, else the first one its value refers to. */
    private String variableOf(Snapshot snapshot, String property) {
        String variable = RealEnvironmentGuards.variableFor(property);
        if (environment.containsKey(variable)) {
            return variable;
        }
        String raw = ProfileYamlEditor.get(snapshot.profileFlat(), property);
        if (raw == null) {
            raw = ProfileYamlEditor.get(snapshot.sharedFlat(), property);
        }
        Matcher references = REFERENCE.matcher(raw == null ? "" : raw);
        while (references.find()) {
            if (environment.containsKey(references.group(1))) {
                return references.group(1);
            }
        }
        return variable;
    }

    // --- testing --------------------------------------------------------------------------------

    /**
     * Tests the system with its current settings overlaid with the form's {@code values} and
     * {@code secrets} (a field the environment sets keeps the environment's value, as in a run). Saves
     * nothing; a pass is remembered for exactly the values tested.
     */
    public ConnectionTester.Result test(Path root, String profile, String systemId, Map<String, String> values,
                                        Map<String, String> secrets, Locale locale, ConnectionTester tester) {
        SystemRef ref = system(systemId);
        Snapshot snapshot = snapshot(root, profile);
        Map<String, String> plain = values == null ? Map.of() : values;
        Map<String, String> secret = secrets == null ? Map.of() : secrets;
        Map<String, Resolved> resolved = resolveAll(snapshot, ref);
        for (String key : plain.keySet()) {
            FieldSpec spec = ref.field(key);
            if (spec == null || spec.secret()) {
                throw ApiError.badRequest(spec == null ? "runner.ui.lab.unknownField" : "runner.ui.lab.secretField",
                        spec == null ? new Object[]{key, Messages.render(ref.displayName(), Locale.ENGLISH)} : new Object[]{key});
            }
        }
        for (String key : secret.keySet()) {
            FieldSpec spec = ref.field(key);
            if (spec == null || !spec.secret()) {
                throw ApiError.badRequest(spec == null ? "runner.ui.lab.unknownField" : "runner.ui.lab.notSecret",
                        spec == null ? new Object[]{key, Messages.render(ref.displayName(), Locale.ENGLISH)} : new Object[]{key});
            }
        }

        checkValues(plain, secret);
        Map<String, String> effective = new LinkedHashMap<>();
        for (FieldSpec spec : ref.fields()) {
            Resolved r = resolved.get(spec.key());
            Map<String, String> form = spec.secret() ? secret : plain;
            String value = r.value();
            if (form.containsKey(spec.key()) && !ENVIRONMENT.equals(r.state())) {
                value = nullToEmpty(form.get(spec.key()));
            }
            effective.put(spec.key(), value);
        }
        if (ref.kind() == Kind.REST) {
            // the OAuth fields of another client, when the form renamed it, come from that client's settings
            String formerClient = resolve(snapshot, ref.property("oauth-client-name", null)).value();
            String client = effective.get("oauth-client-name");
            if (client != null && !client.equals(formerClient)) {
                for (FieldSpec spec : ref.fields()) {
                    Map<String, String> form = spec.secret() ? secret : plain;
                    if (spec.oauth() && !form.containsKey(spec.key()) && !blank(client)) {
                        effective.put(spec.key(), resolve(snapshot, ref.property(spec.key(), client)).value());
                    }
                }
            }
        }
        Map<String, String> properties = new LinkedHashMap<>();
        String client = effective.get("oauth-client-name");
        effective.forEach((key, value) -> {
            String property = ref.property(key, client);
            if (property != null && value != null) {
                properties.put(property, value);
            }
        });
        List<String> secretValues = new ArrayList<>(secret.values());
        for (FieldSpec spec : ref.fields()) {
            if (spec.secret() && effective.get(spec.key()) != null) {
                secretValues.add(effective.get(spec.key()));
            }
        }
        ConnectionTester.Result result = tester.test(ref, properties, snapshot.workspace().drivers(), locale,
                mask(snapshot, secretValues));
        if (results.size() >= MAX_RESULTS) {
            results.clear();
        }
        results.put(fingerprint(root, ref, effective), result);
        return result;
    }

    // --- helpers --------------------------------------------------------------------------------

    private static SystemRef system(String id) {
        try {
            return SystemRef.parse(id);
        } catch (IllegalArgumentException e) {
            throw ApiError.badRequest("runner.ui.lab.unknownSystem", String.valueOf(id));
        }
    }

    /** The key a test result is remembered by: a SHA-256 of the workspace, the system and its values. */
    private static String fingerprint(Path root, SystemRef ref, Map<String, String> effective) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((root.toString() + "\u0000" + ref.id()).getBytes(StandardCharsets.UTF_8));
            new TreeMap<>(effective).forEach((key, value) -> {
                if (key.equals("label")) return;
                digest.update(("\u0000" + key + "\u0001" + (value == null ? "\u0002" : value)).getBytes(StandardCharsets.UTF_8));
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this Java", e);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String first, String second) {
        return blank(first) ? second : first;
    }
}
