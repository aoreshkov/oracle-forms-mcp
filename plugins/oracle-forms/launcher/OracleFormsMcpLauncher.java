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
// The launcher itself deliberately stays within the Java 21 language and API (CI compiles it with
// `--release 21`): on a JDK too old for the server it must still compile and start, so it can say
// what is wrong instead of dying in a compiler error the user never sees.
//
// INVARIANT: stdout belongs to the MCP stdio protocol. Every diagnostic here goes to stderr.

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
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
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(60);
    /** An unpack directory this old belongs to no running launcher: unpacking takes seconds. */
    private static final Duration ABANDONED_AFTER = Duration.ofHours(1);

    private static final Pattern TAG_NAME = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"");
    /** Leading digit required: a version becomes a directory name, and "." or ".." must not. */
    private static final Pattern VERSION = Pattern.compile("[0-9][0-9A-Za-z.+-]*");

    public static void main(String[] args) throws Exception {
        checkConfigured(args);

        // Escape hatch: point OFMCP_SERVER_HOME at an existing installDist tree (a built checkout,
        // an unpacked release zip) and nothing is downloaded. Also how this launcher is testable
        // without a network.
        String home = envOrDefault("OFMCP_SERVER_HOME", "");
        Path dist;
        try {
            dist = home.isEmpty() ? resolveDistribution() : Path.of(home);
        } catch (Exception e) {
            // Bootstrap failures are the user's problem to fix, not a bug report: Claude Code shows
            // this server as failed and the reason is whatever we last wrote to stderr. A stack
            // trace would bury it.
            fail(describe(e));
            return;
        }
        launch(dist, args);
    }

    // ---------------------------------------------------------------------------------------
    // Distribution resolution
    // ---------------------------------------------------------------------------------------

    private static Path resolveDistribution() throws Exception {
        Path dataDir = requiredEnv("OFMCP_PLUGIN_DATA");
        Path distRoot = dataDir.resolve("dist");
        Path stagingRoot = dataDir.resolve("staging");
        String requested = envOrDefault("OFMCP_SERVER_VERSION", "latest");

        if (!requested.equals("latest")) {
            // A pin is the user's explicit choice: if it cannot be installed, say so rather than
            // quietly running some other version.
            String version = requested.startsWith("v") ? requested.substring(1) : requested;
            if (!VERSION.matcher(version).matches()) {
                throw new IOException("Invalid server version '" + requested
                        + "'. Set it to 'latest' or a released version such as '0.4.0' in /plugin.");
            }
            Path dist = distRoot.resolve(version);
            if (!isInstalled(dist)) install(version, dist, stagingRoot);
            return dist;
        }

        Path marker = dataDir.resolve("latest-release.txt");
        String version = cachedLatest(marker);
        boolean fromApi = version == null;
        if (fromApi) {
            try {
                version = fetchLatest();
            } catch (IOException | InterruptedException e) {
                String installed = newestInstalled(distRoot);
                if (installed == null) {
                    throw new IOException("Could not reach the GitHub releases API to find the latest"
                            + " oracle-forms-mcp server, and no version is installed yet. Check the"
                            + " network, or pin a version in /plugin (Oracle Forms plugin config).", e);
                }
                warn("could not check for the latest release (" + e + "); using installed " + installed);
                return distRoot.resolve(installed);
            }
        }

        Path dist = distRoot.resolve(version);
        if (!isInstalled(dist)) {
            try {
                install(version, dist, stagingRoot);
            } catch (InterruptedException e) {
                throw e; // asked to stop, not a failed install
            } catch (Exception e) {
                // "latest" asks for the newest server that works, and an older one that is already
                // here still does: a CDN hiccup, a release whose assets are still uploading, or a
                // checksum mismatch must not take the plugin down with it. The marker is left alone,
                // so the next session start tries the install again.
                String installed = newestInstalled(distRoot);
                if (installed == null) throw e;
                warn("could not install the latest release " + version + ": " + describe(e)
                        + "; using installed " + installed);
                return distRoot.resolve(installed);
            }
        }
        // Remembered only once it is installed: a marker naming a version that failed to install
        // would otherwise be trusted for a whole TTL.
        if (fromApi) {
            Files.createDirectories(dataDir);
            Files.writeString(marker, version, StandardCharsets.UTF_8);
        }
        return dist;
    }

    /**
     * The "latest" version resolved within the last {@link #LATEST_TTL}, so a session start is not
     * a GitHub API call — or null when there is none, or it has expired.
     */
    private static String cachedLatest(Path marker) throws IOException {
        if (!Files.isRegularFile(marker)) return null;
        long age = System.currentTimeMillis() - Files.getLastModifiedTime(marker).toMillis();
        String cached = Files.readString(marker, StandardCharsets.UTF_8).trim();
        return age < LATEST_TTL.toMillis() && VERSION.matcher(cached).matches() ? cached : null;
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
                    + " one before 0.4.0 never had one, so pin 0.4.0 or later in /plugin, or"
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
     * Runs the server in this JVM. In-process rather than a child process: the launcher's stdin and
     * stdout are already the pipes Claude Code opened for the stdio transport, so handing them over
     * directly avoids both a second JVM and the orphaned-child problem when a session ends.
     */
    private static void launch(Path dist, String[] args) throws Exception {
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
        Class<?> server;
        try {
            server = loader.loadClass(MAIN_CLASS);
        } catch (UnsupportedClassVersionError e) {
            // "latest" moved past the Java that runs us: servers after 0.14.0 are compiled for Java 25.
            fail("The oracle-forms-mcp server in " + dist + " needs Java " + SERVER_JAVA
                    + " or later, but the plugin is running on Java " + Runtime.version().feature()
                    + " (" + System.getProperty("java.home") + "). Set the plugin's Java executable"
                    + " to a JDK " + SERVER_JAVA + "+ java (or put one on PATH), or pin server"
                    + " version 0.14.0 (the last to run on Java 21). Both are in /plugin (Oracle"
                    + " Forms plugin config).");
            return;
        }
        Method main = server.getMethod("main", String[].class);
        main.invoke(null, (Object) args);
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Fails early and legibly when the plugin's user configuration was never filled in: Claude Code
     * leaves the placeholder in the argument list rather than substituting a value.
     */
    private static void checkConfigured(String[] args) {
        for (String arg : args) {
            if (arg.contains("${user_config.")) {
                fail("The Oracle Forms plugin is not configured yet. Run /plugin, open the Oracle"
                        + " Forms plugin, and set the forms directory.");
            }
        }
    }

    private static byte[] get(URI uri) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL) // release downloads redirect to a CDN
                .connectTimeout(HTTP_TIMEOUT)
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/octet-stream, application/json")
                .timeout(HTTP_TIMEOUT)
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + uri);
        }
        return response.body();
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

    private static Path requiredEnv(String name) throws IOException {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IOException(name + " is not set. This launcher is meant to be started by the"
                    + " Claude Code plugin; to run it directly, set OFMCP_SERVER_HOME to an"
                    + " installed server distribution instead.");
        }
        return Path.of(value);
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
