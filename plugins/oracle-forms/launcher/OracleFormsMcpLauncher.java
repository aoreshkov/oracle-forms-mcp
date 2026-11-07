// Bootstrap launcher for the Claude Code plugin.
//
// Claude Code plugins are fetched as a git clone, so the plugin cannot carry the server's jars.
// This launcher is what the plugin's .mcp.json actually runs: it resolves a released server
// distribution into the plugin's persistent data directory (downloading and checksum-verifying it
// once), then runs the server in *this* JVM so the stdio pipes Claude Code handed us are the
// pipes the MCP protocol speaks over.
//
// It is executed in single-file source mode (`java OracleFormsMcpLauncher.java ...`), which needs
// a JDK — a bare JRE has no compiler — and the server it loads needs Java 25+. Which `java` runs
// it is the plugin's `java` option (default: the one on PATH), substituted into .mcp.json's
// `command`. That prerequisite is documented in the plugin manifest and README; the JRE-only
// alternatives are the .mcpb bundle and `claude mcp add`.
//
// Nothing in it needs Claude Code, though: another host can run it with a fixed command line and the
// user's settings as environment variables (OFMCP_FORMS_DIR and the rest, read by the server), and
// without OFMCP_PLUGIN_DATA it keeps its downloads in a per-user data directory of the OS.
//
// The launcher itself deliberately stays within the Java 21 language and API (CI compiles it with
// `--release 21`): on a JDK too old for the server it must still compile and start, so it can run
// the server on JAVA_HOME's Java, or say what is wrong, instead of dying in a compiler error the
// user never sees.
//
// INVARIANT: stdout belongs to the MCP stdio protocol. Every diagnostic here goes to stderr.

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class OracleFormsMcpLauncher {

    private static final String REPO = "aoreshkov/oracle-forms-mcp";
    private static final String MAIN_CLASS = "app.oreshkov.oracleformsmcp.server.MainKt";
    private static final String USER_AGENT = "oracle-forms-mcp-plugin-launcher";
    /** The Java feature release current servers are compiled for. */
    private static final int SERVER_JAVA = 25;

    /** How long a resolved "latest" tag is trusted before the GitHub API is asked again. */
    private static final Duration LATEST_TTL = Duration.ofHours(24);
    /**
     * How long a failed check or install is left alone. Shorter than {@link #LATEST_TTL} so a
     * network that comes back, or a release that finishes uploading, is picked up the same day.
     */
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofHours(1);
    /** Short, because a first install has to finish inside the client's startup limit. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    /** An unpack directory this old belongs to no running launcher: unpacking takes seconds. */
    private static final Duration ABANDONED_AFTER = Duration.ofHours(1);

    private static final Pattern TAG_NAME = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"");
    /** The feature release in a JDK's {@code release} file: {@code JAVA_VERSION="25.0.4"}. */
    private static final Pattern JAVA_VERSION = Pattern.compile("(?m)^JAVA_VERSION=\"(\\d+)");
    /** Leading digit required: a version becomes a directory name, and "." or ".." must not. */
    private static final Pattern VERSION = Pattern.compile("[0-9][0-9A-Za-z.+-]*");
    /** A value that is nothing but a variable reference its host never expanded. */
    private static final Pattern UNEXPANDED = Pattern.compile("\\$\\{[^}]*}");

    /**
     * Where a user pins the server version, for messages. Not only /plugin: a host other than Claude
     * Code may start this launcher and pass the user's settings as variables.
     */
    private static final String PIN_WHERE = "(server_version in /plugin configure, or OFMCP_SERVER_VERSION)";

    public static void main(String[] args) throws Exception {
        // An unset forms directory is not refused here: it arrives blank, or as Claude Code's
        // unsubstituted ${user_config.forms_dir}, and the server treats both as unset. It starts,
        // and every tool names the setting to make, which a plugin host shows far better than an
        // exit.

        // Escape hatch: point OFMCP_SERVER_HOME at an existing installDist tree (a built checkout,
        // an unpacked release zip) and nothing is downloaded. Also how this launcher is testable
        // without a network.
        String home = envOrDefault("OFMCP_SERVER_HOME", "");
        Path dist;
        Method server;
        try {
            dist = home.isEmpty() ? resolveDistribution() : Path.of(home);
            server = loadServer(dist);
        } catch (Exception e) {
            // Bootstrap failures are the user's problem to fix, not a bug report: Claude Code shows
            // this server as failed and the reason is whatever we last wrote to stderr. A stack
            // trace would bury it. The server's own run stays outside this: a crash there is a bug.
            fail(describe(e));
            return;
        }
        if (server == null) {
            runOnJavaHome(dist, args);
            return;
        }
        server.invoke(null, (Object) args);
    }

    // ---------------------------------------------------------------------------------------
    // Distribution resolution
    // ---------------------------------------------------------------------------------------

    private static Path resolveDistribution() throws Exception {
        Path dataDir = dataDirectory();
        Path distRoot = dataDir.resolve("dist");
        Path stagingRoot = dataDir.resolve("staging");
        String requested = envOrDefault("OFMCP_SERVER_VERSION", "latest");

        if (!requested.equals("latest")) {
            // A pin is the user's explicit choice: if it cannot be installed, say so rather than
            // quietly running some other version.
            String version = requested.startsWith("v") ? requested.substring(1) : requested;
            if (!VERSION.matcher(version).matches()) {
                throw new IOException("Invalid server version '" + requested
                        + "'. Set it to 'latest' or a released version such as '0.4.0' "
                        + PIN_WHERE + ".");
            }
            Path dist = distRoot.resolve(version);
            if (!isInstalled(dist)) install(version, dist, stagingRoot);
            return dist;
        }

        Path marker = dataDir.resolve("latest-release.txt");
        Path failure = dataDir.resolve("latest-check-failed.txt");
        String remembered = rememberedLatest(marker);
        boolean fresh = remembered != null && isYoungerThan(marker, LATEST_TTL);

        // The marker's version comes first, not simply the newest on disk: a version pinned earlier
        // can be newer than the latest release.
        String ready = remembered != null && isInstalled(distRoot.resolve(remembered))
                ? remembered
                : newestInstalled(distRoot);
        if (ready != null) {
            // Something runnable is here, so the network must not stand between the client and the
            // handshake: a dropped connection can outlast the client's startup limit, and a newer
            // release's download easily does.
            boolean upToDate = fresh && ready.equals(remembered);
            if (!upToDate && !isYoungerThan(failure, RETRY_AFTER_FAILURE)) {
                updateInBackground(ready, dataDir, distRoot, stagingRoot, marker, failure);
            }
            return distRoot.resolve(ready);
        }

        // Nothing to run until a download finishes, so this one has to block.
        String version = fresh ? remembered : fetchLatestForFirstInstall();
        Path dist = distRoot.resolve(version);
        install(version, dist, stagingRoot);
        remember(dataDir, marker, failure, version);
        return dist;
    }

    private static String fetchLatestForFirstInstall() throws IOException, InterruptedException {
        try {
            return fetchLatest();
        } catch (IOException e) {
            throw new IOException("Could not reach the GitHub releases API to find the latest"
                    + " oracle-forms-mcp server, and no version is installed yet. Check the"
                    + " network and any proxy settings (HTTPS_PROXY), or pin a version "
                    + PIN_WHERE + ".", e);
        }
    }

    /**
     * Checks for a newer release and installs it for the next session; {@code running} keeps
     * serving this one. A daemon thread, so it never holds up the end of a session, and an install
     * cut off that way is left in the staging directory, where nothing looks for one. "latest" asks
     * for the newest server that works, and the one already running still does, so a failure only
     * goes to stderr. It is recorded so that the next starts leave it alone for
     * {@link #RETRY_AFTER_FAILURE}.
     */
    private static void updateInBackground(String running, Path dataDir, Path distRoot,
            Path stagingRoot, Path marker, Path failure) {
        Thread update = new Thread(() -> {
            try {
                String version = fetchLatest();
                Path dist = distRoot.resolve(version);
                boolean installing = !isInstalled(dist);
                if (installing) install(version, dist, stagingRoot);
                // Remembered only once it is installed: this is what the next session will run.
                remember(dataDir, marker, failure, version);
                if (installing) warn("installed server " + version + "; the next session uses it");
            } catch (Exception e) {
                warn("could not update from the latest release (" + describe(e)
                        + "); running installed " + running);
                try {
                    Files.createDirectories(dataDir);
                    Files.writeString(failure, e.toString(), StandardCharsets.UTF_8);
                } catch (IOException recording) {
                    warn("could not record the failed update (" + recording + ")");
                }
            }
        }, "oracle-forms-mcp-update");
        update.setDaemon(true);
        update.start();
    }

    private static void remember(Path dataDir, Path marker, Path failure, String version) throws IOException {
        Files.createDirectories(dataDir);
        Files.writeString(marker, version, StandardCharsets.UTF_8);
        Files.deleteIfExists(failure);
    }

    /** The version in the marker, whatever its age, or null when there is no usable one. */
    private static String rememberedLatest(Path marker) {
        try {
            String remembered = Files.readString(marker, StandardCharsets.UTF_8).trim();
            return VERSION.matcher(remembered).matches() ? remembered : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** False when the file doesn't exist. */
    private static boolean isYoungerThan(Path file, Duration age) {
        try {
            return System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() < age.toMillis();
        } catch (IOException e) {
            return false;
        }
    }

    /** The newest released version, asked of the GitHub API. */
    private static String fetchLatest() throws IOException, InterruptedException {
        String body = new String(get(
                URI.create("https://api.github.com/repos/" + REPO + "/releases/latest")),
                StandardCharsets.UTF_8);
        Matcher m = TAG_NAME.matcher(body);
        if (!m.find()) throw new IOException("No tag_name in the GitHub releases response");
        String version = m.group(1);
        if (!VERSION.matcher(version).matches()) {
            throw new IOException("Unusable release tag '" + version + "' from the GitHub API");
        }
        return version;
    }

    private static boolean isInstalled(Path dist) {
        return Files.isDirectory(dist.resolve("lib"));
    }

    /**
     * The newest finished install under {@code distRoot}. Only a directory named as a version
     * counts: launchers before plugin 1.2.1 unpacked into {@code <version>.incoming<random>} right
     * beside the installs, and one killed mid-unpack left that behind with a partial lib/ — which
     * also matches {@link #VERSION}, and outranks the real {@code <version>} when sorted.
     */
    private static String newestInstalled(Path distRoot) {
        if (!Files.isDirectory(distRoot)) return null;
        try (Stream<Path> entries = Files.list(distRoot)) {
            return entries.filter(OracleFormsMcpLauncher::isInstalled)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> VERSION.matcher(name).matches() && !name.contains(".incoming"))
                    .max(Comparator.comparing(OracleFormsMcpLauncher::versionKey))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** Sorts 0.10.0 above 0.9.0 by zero-padding each numeric run. Non-numeric parts sort as text. */
    private static String versionKey(String version) {
        StringBuilder key = new StringBuilder();
        for (String part : version.split("[.]")) {
            key.append(part.chars().allMatch(Character::isDigit) && part.length() <= 9
                    ? "0".repeat(9 - part.length()) + part
                    : part).append('.');
        }
        return key.toString();
    }

    /**
     * Downloads the release zip and its published SHA-256, verifies it, and unpacks it. Extraction
     * goes to a temp directory under {@code stagingRoot} — a sibling of the directory holding the
     * installs, never inside it — that is moved into place only once complete. A launcher killed
     * mid-unpack therefore leaves its partial tree where nothing looks for an installed version,
     * and a later install sweeps it away.
     */
    private static void install(String version, Path dist, Path stagingRoot) throws Exception {
        String base = "https://github.com/" + REPO + "/releases/download/v" + version + "/";
        String archive = "oracle-forms-mcp-server-" + version + ".zip";
        warn("installing oracle-forms-mcp server " + version + " into " + dist);

        byte[] expected;
        try {
            expected = get(URI.create(base + archive + ".sha256"));
        } catch (IOException e) {
            throw new IOException("No published checksum for release v" + version + " ("
                    + archive + ".sha256). A release still uploading its assets has none yet;"
                    + " one before 0.4.0 never had one, so pin 0.4.0 or later " + PIN_WHERE + ", or"
                    + " install the server manually and point OFMCP_SERVER_HOME at it.", e);
        }
        // sha256sum format: "<hex>  <filename>".
        String want = new String(expected, StandardCharsets.UTF_8).trim().split("\\s+")[0];

        byte[] zip = get(URI.create(base + archive));
        String got = sha256(zip);
        if (!got.equalsIgnoreCase(want)) {
            throw new IOException("Checksum mismatch for " + archive + ": expected " + want
                    + ", got " + got + ". Nothing was installed.");
        }

        // Same filesystem as dist (both under the plugin's data directory), so the move below can
        // be atomic.
        Files.createDirectories(dist.getParent());
        Files.createDirectories(stagingRoot);
        sweepAbandonedUnpacks(stagingRoot, dist.getParent());
        Path staging = Files.createTempDirectory(stagingRoot, version + ".");
        try {
            unpack(zip, staging);
            try {
                Files.move(staging, dist, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException race) {
                // Another session won the race, or the filesystem refused an atomic move.
                if (!Files.isDirectory(dist.resolve("lib"))) throw race;
            }
        } finally {
            deleteRecursively(staging);
        }
    }

    /**
     * Deletes what a launcher killed mid-unpack left behind: anything under {@code stagingRoot},
     * and the {@code <version>.incoming*} directories launchers before plugin 1.2.1 unpacked into
     * beside the installs. Only entries older than {@link #ABANDONED_AFTER}, so a concurrent
     * session's unpack in progress is left alone. Best effort: a failure here never stops an
     * install.
     */
    private static void sweepAbandonedUnpacks(Path stagingRoot, Path distRoot) {
        long cutoff = System.currentTimeMillis() - ABANDONED_AFTER.toMillis();
        List<Path> abandoned = new ArrayList<>();
        try (Stream<Path> staged = Files.list(stagingRoot);
             Stream<Path> installs = Files.list(distRoot)) {
            staged.forEach(abandoned::add);
            installs.filter(p -> p.getFileName().toString().contains(".incoming"))
                    .forEach(abandoned::add);
        } catch (IOException e) {
            return;
        }
        for (Path path : abandoned) {
            try {
                if (Files.getLastModifiedTime(path).toMillis() < cutoff) deleteRecursively(path);
            } catch (IOException e) {
                warn("could not remove the abandoned unpack " + path + " (" + e + ")");
            }
        }
    }

    /** Unpacks the archive, stripping its single top-level directory. Rejects zip-slip paths. */
    private static void unpack(byte[] zip, Path target) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            Path root = target.toRealPath();
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                String name = entry.getName();
                int slash = name.indexOf('/');
                if (slash < 0) continue; // the archive's own root directory entry
                Path out = root.resolve(name.substring(slash + 1)).normalize();
                if (!out.startsWith(root)) throw new IOException("Unsafe zip entry: " + name);
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        if (!Files.isDirectory(target.resolve("lib"))) {
            throw new IOException("The release archive had no lib/ directory; refusing to install");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Launch
    // ---------------------------------------------------------------------------------------

    /**
     * Loads the server into this JVM, or returns null when this Java is too old to run it.
     * In-process rather than a child process: the launcher's stdin and stdout are already the pipes
     * Claude Code opened for the stdio transport, so handing them over directly avoids both a second
     * JVM and the orphaned-child problem when a session ends.
     */
    private static Method loadServer(Path dist) throws Exception {
        Path lib = dist.resolve("lib");
        if (!Files.isDirectory(lib)) {
            throw new IOException("Not a server distribution (no lib/ directory): " + dist);
        }
        List<URL> classpath = new ArrayList<>();
        try (Stream<Path> jars = Files.list(lib)) {
            for (Path jar : jars.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
                classpath.add(jar.toUri().toURL());
            }
        }
        if (classpath.isEmpty()) throw new IOException("No jars in " + lib);

        // Parent is the platform loader, not this launcher's own loader: the server sees exactly
        // its distribution, and nothing from the single-file source compilation leaks in.
        URLClassLoader loader =
                new URLClassLoader(classpath.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        Thread.currentThread().setContextClassLoader(loader); // Logback resolves logback.xml via this
        try {
            return loader.loadClass(MAIN_CLASS).getMethod("main", String[].class);
        } catch (UnsupportedClassVersionError e) {
            // "latest" moved past the Java that runs us: servers after 0.14.0 are compiled for Java 25.
            return null;
        }
    }

    /**
     * Runs the server on JAVA_HOME's Java, for someone whose PATH has an older one: without this,
     * all they see is a server that failed to connect. It has to be a child process, so unlike
     * {@link #loadServer} it costs a second JVM. Its stdin is the client's pipe, so the child still
     * ends with the session even if this JVM is killed outright. It runs the jars directly rather
     * than this launcher again, so there is nothing to recompile and no way to loop.
     */
    private static void runOnJavaHome(Path dist, String[] args) throws Exception {
        Path java = javaHomeExecutable();
        if (java == null) {
            fail("The oracle-forms-mcp server in " + dist + " needs Java " + SERVER_JAVA
                    + " or later, but the plugin is running on Java " + Runtime.version().feature()
                    + " (" + System.getProperty("java.home") + "), and JAVA_HOME doesn't name a new"
                    + " enough one. Set the plugin's java option to a JDK " + SERVER_JAVA
                    + "+ java executable (/plugin configure), put one on PATH or in JAVA_HOME, or pin"
                    + " server version 0.14.0, the last to run on Java 21 " + PIN_WHERE + ".");
            return;
        }
        warn("this Java " + Runtime.version().feature() + " (" + System.getProperty("java.home")
                + ") is too old for the server; running it with JAVA_HOME's " + java);
        List<String> command = new ArrayList<>(List.of(
                java.toString(), "-cp", dist.resolve("lib") + File.separator + "*", MAIN_CLASS));
        command.addAll(List.of(args));
        Process server;
        try {
            server = new ProcessBuilder(command).inheritIO().start();
        } catch (IOException e) {
            fail("Could not start " + java + " (" + e + "). Set the plugin's java option to a JDK "
                    + SERVER_JAVA + "+ java executable (/plugin configure).");
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::destroyForcibly));
        System.exit(server.waitFor());
    }

    /** JAVA_HOME's java executable when it is at least {@link #SERVER_JAVA}, otherwise null. */
    private static Path javaHomeExecutable() {
        String javaHome = envOrDefault("JAVA_HOME", "");
        if (javaHome.isEmpty()) return null;
        try {
            Path home = Path.of(javaHome);
            Matcher version = JAVA_VERSION.matcher(
                    Files.readString(home.resolve("release"), StandardCharsets.UTF_8));
            Path java = home.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
            boolean usable = version.find()
                    && Integer.parseInt(version.group(1)) >= SERVER_JAVA
                    && Files.isRegularFile(java);
            return usable ? java : null;
        } catch (IOException | InvalidPathException | NumberFormatException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private static byte[] get(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/octet-stream, application/json")
                .timeout(REQUEST_TIMEOUT)
                .build();
        HttpResponse<byte[]> response = Http.CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + uri);
        }
        return response.body();
    }

    /** In a holder so that a start with nothing to check never builds a client. */
    private static final class Http {
        static final HttpClient CLIENT = build();

        private static HttpClient build() {
            HttpClient.Builder client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL) // release downloads redirect to a CDN
                    .connectTimeout(CONNECT_TIMEOUT);
            ProxySelector proxy = proxySelector();
            if (proxy != null) client.proxy(proxy);
            return client.build();
        }
    }

    /**
     * {@code HTTPS_PROXY} and {@code NO_PROXY}, which the JVM itself ignores. Without them this is
     * the JVM's default selector, which keeps {@code -Dhttps.proxyHost} working. The lower-case
     * names win, as they do in curl.
     */
    private static ProxySelector proxySelector() {
        String lower = envOrDefault("https_proxy", "");
        InetSocketAddress proxy = parseProxy(lower.isEmpty() ? envOrDefault("HTTPS_PROXY", "") : lower);
        if (proxy == null) return ProxySelector.getDefault();
        String lowerNoProxy = envOrDefault("no_proxy", "");
        String noProxy = lowerNoProxy.isEmpty() ? envOrDefault("NO_PROXY", "") : lowerNoProxy;
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return List.of(bypassesProxy(uri.getHost(), noProxy)
                        ? Proxy.NO_PROXY
                        : new Proxy(Proxy.Type.HTTP, proxy));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException e) {
                // There is only the one proxy, so nothing to fail over to.
            }
        };
    }

    /**
     * {@code http://host:port} or {@code host:port}, or null. Messages never repeat the value, which
     * can hold a password. Credentials aren't sent: the JDK disables Basic authentication on HTTPS
     * tunnels unless a JVM property says otherwise.
     */
    private static InetSocketAddress parseProxy(String value) {
        if (value.isEmpty()) return null;
        try {
            URI uri = new URI(value.contains("://") ? value : "http://" + value);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                warn("ignoring HTTPS_PROXY: only an http://host:port proxy is supported");
                return null;
            }
            if (uri.getRawUserInfo() != null) {
                warn("HTTPS_PROXY carries credentials, which are not sent: a proxy that requires"
                        + " authentication is not supported");
            }
            return InetSocketAddress.createUnresolved(uri.getHost(), uri.getPort() < 0 ? 80 : uri.getPort());
        } catch (URISyntaxException e) {
            warn("ignoring HTTPS_PROXY: it is not a valid URL");
            return null;
        }
    }

    /** {@code NO_PROXY} entries: {@code *}, or a host that also covers its subdomains. Ports are ignored. */
    private static boolean bypassesProxy(String host, String noProxy) {
        String name = host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        for (String entry : noProxy.toLowerCase(java.util.Locale.ROOT).split(",")) {
            String suffix = entry.trim().replaceFirst(":\\d+$", "").replaceFirst("^\\*?\\.", "");
            if (suffix.equals("*")) return true;
            if (!suffix.isEmpty() && (name.equals(suffix) || name.endsWith("." + suffix))) return true;
        }
        return false;
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder(64);
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Where downloaded distributions are kept: {@code OFMCP_PLUGIN_DATA}, which the Claude Code
     * plugin sets to its own data directory, else a per-user directory of this OS. The fallback is
     * for a host that starts the launcher with a fixed command line and passes environment values
     * literally, so it has no variable of its own to hand over. A value that is wholly an
     * unexpanded {@code ${...}} reference counts as unset: taken as a path, it would quietly create
     * a directory of that name wherever the host happened to start us.
     */
    private static Path dataDirectory() {
        String configured = envOrDefault("OFMCP_PLUGIN_DATA", "");
        if (!configured.isEmpty() && !UNEXPANDED.matcher(configured).matches()) return Path.of(configured);
        if (!configured.isEmpty()) warn("ignoring OFMCP_PLUGIN_DATA='" + configured + "', which was never expanded");
        return defaultDataDirectory();
    }

    /**
     * The per-user data directory for this OS, with a {@code launcher} directory inside it so that
     * nothing else ever shares the downloads: {@code %LOCALAPPDATA%} on Windows (local, not
     * roaming: these are 50 MB of jars), {@code ~/Library/Application Support} on macOS, and
     * {@code $XDG_DATA_HOME} or {@code ~/.local/share} elsewhere.
     */
    private static Path defaultDataDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home"));
        Path base;
        if (isWindows()) {
            String local = envOrDefault("LOCALAPPDATA", "");
            base = local.isEmpty() ? home.resolve("AppData").resolve("Local") : Path.of(local);
        } else if (os.startsWith("mac")) {
            base = home.resolve("Library").resolve("Application Support");
        } else {
            // The XDG spec says a relative value is invalid and is to be ignored.
            String xdg = envOrDefault("XDG_DATA_HOME", "");
            base = !xdg.isEmpty() && Path.of(xdg).isAbsolute() ? Path.of(xdg) : home.resolve(".local").resolve("share");
        }
        return base.resolve("oracle-forms-mcp").resolve("launcher");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** The message, plus the cause that carries the detail (an HTTP status, a refused connection). */
    private static String describe(Exception e) {
        return e.getMessage() + (e.getCause() == null ? "" : " (" + e.getCause() + ")");
    }

    private static void warn(String message) {
        System.err.println("[oracle-forms-mcp] " + message);
    }

    private static void fail(String message) {
        warn(message);
        System.exit(2);
    }

    private OracleFormsMcpLauncher() {}
}
