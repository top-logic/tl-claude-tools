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
 * Nexus) is passed by .mcp.json via ${CLAUDE_PLUGIN_ROOT}; the server runs in
 * the client's working directory, so no target pom needs to be named.
 * </p>
 */
public class TlMcpServe {

	/** Coordinate of the serve goal; override with TL_MCP_COORD when testing a build. */
	private static final String DEFAULT_COORD = "com.top-logic:tl-mcp-server:0.1.1";

	private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

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
		String settings = args[0];
		List<String> extra = List.of(args).subList(1, args.length);
		String coord = System.getenv().getOrDefault("TL_MCP_COORD", DEFAULT_COORD);

		Process prefetch = startPrefetch(settings, extra);

		List<String> serve = mvn(settings);
		serve.add(coord + ":serve");
		serve.addAll(extra);
		Process server = new ProcessBuilder(serve)
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
			Process process = new ProcessBuilder(cmd)
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

	/** mvn -s settings -q; on Windows Maven's launcher is mvn.cmd. */
	private static List<String> mvn(String settings) {
		List<String> cmd = new ArrayList<>();
		cmd.add(WINDOWS ? "mvn.cmd" : "mvn");
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
