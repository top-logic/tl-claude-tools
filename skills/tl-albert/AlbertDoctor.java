/*
 * SPDX-FileCopyrightText: 2026 (c) Business Operation Systems GmbH <info@top-logic.com>
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-BOS-TopLogic-1.0
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Read-only diagnosis of a TopLogic application for the tl-albert skill.
 *
 * <p>
 * Runs as a single-file source program on JDK 17 or newer, with the standard library only:
 * {@code java AlbertDoctor.java detect <app-dir>} or {@code java AlbertDoctor.java check <app-dir>}.
 * It never writes a file and never prints the value of a secret.
 * </p>
 *
 * <ul>
 * <li>{@code detect}: prints a fact sheet of the application (engine version, user interface, AI
 * modules, roles, model security, configured services, secrets, what the engine of that version
 * ships) and the integration steps that apply.</li>
 * <li>{@code check}: prints the errors and warnings of an Albert integration. Exit code 1 when
 * there is an error.</li>
 * </ul>
 *
 * <p>
 * Option {@code --m2 <dir>}: the local Maven repository. Default: {@code <localRepository>} of
 * {@code ~/.m2/settings.xml}, else {@code ~/.m2/repository}.
 * </p>
 */
public class AlbertDoctor {

	// ---------------------------------------------------------------------------------------
	// Engine names. The one place to adjust when the engine renames a shipped part.
	// ---------------------------------------------------------------------------------------

	/** Web fragment entry of tl-ai-core: owner access to chats (Trac #30144). */
	static final String ENGINE_CHAT_SECURITY = "WEB-INF/conf/tl-ai-core.chatSecurity.config.xml";

	/** Role of the shipped chat security file. */
	static final String ENGINE_CHAT_OWNER_ROLE = "AiChatOwner";

	/** Chat page shipped by tl-chat, relative to WEB-INF/views/. */
	static final String ENGINE_CHAT_VIEW = "tl-chat/albert.view.xml";

	/** The tl-chat jar maps the chat REST routes (/chat/*, e.g. /chat/capabilities) in this file. Gone since Trac #30126. */
	static final String ENGINE_CHAT_REST_FRAGMENT = "META-INF/web-fragment.xml";

	/** How-to article shipped in the tl-ai-core jar (read_doc name). */
	static final String ENGINE_HOWTO_DOC = "ai/adding-albert";

	/** The agent the chat talks to. */
	static final String AGENT = "TL_SingleAgent";

	/** Role of the skill's own chat security file (templates/chat-security.config.xml). */
	static final String FALLBACK_OWNER_ROLE = "AgentSessionOwner";

	/** Name of the domain-knowledge prompt contributor. */
	static final String PROMPT_CONTRIBUTOR = "application-context";

	/** Secrets of the providers that tl-ai-core ships. */
	static final String[][] SHIPPED_PROVIDERS = {
		{ "anthropic", "tl_anthropic_api_key" },
		{ "openai", "tl_openai_api_key" },
		{ "mistral", "tl_mistral_api_key" },
		{ "xai", "tl_xai_api_key" },
	};

	static final String SVC_ROLES = "com.top_logic.base.services.InitialRolesManager";
	static final String SVC_ACCESS = "com.top_logic.tool.boundsec.manager.AccessManager";
	static final String SVC_SECURITY = "com.top_logic.model.security.SecurityConfigurationService";
	static final String SVC_GROUPS = "com.top_logic.tool.boundsec.simple.CommandGroupRegistry";
	static final String SVC_BOUND_HELPER = "com.top_logic.tool.boundsec.BoundHelper";
	static final String SVC_TOOL_ACCESS = "com.top_logic.tl.ai.core.security.AiToolAccessService";
	static final String SVC_AGENT_APP = "com.top_logic.tl.ai.core.agent.AgentApplicationService";
	static final String SVC_PROVIDERS = "com.top_logic.tl.ai.core.provider.LLMProviderService";
	static final String SVC_RUNTIME = "com.top_logic.tl.ai.core.agent.AgentRuntimeService";

	static final String[] RELEVANT_SERVICES = {
		SVC_ROLES, SVC_ACCESS, SVC_SECURITY, SVC_GROUPS, SVC_BOUND_HELPER, SVC_TOOL_ACCESS, SVC_AGENT_APP,
		SVC_PROVIDERS, SVC_RUNTIME,
	};

	// ---------------------------------------------------------------------------------------

	public static void main(String[] args) throws Exception {
		String mode = null;
		String dir = null;
		String m2 = null;
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			if (arg.equals("--m2") && i + 1 < args.length) {
				m2 = args[++i];
			} else if (mode == null) {
				mode = arg;
			} else if (dir == null) {
				dir = arg;
			} else {
				usage();
			}
		}
		if (mode == null || !(mode.equals("detect") || mode.equals("check"))) {
			usage();
		}
		Path start = Paths.get(dir == null ? "." : dir).toAbsolutePath().normalize();
		App app = App.locate(start);
		if (app == null) {
			System.err.println("No TopLogic application found in " + start
				+ " (a pom.xml next to src/main/webapp/WEB-INF, here or in a child directory).");
			System.exit(2);
		}
		Path repo = m2 != null ? Paths.get(m2) : defaultRepository();
		app.read(repo);
		if (mode.equals("detect")) {
			app.printDetect();
		} else {
			int errors = app.printCheck();
			System.exit(errors > 0 ? 1 : 0);
		}
	}

	static void usage() {
		System.err.println("Usage: java AlbertDoctor.java <detect|check> [<app-dir>] [--m2 <local-repository>]");
		System.exit(2);
	}

	static Path defaultRepository() {
		Path home = Paths.get(System.getProperty("user.home"));
		Path settings = home.resolve(".m2").resolve("settings.xml");
		if (Files.isRegularFile(settings)) {
			Document doc = Xml.parse(settings);
			if (doc != null) {
				for (Element e : Xml.all(doc.getDocumentElement(), "localRepository")) {
					String value = e.getTextContent().trim();
					if (!value.isEmpty() && !value.contains("${")) {
						return Paths.get(value);
					}
				}
			}
		}
		return home.resolve(".m2").resolve("repository");
	}

	// ---------------------------------------------------------------------------------------

	/** A finding of check mode. */
	static final class Finding {
		final boolean error;

		final String where;

		final String text;

		Finding(boolean error, String where, String text) {
			this.error = error;
			this.where = where;
			this.text = text;
		}
	}

	/** A configuration file of the application with its parsed DOM. */
	static final class XmlFile {
		final Path path;

		final String rel;

		final Document doc;

		XmlFile(Path path, String rel, Document doc) {
			this.path = path;
			this.rel = rel;
			this.doc = doc;
		}
	}

	/** What the engine of the application's version ships, read from the local Maven repository. */
	static final class Engine {
		/** null: the version is unknown or no engine artifact of it is in the repository. */
		Boolean chatSecurity;

		Boolean chatView;

		Boolean howtoDoc;

		Boolean chatRestApi;

		boolean scanned;

		int fragments;

		final Set<String> roles = new TreeSet<>();

		final Set<String> views = new TreeSet<>();

		final Set<String> commandGroups = new TreeSet<>();

		final Set<String> layouts = new TreeSet<>();

		void read(Path repo, String version) {
			if (version == null) {
				return;
			}
			Path group = repo.resolve("com").resolve("top-logic");
			Path core = group.resolve("tl-ai-core").resolve(version);
			Path coreWar = core.resolve("tl-ai-core-" + version + "-web-fragment.war");
			Path coreJar = core.resolve("tl-ai-core-" + version + ".jar");
			Path chatWar = group.resolve("tl-chat").resolve(version).resolve("tl-chat-" + version + "-web-fragment.war");
			chatSecurity = hasEntry(coreWar, ENGINE_CHAT_SECURITY);
			chatView = hasEntry(chatWar, "WEB-INF/views/" + ENGINE_CHAT_VIEW);
			howtoDoc = hasEntry(coreJar, "META-INF/tl-docs/" + ENGINE_HOWTO_DOC + ".md");
			chatRestApi = entryContains(group.resolve("tl-chat").resolve(version).resolve("tl-chat-" + version + ".jar"),
				ENGINE_CHAT_REST_FRAGMENT, "/chat/*");
			if (!Files.isDirectory(group)) {
				return;
			}
			try (Stream<Path> artifacts = Files.list(group)) {
				for (Path artifact : (Iterable<Path>) artifacts::iterator) {
					Path war = artifact.resolve(version)
						.resolve(artifact.getFileName() + "-" + version + "-web-fragment.war");
					if (Files.isRegularFile(war)) {
						scanFragment(war);
					}
				}
			} catch (IOException ex) {
				// Best effort: an unreadable repository leaves the index empty.
			}
			scanned = fragments > 0;
		}

		private void scanFragment(Path war) {
			try (ZipFile zip = new ZipFile(war.toFile())) {
				fragments++;
				Enumeration<? extends ZipEntry> entries = zip.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();
					String name = entry.getName();
					if (name.startsWith("WEB-INF/views/") && name.endsWith(".view.xml")) {
						views.add(name.substring("WEB-INF/views/".length()));
					} else if (name.startsWith("WEB-INF/layouts/")) {
						layouts.add(name.substring("WEB-INF/layouts/".length()));
					} else if ((name.startsWith("WEB-INF/conf/") || name.startsWith("WEB-INF/autoconf/"))
						&& name.endsWith(".xml")) {
						byte[] bytes = readAll(zip.getInputStream(entry));
						String text = new String(bytes, StandardCharsets.UTF_8);
						if (text.contains(SVC_ROLES) || text.contains(SVC_GROUPS)) {
							Document doc = Xml.parse(new ByteArrayInputStream(bytes));
							if (doc != null) {
								for (Element config : Xml.services(doc, SVC_ROLES)) {
									for (Element role : Xml.all(config, "role")) {
										addName(roles, role);
									}
								}
								for (Element config : Xml.services(doc, SVC_GROUPS)) {
									for (Element g : Xml.all(config, "group")) {
										addName(commandGroups, g);
									}
								}
							}
						}
					}
				}
			} catch (IOException ex) {
				// Skip an unreadable archive.
			}
		}

		static Boolean entryContains(Path archive, String entry, String text) {
			if (!Files.isRegularFile(archive)) {
				return null;
			}
			try (ZipFile zip = new ZipFile(archive.toFile())) {
				ZipEntry e = zip.getEntry(entry);
				return e != null && new String(readAll(zip.getInputStream(e)), StandardCharsets.UTF_8).contains(text);
			} catch (IOException ex) {
				return null;
			}
		}

		static Boolean hasEntry(Path archive, String entry) {
			if (!Files.isRegularFile(archive)) {
				return null;
			}
			try (ZipFile zip = new ZipFile(archive.toFile())) {
				return zip.getEntry(entry) != null;
			} catch (IOException ex) {
				return null;
			}
		}
	}

	static void addName(Collection<String> target, Element e) {
		String name = e.getAttribute("name").trim();
		if (!name.isEmpty()) {
			target.add(name);
		}
	}

	static byte[] readAll(InputStream in) throws IOException {
		try (InputStream input = in) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int n;
			while ((n = input.read(buffer)) > 0) {
				out.write(buffer, 0, n);
			}
			return out.toByteArray();
		}
	}

	// ---------------------------------------------------------------------------------------

	/** The application under diagnosis. */
	static final class App {
		final Path root;

		final Path module;

		final Path webInf;

		Document pom;

		final Map<String, String> pomProperties = new LinkedHashMap<>();

		String engineVersion;

		String engineVersionSource;

		final Set<String> deps = new TreeSet<>();

		boolean oldAiVersionProperty;

		final List<XmlFile> configs = new ArrayList<>();

		final List<XmlFile> views = new ArrayList<>();

		final List<XmlFile> layouts = new ArrayList<>();

		final List<XmlFile> models = new ArrayList<>();

		final List<String> unparsable = new ArrayList<>();

		final Map<String, Map<String, Integer>> servicesPerFile = new TreeMap<>();

		final Map<String, Set<String>> filesPerService = new TreeMap<>();

		final Set<String> declaredRoles = new TreeSet<>();

		/** role -> group names of the singleton rules that put the role on the security root. */
		final Map<String, Set<String>> rootRoles = new TreeMap<>();

		/** role -> types on which a reference rule derives the role. */
		final Map<String, Set<String>> objectRoles = new TreeMap<>();

		/** type -> operation -> roles. */
		final Map<String, Map<String, Set<String>>> grants = new TreeMap<>();

		final Set<String> accessParentTypes = new TreeSet<>();

		boolean securityConfigPresent;

		Boolean defaultSecurityParent;

		final Set<String> commandGroups = new TreeSet<>();

		final Map<String, Set<String>> toolAccessRoles = new TreeMap<>();

		String agentAccessDefault;

		final Set<String> agentAccessRoles = new TreeSet<>();

		boolean backgroundAllowlist;

		final Set<String> promptContributors = new TreeSet<>();

		final Set<String> customProviders = new TreeSet<>();

		final Set<String> namedSecrets = new TreeSet<>();

		final List<String> aliasKeys = new ArrayList<>();

		final List<String> llmConfiguration = new ArrayList<>();

		final List<String> chatViews = new ArrayList<>();

		final List<String> chatDocks = new ArrayList<>();

		final List<String> navViewRefs = new ArrayList<>();

		final Set<String> classicChatGrants = new TreeSet<>();

		boolean hasSecurityXml;

		/** model element key (model.<module>.<Type>[.<attr>]) in declaration order. */
		final List<String> modelKeys = new ArrayList<>();

		final Set<String> modelClasses = new TreeSet<>();

		/** Keys of the model modules: they need a label, a tooltip is optional. */
		final Set<String> moduleKeys = new TreeSet<>();

		final Properties labelsEn = new Properties();

		final Properties labelsDe = new Properties();

		final Set<String> appBundles = new TreeSet<>();

		String develSecretsLine;

		final Engine engine = new Engine();

		App(Path root, Path module) {
			this.root = root;
			this.module = module;
			this.webInf = module.resolve("src/main/webapp/WEB-INF");
		}

		static App locate(Path start) {
			if (isAppModule(start)) {
				return new App(start, start);
			}
			try (Stream<Path> walk = Files.walk(start, 3)) {
				for (Path p : (Iterable<Path>) walk::iterator) {
					if (Files.isDirectory(p) && !ignored(start.relativize(p)) && isAppModule(p)) {
						return new App(start, p);
					}
				}
			} catch (IOException ex) {
				return null;
			}
			return null;
		}

		static boolean isAppModule(Path dir) {
			return Files.isRegularFile(dir.resolve("pom.xml"))
				&& Files.isDirectory(dir.resolve("src/main/webapp/WEB-INF"));
		}

		static boolean ignored(Path rel) {
			for (Path part : rel) {
				String n = part.toString();
				if (n.equals("target") || n.equals("tmp") || n.equals("node_modules") || n.startsWith(".")) {
					return true;
				}
			}
			return false;
		}

		String rel(Path p) {
			return root.relativize(p).toString().replace('\\', '/');
		}

		// ---- reading ------------------------------------------------------------------

		void read(Path repo) {
			readPom();
			readXml(webInf.resolve("conf"), configs, ".xml");
			readXml(webInf.resolve("autoconf"), configs, ".xml");
			readXml(module.resolve("deploy"), configs, ".xml");
			readXml(webInf.resolve("views"), views, ".view.xml");
			readXml(webInf.resolve("layouts"), layouts, ".xml");
			readXml(webInf.resolve("model"), models, ".model.xml");
			for (XmlFile f : configs) {
				readConfig(f);
			}
			for (XmlFile f : views) {
				readView(f);
			}
			for (XmlFile f : models) {
				readModel(f);
			}
			readLabels();
			readMetaConf();
			engine.read(repo, engineVersion);
		}

		void readPom() {
			Path pomFile = module.resolve("pom.xml");
			pom = Xml.parse(pomFile);
			// Properties of the parent poms in the file system first, the module's last.
			List<Document> chain = new ArrayList<>();
			Path current = pomFile;
			Document doc = pom;
			for (int depth = 0; doc != null && depth < 5; depth++) {
				chain.add(0, doc);
				Element parent = Xml.child(doc.getDocumentElement(), "parent");
				if (parent == null) {
					break;
				}
				String relativePath = Xml.childText(parent, "relativePath");
				Path next = current.getParent().resolve(relativePath == null ? "../pom.xml" : relativePath).normalize();
				if (Files.isDirectory(next)) {
					next = next.resolve("pom.xml");
				}
				if (!Files.isRegularFile(next)) {
					break;
				}
				current = next;
				doc = Xml.parse(next);
			}
			for (Document d : chain) {
				Element props = Xml.child(d.getDocumentElement(), "properties");
				if (props != null) {
					for (Element p : Xml.children(props)) {
						pomProperties.put(p.getTagName(), p.getTextContent().trim());
					}
				}
				for (Element dep : Xml.all(d.getDocumentElement(), "dependency")) {
					String groupId = Xml.childText(dep, "groupId");
					String artifactId = Xml.childText(dep, "artifactId");
					String version = Xml.childText(dep, "version");
					boolean managed = isUnder(dep, "dependencyManagement");
					if ("com.top-logic".equals(groupId) && artifactId != null) {
						if (artifactId.equals("tl-bom") && engineVersion == null) {
							engineVersion = resolve(version);
							engineVersionSource = "BOM tl-bom";
						} else if (!managed) {
							deps.add(artifactId);
						}
					}
					if (version != null && version.contains("tl.ai.version")) {
						oldAiVersionProperty = true;
					}
				}
			}
			if (pomProperties.containsKey("tl.ai.version")) {
				oldAiVersionProperty = true;
			}
			if (pomProperties.containsKey("tl.version")) {
				engineVersion = resolve(pomProperties.get("tl.version"));
				engineVersionSource = "property tl.version";
			}
			if (engineVersion == null && pom != null) {
				Element parent = Xml.child(pom.getDocumentElement(), "parent");
				if (parent != null && "com.top-logic".equals(Xml.childText(parent, "groupId"))) {
					engineVersion = resolve(Xml.childText(parent, "version"));
					engineVersionSource = "parent " + Xml.childText(parent, "artifactId");
				}
			}
		}

		static boolean isUnder(Element e, String tag) {
			for (Node n = e.getParentNode(); n instanceof Element; n = n.getParentNode()) {
				if (((Element) n).getTagName().equals(tag)) {
					return true;
				}
			}
			return false;
		}

		String resolve(String value) {
			if (value == null) {
				return null;
			}
			String result = value.trim();
			for (int i = 0; i < 5 && result.contains("${"); i++) {
				Matcher m = Pattern.compile("\\$\\{([^}]+)\\}").matcher(result);
				StringBuffer sb = new StringBuffer();
				while (m.find()) {
					String v = pomProperties.get(m.group(1));
					m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v));
				}
				m.appendTail(sb);
				if (sb.toString().equals(result)) {
					break;
				}
				result = sb.toString();
			}
			return result.contains("${") ? null : result;
		}

		void readXml(Path dir, List<XmlFile> target, String suffix) {
			if (!Files.isDirectory(dir)) {
				return;
			}
			try (Stream<Path> walk = Files.walk(dir)) {
				walk.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(suffix))
					.filter(p -> !ignored(module.relativize(p)))
					.sorted()
					.forEach(p -> {
						Document doc = Xml.parse(p);
						if (doc == null) {
							unparsable.add(rel(p));
						} else {
							target.add(new XmlFile(p, rel(p), doc));
						}
					});
			} catch (IOException ex) {
				unparsable.add(rel(dir));
			}
		}

		void readConfig(XmlFile f) {
			Element root = f.doc.getDocumentElement();
			for (Element config : Xml.all(root, "config")) {
				String svc = config.getAttribute("service-class");
				if (svc.isEmpty()) {
					continue;
				}
				servicesPerFile.computeIfAbsent(f.rel, k -> new TreeMap<>()).merge(svc, 1, Integer::sum);
				filesPerService.computeIfAbsent(svc, k -> new TreeSet<>()).add(f.rel);
				switch (svc) {
					case SVC_ROLES:
						for (Element role : Xml.all(config, "role")) {
							addName(declaredRoles, role);
						}
						break;
					case SVC_ACCESS:
						readRoleRules(config);
						break;
					case SVC_SECURITY:
						readSecurityConfig(config);
						break;
					case SVC_GROUPS:
						for (Element g : Xml.all(config, "group")) {
							addName(commandGroups, g);
						}
						break;
					case SVC_BOUND_HELPER:
						for (Element inst : Xml.all(config, "instance")) {
							String v = inst.getAttribute("use-default-security-parent");
							if (!v.isEmpty()) {
								defaultSecurityParent = Boolean.valueOf(v);
							}
						}
						break;
					case SVC_TOOL_ACCESS:
						readToolAccess(config);
						break;
					case SVC_AGENT_APP:
						for (Element c : Xml.all(config, "contributor")) {
							addName(promptContributors, c);
						}
						break;
					case SVC_PROVIDERS:
						for (Element p : Xml.all(config, "provider")) {
							addName(customProviders, p);
						}
						break;
					default:
						break;
				}
			}
			for (Element e : Xml.all(root, "*")) {
				if (e.getTagName().equals("llm-configuration")) {
					llmConfiguration.add(f.rel);
				}
				if (e.getTagName().equals("api-key")) {
					addName(namedSecrets, e);
				}
				String key = e.getAttribute("api-key");
				if (key.startsWith("%") || key.startsWith("unencrypted:%")) {
					aliasKeys.add(f.rel + " (" + e.getTagName() + " api-key=\"" + key + "\")");
				}
				if (e.getTagName().equals("bundle")) {
					addName(appBundles, e);
				}
			}
			if (f.rel.endsWith("conf/security.xml") || f.path.getFileName().toString().equals("security.xml")) {
				hasSecurityXml = true;
				for (Element view : Xml.all(root, "view")) {
					String name = view.getAttribute("name");
					if (name.startsWith("tl-chat/")) {
						Element profile = Xml.ancestor(view, "profile");
						classicChatGrants.add((profile == null ? "?" : profile.getAttribute("name")) + " -> " + name);
					}
				}
			}
		}

		void readRoleRules(Element config) {
			for (Element rule : Xml.all(config, "singleton-rule")) {
				String role = rule.getAttribute("role");
				String target = rule.getAttribute("target");
				if (role.isEmpty()) {
					continue;
				}
				Set<String> groups = new TreeSet<>();
				for (Element g : Xml.all(rule, "group-with-name")) {
					addName(groups, g);
				}
				if (target.isEmpty() || target.equals("SecurityStructure#ROOT")) {
					rootRoles.computeIfAbsent(role, k -> new TreeSet<>()).addAll(groups);
				}
			}
			for (Element rule : Xml.all(config, "rule")) {
				if (Xml.ancestor(rule, "role-rules") == null) {
					continue;
				}
				String role = rule.getAttribute("role");
				String type = rule.getAttribute("meta-element");
				if (!role.isEmpty()) {
					objectRoles.computeIfAbsent(role, k -> new TreeSet<>()).add(type.isEmpty() ? "?" : type);
				}
			}
		}

		void readSecurityConfig(Element config) {
			for (Element sc : Xml.all(config, "security-config")) {
				securityConfigPresent = true;
				for (Element entry : Xml.children(sc)) {
					String name = entry.getAttribute("name");
					if (name.isEmpty()) {
						name = entry.getAttribute("module");
					}
					String key = entry.getTagName() + ":" + name;
					if (entry.getTagName().equals("class")) {
						key = name;
						if (Xml.child(entry, "access-parent") != null) {
							accessParentTypes.add(name);
						}
					}
					for (Element grant : Xml.all(entry, "grant")) {
						String op = grant.getAttribute("operation");
						Set<String> roles = grants.computeIfAbsent(key, k -> new TreeMap<>())
							.computeIfAbsent(op, k -> new TreeSet<>());
						roles.addAll(split(grant.getAttribute("roles")));
					}
				}
			}
		}

		void readToolAccess(Element config) {
			for (Element ta : Xml.all(config, "tool-access")) {
				for (Element role : Xml.children(ta, "role")) {
					Set<String> groups = toolAccessRoles.computeIfAbsent(role.getAttribute("name"), k -> new TreeSet<>());
					for (Element g : Xml.all(role, "grant")) {
						groups.add(g.getAttribute("group"));
					}
				}
			}
			for (Element aa : Xml.all(config, "agent-access")) {
				agentAccessDefault = aa.getAttribute("default").isEmpty() ? "all" : aa.getAttribute("default");
				for (Element role : Xml.children(aa, "role")) {
					addName(agentAccessRoles, role);
				}
			}
			if (!Xml.all(config, "background-allowlist").isEmpty()) {
				backgroundAllowlist = true;
			}
		}

		void readView(XmlFile f) {
			Element root = f.doc.getDocumentElement();
			if (!Xml.all(root, "chat-conversation").isEmpty() && Xml.all(root, "chat-dock").isEmpty()) {
				chatViews.add(f.rel);
			}
			if (!Xml.all(root, "chat-dock").isEmpty()) {
				chatDocks.add(f.rel);
			}
			for (Element ref : Xml.all(root, "view-ref")) {
				navViewRefs.add(ref.getAttribute("view"));
			}
		}

		void readModel(XmlFile f) {
			for (Element module : Xml.all(f.doc.getDocumentElement(), "module")) {
				String m = module.getAttribute("name");
				modelKeys.add("model." + m);
				moduleKeys.add("model." + m);
				for (Element type : Xml.all(module, "*")) {
					String tag = type.getTagName();
					if (!(tag.equals("class") || tag.equals("interface") || tag.equals("enum"))
						|| Xml.ancestor(type, "types") == null) {
						continue;
					}
					String t = type.getAttribute("name");
					modelKeys.add("model." + m + "." + t);
					if (tag.equals("class") && !"true".equals(type.getAttribute("abstract"))) {
						modelClasses.add(m + ":" + t);
					}
					for (Element part : Xml.all(type, "*")) {
						String pt = part.getTagName();
						if (pt.equals("property") || pt.equals("reference") || pt.equals("classifier")) {
							modelKeys.add("model." + m + "." + t + "." + part.getAttribute("name"));
						}
					}
				}
			}
		}

		void readLabels() {
			Path dir = webInf.resolve("conf").resolve("resources");
			if (!Files.isDirectory(dir)) {
				return;
			}
			try (Stream<Path> list = Files.list(dir)) {
				for (Path p : (Iterable<Path>) list::iterator) {
					String n = p.getFileName().toString();
					Properties target = n.endsWith("_en.properties") ? labelsEn
						: n.endsWith("_de.properties") ? labelsDe : null;
					if (target != null) {
						try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
							target.load(r);
						} catch (IOException ex) {
							// Unreadable bundle: its keys count as missing.
						}
					}
				}
			} catch (IOException ex) {
				// No bundles.
			}
		}

		void readMetaConf() {
			Path meta = module.resolve("deploy/local/webapp/WEB-INF/conf/metaConf.txt");
			if (!Files.isRegularFile(meta)) {
				return;
			}
			try {
				List<String> lines = Files.readAllLines(meta, StandardCharsets.UTF_8);
				int h2 = -1;
				int secrets = -1;
				for (int i = 0; i < lines.size(); i++) {
					String l = lines.get(i).trim();
					if (l.equals("devel-h2.xml")) {
						h2 = i;
					}
					if (l.equals("devel-secrets.xml")) {
						secrets = i;
					}
				}
				develSecretsLine = secrets < 0 ? "missing" : (h2 >= 0 && secrets < h2 ? "before devel-h2.xml" : "present");
			} catch (IOException ex) {
				develSecretsLine = null;
			}
		}

		// ---- derived facts --------------------------------------------------------------

		String uiType() {
			boolean react = !views.isEmpty() || deps.contains("tl-starter-react") || deps.contains("tl-layout-view");
			boolean classic = !layouts.isEmpty() || deps.contains("tl-chat-classic");
			if (react && classic) {
				return "both";
			}
			return classic ? "classic" : react ? "react" : "unknown";
		}

		boolean hasAiCore() {
			return deps.contains("tl-ai-core") || deps.contains("tl-starter-ai");
		}

		boolean hasChat() {
			return deps.contains("tl-chat") || deps.contains("tl-starter-ai");
		}

		Set<String> allKnownRoles() {
			Set<String> known = new TreeSet<>(declaredRoles);
			known.addAll(engine.roles);
			return known;
		}

		boolean appChatSecurity() {
			return grants.containsKey("tl.ai.execution:AgentSession");
		}

		Set<String> typesWithoutRead() {
			Set<String> result = new TreeSet<>();
			for (String type : modelClasses) {
				Map<String, Set<String>> ops = grants.get(type);
				boolean read = ops != null && ops.containsKey("Read") && !ops.get("Read").isEmpty();
				if (!read && !accessParentTypes.contains(type)) {
					result.add(type);
				}
			}
			return result;
		}

		Set<String> missingLabels(Properties labels) {
			Set<String> result = new LinkedHashSet<>();
			for (String key : modelKeys) {
				if (!labels.containsKey(key)) {
					result.add(key);
				}
			}
			return result;
		}

		Set<String> missingTooltips(Properties labels) {
			Set<String> result = new LinkedHashSet<>();
			for (String key : modelKeys) {
				if (!moduleKeys.contains(key) && !labels.containsKey(key + ".tooltip")) {
					result.add(key + ".tooltip");
				}
			}
			return result;
		}

		Map<String, Boolean> secrets() {
			Map<String, Boolean> result = new LinkedHashMap<>();
			for (String[] p : SHIPPED_PROVIDERS) {
				result.put(p[1], secretPresent(p[1]));
			}
			for (String name : namedSecrets) {
				result.putIfAbsent(name, secretPresent(name));
			}
			return result;
		}

		static boolean secretPresent(String name) {
			String env = System.getenv(name);
			if (env != null && !env.isEmpty()) {
				return true;
			}
			Path file = Paths.get(System.getProperty("user.home"), ".top-logic", "secrets", name);
			try {
				return Files.isRegularFile(file) && Files.size(file) > 0;
			} catch (IOException ex) {
				return false;
			}
		}

		static String yn(Boolean b) {
			return b == null ? "unknown (artifact not in the local Maven repository)" : b ? "yes" : "no";
		}

		// ---- detect -----------------------------------------------------------------------

		void printDetect() {
			String ui = uiType();
			println("# Albert doctor: fact sheet");
			println("app.root: " + root);
			println("app.module: " + (module.equals(root) ? "." : rel(module)));
			println("engine.version: " + (engineVersion == null ? "unknown" : engineVersion
				+ " (" + engineVersionSource + ")"));
			println("ui: " + ui + "  (views=" + views.size() + ", layouts=" + layouts.size() + ")");
			println("deps.ai: " + join(filter(deps, "tl-ai", "tl-chat", "tl-starter-ai")));
			println("deps.license-professional: " + (deps.contains("tl-license-professional") ? "yes" : "no"));
			List<String> legacy = legacyFindings();
			println("legacy.tl-ai: " + (legacy.isEmpty() ? "none" : String.join("; ", legacy)));
			println("");
			println("## Engine " + (engineVersion == null ? "(version unknown)" : engineVersion)
				+ " ships (from the local Maven repository)");
			println("engine.chatSecurity (" + ENGINE_CHAT_SECURITY + "): " + yn(engine.chatSecurity));
			println("engine.chatView (views/" + ENGINE_CHAT_VIEW + "): " + yn(engine.chatView));
			println("engine.howtoDoc (doc:" + ENGINE_HOWTO_DOC + "): " + yn(engine.howtoDoc));
			println("engine.chatRestApi (/chat/capabilities): " + yn(engine.chatRestApi));
			println("engine.fragmentsScanned: " + engine.fragments);
			println("");
			println("## Security");
			println("roles.declared (InitialRolesManager): " + join(declaredRoles));
			println("roles.onRoot (singleton-rule): " + describe(rootRoles));
			println("roles.onObjects (reference rule): " + describe(objectRoles));
			println("useDefaultSecurityParent: " + (defaultSecurityParent == null ? "not set (engine default false)"
				: defaultSecurityParent));
			println("securityConfig: " + (securityConfigPresent ? "present" : "MISSING"));
			for (Map.Entry<String, Map<String, Set<String>>> e : grants.entrySet()) {
				println("  grant " + e.getKey() + ": " + e.getValue());
			}
			println("model.classes (non-abstract): " + join(modelClasses));
			println("model.classesWithoutReadGrant: " + join(typesWithoutRead())
				+ "  (a composition part or a type with an access parent can be covered without a grant;"
				+ " the startup log of SecurityCoverageCheck decides)");
			println("gate.modelSecurity: " + gateVerdict());
			println("");
			println("## Albert configuration");
			println("toolAccess.roles: " + (toolAccessRoles.isEmpty() ? "none" : toolAccessRoles.toString()));
			println("agentAccess.default: " + (agentAccessDefault == null ? "not configured (engine default all)"
				: agentAccessDefault) + "  roles=" + join(agentAccessRoles));
			println("backgroundAllowlist: " + (backgroundAllowlist ? "present" : "absent"));
			println("chat.views (with <chat-conversation>): " + join(chatViews));
			println("chat.docks (with <chat-dock>): " + join(chatDocks));
			println("nav.viewRefs: " + join(navViewRefs));
			println("classic.chatTabGrants (security.xml): " + join(classicChatGrants));
			println("chatSecurity.app (grants on tl.ai.execution:AgentSession): " + (appChatSecurity() ? "yes" : "no"));
			println("commandGroups.app: " + join(commandGroups));
			println("promptContributors: " + join(promptContributors));
			println("providers.app (LLMProviderService): " + join(customProviders));
			println("");
			println("## Configured services (file -> service-class), relevant ones only");
			for (String svc : RELEVANT_SERVICES) {
				Set<String> files = filesPerService.get(svc);
				println("  " + simple(svc) + ": " + (files == null ? "-" : String.join(", ", files)));
			}
			println("");
			println("## Secrets (present yes/no; values are never read into the output)");
			for (Map.Entry<String, Boolean> e : secrets().entrySet()) {
				println("  " + e.getKey() + ": " + (e.getValue() ? "yes" : "no"));
			}
			println("metaConf.devel-secrets.xml (deploy/local): "
				+ (develSecretsLine == null ? "no deploy/local metaConf.txt" : develSecretsLine));
			println("");
			println("## Labels (Albert reads the model through them)");
			Set<String> en = missingLabels(labelsEn);
			Set<String> de = missingLabels(labelsDe);
			Set<String> tipEn = missingTooltips(labelsEn);
			Set<String> tipDe = missingTooltips(labelsDe);
			println("model.elements: " + modelKeys.size());
			println("labels.missing.en: " + limit(en));
			println("labels.missing.de: " + limit(de));
			println("tooltips.missing.en: " + limit(tipEn));
			println("tooltips.missing.de: " + limit(tipDe));
			if (!unparsable.isEmpty()) {
				println("");
				println("unparsable.xml: " + join(unparsable));
			}
			println("");
			printSteps(ui, legacy);
		}

		String gateVerdict() {
			if (!securityConfigPresent) {
				return "STOP - no <security-config>: a non-admin account (and Albert for it) sees nothing";
			}
			if (rootRoles.isEmpty()) {
				return "STOP - no role on the security root (singleton-rule): no role can get Albert tool rights";
			}
			Set<String> missing = typesWithoutRead();
			if (!missing.isEmpty()) {
				return "CHECK - types without own Read grant: " + join(missing);
			}
			return "PASS (confirm with the SecurityCoverageCheck findings in the startup log)";
		}

		List<String> legacyFindings() {
			List<String> result = new ArrayList<>();
			if (oldAiVersionProperty) {
				result.add("pom uses tl.ai.version");
			}
			for (String f : llmConfiguration) {
				result.add("<llm-configuration> in " + f);
			}
			for (String a : aliasKeys) {
				result.add("alias key " + a);
			}
			return result;
		}

		void printSteps(String ui, List<String> legacy) {
			println("## Steps (APPLY = to do, DONE = found, SKIP = not needed, ASK = decide with the user)");
			String gate = gateVerdict();
			println("G  model security gate: " + gate);
			if (!legacy.isEmpty()) {
				println("L  migrate from the separate tl-ai artifacts: APPLY - " + String.join("; ", legacy));
			}
			boolean depsDone = hasAiCore() && hasChat()
				&& (!(ui.equals("classic") || ui.equals("both")) || deps.contains("tl-chat-classic"));
			println("1  dependencies: " + (depsDone ? "DONE" : "APPLY") + " - have " + join(filter(deps, "tl-ai", "tl-chat", "tl-starter-ai"))
				+ (ui.equals("classic") ? "; classic UI needs tl-chat-classic" : "; never tl-chat-classic in a React-only app")
				+ (deps.contains("tl-license-professional") ? "" : "; check tl-license-professional if the app uses the Professional Edition"));
			boolean anySecret = secrets().values().stream().anyMatch(b -> b);
			println("2  provider and key: " + (anySecret ? "DONE (a key is present here)" : "APPLY (no key found here)")
				+ (customProviders.isEmpty() ? "" : " - own providers: " + join(customProviders))
				+ ("missing".equals(develSecretsLine) ? " - add devel-secrets.xml to deploy/local metaConf.txt" : ""));
			if (ui.equals("classic")) {
				println("3B classic Albert tab: " + (classicChatGrants.isEmpty() ? "APPLY (security.xml has no tl-chat view grant)" : "DONE"));
			} else {
				boolean page = !chatViews.isEmpty() || navViewRefs.contains(ENGINE_CHAT_VIEW);
				String variant = Boolean.TRUE.equals(engine.chatView)
					? "use the shipped page: <view-ref view=\"" + ENGINE_CHAT_VIEW + "\"/>"
					: "use templates/albert.view.xml (engine ships no chat page)";
				println("3A React Albert page: " + (page ? "DONE - " + join(chatViews) : "APPLY - " + variant)
					+ (chatDocks.isEmpty() ? "" : "; dock in " + join(chatDocks)));
			}
			String step4;
			if (Boolean.TRUE.equals(engine.chatSecurity)) {
				step4 = "SKIP - the engine ships it (" + ENGINE_CHAT_OWNER_ROLE + ")"
					+ (appChatSecurity() ? "; the app still has its own chat grants: remove them" : "");
			} else if (appChatSecurity()) {
				step4 = "DONE - the app grants on tl.ai.execution:AgentSession";
			} else if (engine.chatSecurity == null) {
				step4 = "ASK - build the app once so the engine artifacts are local, then run detect again";
			} else {
				step4 = "APPLY - templates/chat-security.config.xml (engine " + engineVersion + " ships no chat security)";
			}
			println("4  own chats readable: " + step4);
			println("5  Albert rights per role (AiToolAccessService): " + (toolAccessRoles.isEmpty() ? "APPLY" : "DONE - " + toolAccessRoles.keySet())
				+ ("none".equals(agentAccessDefault) ? "" : "; set <agent-access default=\"none\">"));
			Set<String> labels = new LinkedHashSet<>(missingLabels(labelsEn));
			labels.addAll(missingLabels(labelsDe));
			println("6  domain knowledge: " + (promptContributors.contains(PROMPT_CONTRIBUTOR) ? "DONE" : "APPLY")
				+ " (contributor \"" + PROMPT_CONTRIBUTOR + "\"); model labels missing: " + labels.size());
			println("7  test as non-admin accounts: APPLY");
			println("8  operation (key per environment, customer approval of the provider): APPLY");
			println("");
			println("engine.names: agent=" + AGENT + " chatView=" + ENGINE_CHAT_VIEW + " howto=doc:" + ENGINE_HOWTO_DOC
				+ " chatOwnerRole=" + ENGINE_CHAT_OWNER_ROLE + " fallbackOwnerRole=" + FALLBACK_OWNER_ROLE);
		}

		// ---- check ------------------------------------------------------------------------

		int printCheck() {
			List<Finding> findings = new ArrayList<>();
			checkServicesTwice(findings);
			checkViews(findings);
			checkRoles(findings);
			checkOperations(findings);
			checkAiConfig(findings);
			checkKeys(findings);
			for (String u : unparsable) {
				findings.add(new Finding(true, u, "not well-formed XML"));
			}
			int errors = 0;
			int warnings = 0;
			println("# Albert doctor: check " + root);
			for (Finding f : findings) {
				println((f.error ? "ERROR " : "WARN  ") + f.where + ": " + f.text);
				if (f.error) {
					errors++;
				} else {
					warnings++;
				}
			}
			println("result: " + errors + " error(s), " + warnings + " warning(s)"
				+ (engine.scanned ? "" : "; engine artifacts of " + engineVersion
					+ " not in the local Maven repository: engine roles and views not checked"));
			return errors;
		}

		void checkServicesTwice(List<Finding> out) {
			for (Map.Entry<String, Map<String, Integer>> e : servicesPerFile.entrySet()) {
				for (Map.Entry<String, Integer> s : e.getValue().entrySet()) {
					if (s.getValue() > 1) {
						out.add(new Finding(true, e.getKey(), "service-class " + s.getKey() + " is configured "
							+ s.getValue() + " times; merge into one <config>"));
					}
				}
			}
		}

		void checkViews(List<Finding> out) {
			Set<String> appViews = new TreeSet<>();
			Path viewDir = webInf.resolve("views");
			for (XmlFile f : views) {
				appViews.add(viewDir.relativize(f.path).toString().replace('\\', '/'));
			}
			for (XmlFile f : views) {
				Map<String, Integer> ids = new TreeMap<>();
				for (Element e : Xml.all(f.doc.getDocumentElement(), "*")) {
					String id = e.getAttribute("id");
					if (!id.isEmpty()) {
						ids.merge(id, 1, Integer::sum);
					}
				}
				for (Map.Entry<String, Integer> id : ids.entrySet()) {
					if (id.getValue() > 1) {
						out.add(new Finding(true, f.rel, "id \"" + id.getKey() + "\" occurs " + id.getValue()
							+ " times; an id must be unique in the whole view"));
					}
				}
				for (Element ref : Xml.all(f.doc.getDocumentElement(), "view-ref")) {
					String v = ref.getAttribute("view");
					if (!v.isEmpty() && !appViews.contains(v) && !engine.views.contains(v)) {
						out.add(new Finding(engine.scanned, f.rel, "<view-ref view=\"" + v + "\"> names no view of the app"
							+ (engine.scanned ? " or of the engine " + engineVersion : " (engine not checked)")));
					}
				}
				checkScripts(f, f.doc.getDocumentElement(), out);
			}
			if (!views.isEmpty() && deps.contains("tl-chat-classic") && layouts.isEmpty()) {
				out.add(new Finding(false, "pom.xml", "tl-chat-classic in a React-only app moves the start and login"
					+ " page to the LayoutServlet; remove it"));
			}
		}

		static final Pattern LAMBDA_HEAD = Pattern.compile("^\\s*[A-Za-z_][A-Za-z0-9_]*\\s*->");

		void checkScripts(XmlFile f, Element e, List<Finding> out) {
			for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
				if (n.getNodeType() == Node.CDATA_SECTION_NODE) {
					String script = n.getNodeValue();
					boolean positional = Xml.ancestor(e, "data-list") != null || Xml.ancestor(e, "context") != null
						|| e.getTagName().equals("suggestions");
					if (!positional && LAMBDA_HEAD.matcher(script).find()) {
						out.add(new Finding(false, f.rel, "<" + e.getTagName() + ">: script starts with a lambda head"
							+ " (\"" + script.trim().split("->")[0].trim() + " ->\"); since 8.0.0-alpha12 view scripts take"
							+ " their inputs as variables $<name>, only <data-list> scripts take positional arguments"));
					}
					if (positional && e.getTagName().equals("rows") && Xml.child(Xml.ancestor(e, "data-list"), "inputs") != null
						&& !LAMBDA_HEAD.matcher(script).find()) {
						out.add(new Finding(true, f.rel, "<data-list><rows> with <inputs> needs a lambda head"
							+ " (\"session -> ...\"); a $<channel> variable fails with \"Undefined variable\""));
					}
				} else if (n instanceof Element) {
					checkScripts(f, (Element) n, out);
				}
			}
		}

		void checkRoles(List<Finding> out) {
			Set<String> known = allKnownRoles();
			Map<String, Set<String>> used = new TreeMap<>();
			for (Map.Entry<String, Map<String, Set<String>>> e : grants.entrySet()) {
				for (Set<String> roles : e.getValue().values()) {
					for (String r : roles) {
						used.computeIfAbsent(r, k -> new TreeSet<>()).add("<security-config> " + e.getKey());
					}
				}
			}
			for (String r : toolAccessRoles.keySet()) {
				used.computeIfAbsent(r, k -> new TreeSet<>()).add("<tool-access>");
			}
			for (String r : agentAccessRoles) {
				used.computeIfAbsent(r, k -> new TreeSet<>()).add("<agent-access>");
			}
			for (String r : rootRoles.keySet()) {
				used.computeIfAbsent(r, k -> new TreeSet<>()).add("singleton-rule");
			}
			for (String r : objectRoles.keySet()) {
				used.computeIfAbsent(r, k -> new TreeSet<>()).add("role rule");
			}
			for (Map.Entry<String, Set<String>> u : used.entrySet()) {
				if (!known.contains(u.getKey())) {
					out.add(new Finding(engine.scanned, "roles", "role \"" + u.getKey() + "\" used in " + u.getValue()
						+ " is declared nowhere (InitialRolesManager of the app"
						+ (engine.scanned ? " or of the engine" : "; engine not checked") + ")"));
				}
			}
			for (String r : toolAccessRoles.keySet()) {
				if (!rootRoles.containsKey(r)) {
					out.add(new Finding(false, "<tool-access>", "role \"" + r + "\" has no singleton-rule on"
						+ " SecurityStructure#ROOT; tool access asks for the role on the security root, so nobody gets these tools"));
				}
			}
			for (String r : agentAccessRoles) {
				if (!rootRoles.containsKey(r)) {
					out.add(new Finding(false, "<agent-access>", "role \"" + r + "\" has no singleton-rule on"
						+ " SecurityStructure#ROOT; nobody gets Albert through it"));
				}
			}
			if (!Boolean.TRUE.equals(defaultSecurityParent)) {
				for (Map.Entry<String, Map<String, Set<String>>> e : grants.entrySet()) {
					if (e.getKey().contains(":") && !e.getKey().startsWith("tl.ai.")) {
						for (Map.Entry<String, Set<String>> op : e.getValue().entrySet()) {
							for (String r : op.getValue()) {
								if (rootRoles.containsKey(r) && !objectRoles.containsKey(r)) {
									out.add(new Finding(false, "<security-config> " + e.getKey(), "grant " + op.getKey()
										+ " to \"" + r + "\": the role is only held on the security root, which is no"
										+ " security parent of the objects; add a role rule that puts it on the objects"));
								}
							}
						}
					}
				}
			}
			if (Boolean.TRUE.equals(engine.chatSecurity) && appChatSecurity()) {
				out.add(new Finding(false, "<security-config> tl.ai.execution:AgentSession", "engine " + engineVersion
					+ " ships owner access to chats (" + ENGINE_CHAT_OWNER_ROLE + "); remove the app's own chat grants"));
			}
			if (Boolean.FALSE.equals(engine.chatSecurity) && !appChatSecurity() && hasChat()) {
				out.add(new Finding(true, "chat security", "engine " + engineVersion + " ships no chat security and the app"
					+ " grants nothing on tl.ai.execution:AgentSession; the chat list of a non-admin stays empty"
					+ " (templates/chat-security.config.xml)"));
			}
		}

		void checkOperations(List<Finding> out) {
			Set<String> registered = new TreeSet<>(commandGroups);
			registered.addAll(engine.commandGroups);
			for (XmlFile f : views) {
				for (Element ac : Xml.all(f.doc.getDocumentElement(), "access-control")) {
					String op = ac.getAttribute("operation");
					if (op.isEmpty()) {
						continue;
					}
					if (!registered.contains(op)) {
						out.add(new Finding(engine.scanned, f.rel, "operation \"" + op + "\" is registered in no"
							+ " CommandGroupRegistry; the application does not start (\"The command group ... does not exist\")"));
					} else if (commandGroups.contains(op)) {
						for (String lang : new String[] { "en", "de" }) {
							Properties p = lang.equals("en") ? labelsEn : labelsDe;
							for (String key : new String[] { "commandGroup." + op, "commandGroup." + op + ".tooltip" }) {
								if (!p.containsKey(key)) {
									out.add(new Finding(false, f.rel, "operation \"" + op + "\": key " + key
										+ " missing in conf/resources/*_" + lang + ".properties"));
								}
							}
						}
					}
				}
			}
		}

		void checkAiConfig(List<Finding> out) {
			if (!hasAiCore()) {
				return;
			}
			if (filesPerService.containsKey(SVC_TOOL_ACCESS) && !"none".equals(agentAccessDefault)) {
				out.add(new Finding(false, "AiToolAccessService", "no <agent-access default=\"none\">: every account that"
					+ " can log in can talk to Albert"));
			} else if (!filesPerService.containsKey(SVC_TOOL_ACCESS)) {
				out.add(new Finding(false, "AiToolAccessService", "not configured: no role gets Albert tools by role, and"
					+ " every account that can log in can talk to Albert"));
			}
			if (!toolAccessRoles.isEmpty() && !backgroundAllowlist) {
				out.add(new Finding(true, "AiToolAccessService", "<tool-access> names roles but there is no"
					+ " <background-allowlist>; the service does not boot"));
			}
			for (String f : llmConfiguration) {
				out.add(new Finding(true, f, "<llm-configuration> stops the start since 8.0.0-alpha12; remove it"));
			}
			for (String a : aliasKeys) {
				out.add(new Finding(false, a, "API key from an alias; use <api-key name=\"...\"/> (a named secret)"));
			}
			if (oldAiVersionProperty) {
				out.add(new Finding(true, "pom.xml", "tl.ai.version: the AI modules are engine modules now; remove the"
					+ " property and every <version>${tl.ai.version}</version>"));
			}
		}

		static final Pattern[] KEY_PATTERNS = {
			Pattern.compile("sk-ant-[A-Za-z0-9_\\-]{20,}"),
			Pattern.compile("sk-(proj-|svcacct-)?[A-Za-z0-9_\\-]{20,}"),
			Pattern.compile("xai-[A-Za-z0-9]{20,}"),
			Pattern.compile("AKIA[0-9A-Z]{16}"),
			Pattern.compile("AIza[0-9A-Za-z_\\-]{35}"),
			Pattern.compile("gh[pousr]_[A-Za-z0-9]{36}"),
			Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
			Pattern.compile("api-key\\s*=\\s*\"(?!%|unencrypted:%|\")[^\"]{12,}\""),
			Pattern.compile("(?i)(api[_-]?key|secret|token|password)\\s*[=:]\\s*[\"']?[A-Za-z0-9_\\-]{32,}"),
		};

		static final Set<String> SCANNED_SUFFIXES = Set.of(".xml", ".properties", ".txt", ".json", ".sh", ".yml",
			".yaml", ".env", ".conf", ".cfg", ".ini", ".bat", ".cmd", ".ps1", ".template");

		void checkKeys(List<Finding> out) {
			try (Stream<Path> walk = Files.walk(root)) {
				walk.filter(Files::isRegularFile)
					.filter(p -> !ignored(root.relativize(p)) || p.getFileName().toString().equals(".env"))
					.filter(p -> {
						String n = p.getFileName().toString();
						return SCANNED_SUFFIXES.stream().anyMatch(n::endsWith) || n.equals(".env");
					})
					.forEach(p -> scanForKeys(p, out));
			} catch (IOException ex) {
				out.add(new Finding(false, ".", "could not scan for keys: " + ex.getMessage()));
			}
			String diff = gitDiff();
			if (diff == null) {
				out.add(new Finding(false, "git diff", "not scanned (no git repository or no git)"));
				return;
			}
			String file = "git diff";
			int line = 0;
			for (String l : diff.split("\n", -1)) {
				if (l.startsWith("+++ ")) {
					file = "git diff " + l.substring(4).trim();
					line = 0;
					continue;
				}
				if (l.startsWith("@@")) {
					Matcher m = Pattern.compile("\\+(\\d+)").matcher(l);
					line = m.find() ? Integer.parseInt(m.group(1)) - 1 : 0;
					continue;
				}
				if (l.startsWith("+")) {
					line++;
					String kind = keyKind(l);
					if (kind != null) {
						out.add(new Finding(true, file + ":" + line, "added line looks like a secret (" + kind
							+ "); value not shown. Never commit a key"));
					}
				} else if (!l.startsWith("-")) {
					line++;
				}
			}
		}

		void scanForKeys(Path p, List<Finding> out) {
			try {
				if (Files.size(p) > 2_000_000) {
					return;
				}
				List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
				for (int i = 0; i < lines.size(); i++) {
					String kind = keyKind(lines.get(i));
					if (kind != null) {
						out.add(new Finding(true, rel(p) + ":" + (i + 1), "looks like a secret (" + kind
							+ "); value not shown. Use a named secret (<api-key name=\"...\"/>) and set the value per environment"));
					}
				}
			} catch (IOException ex) {
				// Binary or unreadable: not a configuration file.
			}
		}

		static String keyKind(String line) {
			String[] kinds = { "Anthropic key", "OpenAI-style key", "xAI key", "AWS access key", "Google API key",
				"GitHub token", "private key", "literal api-key attribute", "long literal credential" };
			for (int i = 0; i < KEY_PATTERNS.length; i++) {
				if (KEY_PATTERNS[i].matcher(line).find()) {
					return kinds[i];
				}
			}
			return null;
		}

		String gitDiff() {
			try {
				Process p = new ProcessBuilder("git", "-C", root.toString(), "diff", "HEAD", "--no-color", "--unified=0")
					.redirectErrorStream(false).start();
				p.getOutputStream().close();
				byte[] bytes = readAll(p.getInputStream());
				if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
					return null;
				}
				return new String(bytes, StandardCharsets.UTF_8);
			} catch (IOException ex) {
				return null;
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return null;
			}
		}
	}

	// ---------------------------------------------------------------------------------------

	static String simple(String className) {
		return className.substring(className.lastIndexOf('.') + 1);
	}

	static Set<String> filter(Set<String> values, String... prefixes) {
		Set<String> result = new TreeSet<>();
		for (String v : values) {
			for (String p : prefixes) {
				if (v.startsWith(p)) {
					result.add(v);
				}
			}
		}
		return result;
	}

	static String join(Collection<String> values) {
		return values.isEmpty() ? "none" : String.join(", ", values);
	}

	static String limit(Set<String> values) {
		if (values.isEmpty()) {
			return "none";
		}
		List<String> list = new ArrayList<>(values);
		String head = String.join(", ", list.subList(0, Math.min(15, list.size())));
		return values.size() + " - " + head + (list.size() > 15 ? ", ..." : "");
	}

	static String describe(Map<String, Set<String>> map) {
		if (map.isEmpty()) {
			return "none";
		}
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, Set<String>> e : map.entrySet()) {
			parts.add(e.getKey() + (e.getValue().isEmpty() ? "" : " " + e.getValue()));
		}
		return String.join(", ", parts);
	}

	static List<String> split(String roles) {
		List<String> result = new ArrayList<>();
		for (String r : roles.split("[,\\s]+")) {
			if (!r.isEmpty()) {
				result.add(r);
			}
		}
		return result;
	}

	static void println(String s) {
		System.out.println(s);
	}

	// ---------------------------------------------------------------------------------------

	/** Small DOM helpers. Not namespace aware: tags and attributes are matched by their qualified name. */
	static final class Xml {
		static Document parse(Path file) {
			try (InputStream in = Files.newInputStream(file)) {
				return parse(in);
			} catch (IOException ex) {
				return null;
			}
		}

		static Document parse(InputStream in) {
			try {
				DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
				factory.setNamespaceAware(false);
				factory.setExpandEntityReferences(false);
				factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
				factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
				factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
				DocumentBuilder builder = factory.newDocumentBuilder();
				builder.setErrorHandler(null);
				builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
				return builder.parse(in);
			} catch (Exception ex) {
				return null;
			}
		}

		/** All descendant elements with the given tag ("*": all). */
		static List<Element> all(Element root, String tag) {
			List<Element> result = new ArrayList<>();
			if (root == null) {
				return result;
			}
			NodeList list = root.getElementsByTagName(tag);
			for (int i = 0; i < list.getLength(); i++) {
				result.add((Element) list.item(i));
			}
			return result;
		}

		static List<Element> services(Document doc, String serviceClass) {
			List<Element> result = new ArrayList<>();
			for (Element config : all(doc.getDocumentElement(), "config")) {
				if (serviceClass.equals(config.getAttribute("service-class"))) {
					result.add(config);
				}
			}
			return result;
		}

		static List<Element> children(Element parent) {
			return children(parent, null);
		}

		static List<Element> children(Element parent, String tag) {
			List<Element> result = new ArrayList<>();
			for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
				if (n instanceof Element && (tag == null || ((Element) n).getTagName().equals(tag))) {
					result.add((Element) n);
				}
			}
			return result;
		}

		static Element child(Element parent, String tag) {
			if (parent == null) {
				return null;
			}
			List<Element> c = children(parent, tag);
			return c.isEmpty() ? null : c.get(0);
		}

		static String childText(Element parent, String tag) {
			Element c = child(parent, tag);
			return c == null ? null : c.getTextContent().trim();
		}

		static Element ancestor(Element e, String tag) {
			for (Node n = e.getParentNode(); n instanceof Element; n = n.getParentNode()) {
				if (((Element) n).getTagName().equals(tag)) {
					return (Element) n;
				}
			}
			return null;
		}
	}
}
