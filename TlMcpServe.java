/*
 * SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
 */

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Launcher for the tl-mcp MCP server, invoked from the plugin's .mcp.json as a
 * single-file source program ({@code java TlMcpServe.java ...}, JDK 11+).
 *
 * <p>
 * Java instead of a shell script, so the same .mcp.json entry works on Linux,
 * macOS and Windows: Windows cannot start a .sh file as a process, and Java is
 * required for Maven anyway.
 * </p>
 *
 * <p>
 * Alongside the server it pulls dependency source jars into the local Maven
 * repository, so show_source can serve source for external dependencies and
 * not just for reactor modules. The pre-fetch runs in the background,
 * concurrently with the server: the MCP client allows the server a fixed
 * connect window (30 s by default) to answer the initialize request, and a
 * reactor-wide dependency:sources pass alone takes most of that. The two Maven
 * runs do not interfere, since the pre-fetch only writes -sources artifacts,
 * which the server never resolves. The pre-fetch is best-effort: artifacts
 * without published sources and offline runs must still start the server, so
 * a failure is ignored. Its build output is copied to stderr and its stdin
 * detached, because on the default stdio transport Maven's stdout would
 * otherwise corrupt the JSON-RPC stream the MCP client reads.
 * </p>
 *
 * <p>
 * Shutdown: Maven runs as a child process (mvn/mvn.cmd, which starts the Maven
 * JVM as a grandchild), not in place of this launcher as with exec in a shell.
 * The server does not exit when its stdin closes, and on Windows the client
 * ends a stdio server with TerminateProcess, which runs no shutdown hook. So
 * the Maven process trees are destroyed
 * </p>
 * <ul>
 * <li>when the client closes stdin (this launcher forwards stdin to the
 * server),</li>
 * <li>on a regular shutdown of this launcher (shutdown hook, e.g. SIGTERM),</li>
 * <li>by a small watchdog process when this launcher is killed outright.</li>
 * </ul>
 *
 * <p>
 * Usage: {@code java TlMcpServe.java <mvn-settings.xml> [extra mvn args...]}.
 * The settings file (the plugin's mvn-settings.xml, declaring the TopLogic
 * Nexus) is passed by .mcp.json via ${CLAUDE_PLUGIN_ROOT}. When the working
 * directory has no pom.xml (e.g. a Git root with the Maven project one level
 * down), the launcher searches for one and passes it to Maven with {@code -f}.
 * </p>
 */
public class TlMcpServe {

	/** Coordinate of the serve goal; override with TL_MCP_COORD when testing a build. */
	private static final String DEFAULT_COORD = "com.top-logic:tl-mcp-server:0.1.3";

	private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

	/** tl-mcp-server is compiled for Java 17 (class file version 61). */
	private static final int MIN_JAVA = 17;

	/** Internal mode: {@code --watch <launcher-pid> <pid>...}, see {@link #watch(String[])}. */
	private static final String WATCH = "--watch";

	public static void main(String[] args) throws Exception {
		if (args.length >= 1 && WATCH.equals(args[0])) {
			watch(args);
			return;
		}
		if (args.length < 1) {
			System.err.println("Usage: java TlMcpServe.java <mvn-settings.xml> [extra mvn args...]");
			System.exit(2);
		}
		if (Runtime.version().feature() < MIN_JAVA) {
			System.err.println("tl-mcp: needs Java " + MIN_JAVA + " or newer, but 'java' on the PATH is "
				+ Runtime.version() + " (" + System.getProperty("java.home") + ").");
			System.exit(1);
		}
		String settings = args[0];
		String coord = System.getenv().getOrDefault("TL_MCP_COORD", DEFAULT_COORD);
		List<String> extra = new ArrayList<>(List.of(args).subList(1, args.length));
		File pom = findPom();
		if (pom != null) {
			System.err.println("tl-mcp: using " + pom.getAbsolutePath());
			extra.add("-f");
			extra.add(pom.getAbsolutePath());
		}

		Process prefetch = startPrefetch(settings, extra);

		List<String> serve = mvn(settings);
		serve.add(coord + ":serve");
		serve.addAll(extra);
		Process server = maven(serve)
			.redirectOutput(ProcessBuilder.Redirect.INHERIT)
			.redirectError(ProcessBuilder.Redirect.INHERIT)
			.start();

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			destroyTree(server.toHandle());
			destroyTree(prefetch == null ? null : prefetch.toHandle());
		}));
		startWatchdog(server, prefetch);

		// Forward the client's requests; their end means the client is gone.
		Thread forward = new Thread(() -> {
			copy(System.in, server.getOutputStream());
			destroyTree(server.toHandle());
		}, "stdin-forward");
		forward.setDaemon(true);
		forward.start();

		int exitCode = server.waitFor();
		destroyTree(prefetch == null ? null : prefetch.toHandle());
		System.exit(exitCode);
	}

	private static Process startPrefetch(String settings, List<String> extra) {
		List<String> cmd = mvn(settings);
		cmd.add("-fn");
		cmd.add("dependency:sources");
		cmd.addAll(extra);
		try {
			Process process = maven(cmd)
				.redirectInput(nullFile())
				.redirectErrorStream(true)
				.start();
			Thread pump = new Thread(() -> copy(process.getInputStream(), System.err), "prefetch-output");
			pump.setDaemon(true);
			pump.start();
			return process;
		} catch (IOException ex) {
			System.err.println("tl-mcp: source pre-fetch not started: " + ex.getMessage());
			return null;
		}
	}

	/**
	 * Starts a second JVM on this same source file that destroys the Maven
	 * process trees once this launcher is gone - the only cleanup that still
	 * runs when the launcher is killed without a shutdown hook.
	 */
	private static void startWatchdog(Process server, Process prefetch) {
		String source = System.getProperty("jdk.launcher.sourcefile");
		if (source == null) {
			return;
		}
		List<String> cmd = new ArrayList<>();
		cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
		cmd.add(source);
		cmd.add(WATCH);
		cmd.add(Long.toString(ProcessHandle.current().pid()));
		cmd.add(Long.toString(server.pid()));
		if (prefetch != null) {
			cmd.add(Long.toString(prefetch.pid()));
		}
		try {
			new ProcessBuilder(cmd)
				.redirectInput(nullFile())
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.start();
		} catch (IOException ex) {
			System.err.println("tl-mcp: watchdog not started: " + ex.getMessage());
		}
	}

	/** Watchdog mode: wait for the launcher to end, then destroy the given process trees. */
	private static void watch(String[] args) {
		List<ProcessHandle> targets = new ArrayList<>();
		for (int i = 2; i < args.length; i++) {
			ProcessHandle.of(Long.parseLong(args[i])).ifPresent(targets::add);
		}
		ProcessHandle.of(Long.parseLong(args[1])).ifPresent(launcher -> launcher.onExit().join());
		for (ProcessHandle target : targets) {
			destroyTree(target);
		}
	}

	/**
	 * Finds the project pom.xml when the working directory does not contain one.
	 * Search order: cwd, the child directory named like cwd, the only child
	 * directory with a pom, parent directories. Returns {@code null} if the pom is
	 * in cwd or none is found (Maven's own error is clear enough). The
	 * tl-docs-index hook locates the project the same way.
	 */
	private static File findPom() {
		File cwd = new File(".").getAbsoluteFile().getParentFile();
		if (new File(cwd, "pom.xml").isFile()) {
			return null; // already in the right directory
		}
		File named = new File(new File(cwd, cwd.getName()), "pom.xml");
		if (named.isFile()) {
			return named;
		}
		File[] children = cwd.listFiles(File::isDirectory);
		if (children != null) {
			File match = null;
			int count = 0;
			for (File child : children) {
				File pom = new File(child, "pom.xml");
				if (pom.isFile()) {
					match = pom;
					count++;
				}
			}
			if (count == 1) {
				return match;
			}
		}
		// Walk up to parent directories.
		for (File dir = cwd.getParentFile(); dir != null; dir = dir.getParentFile()) {
			if (new File(dir, "pom.xml").isFile()) {
				return new File(dir, "pom.xml");
			}
		}
		return null;
	}

	/**
	 * Maven runs on this launcher's own JDK (at least {@link #MIN_JAVA}): the
	 * server needs Java 17, while JAVA_HOME or a mavenrc file (mavenrc_pre.bat,
	 * ~/.mavenrc - often used to pin an older JDK for the user's builds) may
	 * point elsewhere. MAVEN_SKIP_RC keeps the mavenrc files from overriding
	 * JAVA_HOME again.
	 */
	private static ProcessBuilder maven(List<String> cmd) {
		ProcessBuilder builder = new ProcessBuilder(cmd);
		builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
		builder.environment().put("MAVEN_SKIP_RC", "1");
		return builder;
	}

	/**
	 * mvn -s settings -q; on Windows Maven's launcher is mvn.cmd, run via
	 * {@code cmd.exe /q /c}: with echo off from the start, a command placed
	 * before {@code @echo off} in a customized mvn.cmd (or in mavenrc_pre.bat)
	 * is not echoed to stdout, which is the server's JSON-RPC channel.
	 */
	private static List<String> mvn(String settings) {
		List<String> cmd = new ArrayList<>();
		if (WINDOWS) {
			cmd.add("cmd.exe");
			cmd.add("/q");
			cmd.add("/c");
			cmd.add("mvn.cmd");
		} else {
			cmd.add("mvn");
		}
		cmd.add("-s");
		cmd.add(settings);
		cmd.add("-q");
		return cmd;
	}

	private static ProcessBuilder.Redirect nullFile() {
		return ProcessBuilder.Redirect.from(new File(WINDOWS ? "NUL" : "/dev/null"));
	}

	private static void copy(InputStream in, OutputStream out) {
		byte[] buffer = new byte[8192];
		try {
			for (int n; (n = in.read(buffer)) >= 0;) {
				out.write(buffer, 0, n);
				out.flush();
			}
		} catch (IOException ex) {
			// The other side is gone; the caller handles the shutdown.
		}
	}

	/** The mvn script starts the actual Maven JVM as a child, so destroy descendants too. */
	private static void destroyTree(ProcessHandle process) {
		if (process == null || !process.isAlive()) {
			return;
		}
		process.descendants().forEach(ProcessHandle::destroy);
		process.destroy();
	}

}
