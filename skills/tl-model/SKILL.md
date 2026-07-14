---
name: tl-model
description: Reference for TopLogic dynamic-type model XML — defining application data types (classes, interfaces, enums) in `*.model.xml`, declaring attributes and references, derived attributes, label/ID conventions, and the wrapper-generator build wiring. Invoke when authoring or modifying `*.model.xml` files, configuring `package-binding`, or troubleshooting generated wrappers.
allowed-tools: Bash, Read, Glob, Grep
---

# TopLogic Model Reference

TopLogic application data types live in `*.model.xml` files under `src/main/webapp/WEB-INF/model/`. Each file declares one **module**, registered with the application's `ModelService`. The model is loaded at startup; Java wrappers can optionally be generated from it.

## File layout

```xml
<model xmlns="http://www.top-logic.com/ns/dynamic-types/6.0">
    <module name="my.module">
        <annotations>
            <package-binding
                implementation-package="com.example.app.model.impl"
                interface-package="com.example.app.model"
            />
        </annotations>
        <types>
            <class name="Thing"> ... </class>
            <interface name="Named"> ... </interface>
            <enum name="State"> ... </enum>
            <datatype name="DemoVec" db_type="string" kind="Custom"/>
        </types>
    </module>
</model>
```

Register every model file in `WEB-INF/autoconf/<app>.config.xml`:

```xml
<config service-class="com.top_logic.util.model.ModelService">
    <instance class="com.top_logic.element.model.DynamicModelService">
        <declarations>
            <declaration file="/WEB-INF/model/my.module.model.xml"/>
        </declarations>
    </instance>
</config>
```

## Types

**`<class name="...">`** — instantiable. Can be subclassed via `<generalization type="..."/>` (single or multiple). Every root extends `tl.model:TLObject`. Cannot be marked abstract — for abstract types use `<interface>`.

**`<interface name="...">`** — abstract. Same generalization / attribute syntax as classes. Concrete classes implement them via `<generalization type="MyInterface"/>`.

**`<enum name="...">`** — enumeration with `<classifier name="..."/>` entries. Can map to a Java enum via `<enum-storage enum="java.lang.Enum.Name"/>` inside `<annotations>`.

**`<datatype name="..." db_type="..." kind="...">`** — custom value type with a storage strategy.

## Attribute types

`<property>` carries primitives (literals stored on the row). `<reference>` carries links to other model instances — **including enum classifiers**.

**Enums are referenced, not propertied.** An attribute typed by an `<enum>` declared in the same module (or imported from another) must be a `<reference>`, never a `<property>`. Using `<property>` is silent at startup until the first commit tries to write that attribute, then explodes as `ClassCastException: PersistentEnumeration cannot be cast to TLPrimitive` during DynamicModelService startup — a confusing error that easily gets misdiagnosed as a stale DB or migration issue.

```xml
<!-- WRONG: enum as property -->
<property name="kind" type="my.module:Status" mandatory="true"/>

<!-- RIGHT: enum as reference -->
<reference name="kind" type="my.module:Status" mandatory="true"/>
```

### Primitive type literals

`tl.core:String`, `Integer`, `Long`, `Boolean`, `Double`, `Float`, `Date`, `DateTime`, `Time`, `Duration`, `Binary`, `Icon`, `Name`, `Password`, `Text`, `Tristate`, `Revision`.

### Internationalized and rich text

For human-readable prose that should be translatable, prefer the internationalized value types over `tl.core:String`/`Text`. They live in module `tl.model.i18n` (shipped by `tl-model-wysiwyg`, normally already on the classpath of an app built on `tl-parent-app`):

- `tl.model.i18n:I18NString` — a single-line string carrying a separate value per language (good for titles/summaries).
- `tl.model.i18n:I18NHtml` — internationalized rich text, edited with a WYSIWYG (CKEditor) control; the right home for description/comment bodies and any wiki-/HTML-style content.

`tl.core:Html` is the non-internationalized rich-text variant. Author prose in the source language; other languages stay empty for later translation. Use these instead of a plain `Text` whenever content is user-facing and either formatted or shown to a mixed-language audience.

### Property example

```xml
<property name="lexicalForm" type="tl.core:String" mandatory="true"/>
<property name="cardinality" type="tl.core:Integer"/>
```

### Reference example

```xml
<reference name="datatype" type="Datatype" mandatory="true"/>
<reference name="operands" type="ClassExpression"
    composite="true" kind="forwards" multiple="true" ordered="true"/>
```

Reference attributes:
- `multiple="true"` — collection. Without `ordered="true"`, set semantics. With it, list.
- `composite="true"` — *owning* relationship: the target is deleted when the source is deleted, and the target lives inside the source for serialization purposes. Always pair with `kind="forwards"`.
- `kind="forwards"` / `kind="backwards"` — the direction the link is materialized.
- `inverse-reference="..."` — declares this side as the inverse of another reference for bidirectional navigation.
- `mandatory="true"` — at least one value required.
- `navigate="true"` — make the reference traversable (defaults are usually fine).
- `override="true"` — overriding an inherited attribute (must match the parent's type exactly or be a covariant subtype).

### Annotations on attributes / classes

Wrap in `<annotations>...</annotations>` directly inside `<property>`, `<reference>`, `<class>`, or `<interface>`:

```xml
<class name="Foo">
    <annotations>
        <id-column value="iri"/>
        <main-properties properties="name,description"/>
    </annotations>
    ...
</class>
```

Common annotations:
- `<id-column value="...">` — the attribute used as the object's display label (must point at a **primitive** property, not a reference).
- `<main-properties properties="a,b,c"/>` — preferred columns / form fields.
- `<storage-algorithm>` — defines a derived attribute (see below).
- `<default-value>`, `<options>`, `<form-definition>`, `<visibility>`, `<create-visibility>` — UI configuration.

## Derived (computed) attributes

A derived attribute has no stored value; it is computed from a TL Script expression every time it's read. Declare as a normal `<property>` or `<reference>` with a `<storage-algorithm>` annotation:

```xml
<property name="localName" type="tl.core:String">
    <annotations>
        <storage-algorithm>
            <query>
                <expr><![CDATA[e -> regex("^.*[#/]").regexReplace($e.get(`my.module:Entity#iri`), "")]]></expr>
            </query>
        </storage-algorithm>
    </annotations>
</property>
```

The expression is a single-argument lambda (`<receiver> -> <expression>`) whose parameter is the model object itself. Use TL Script — see the `tl-script` skill.

A derived `<reference name="..." multiple="true">` returns a collection from its expression. Without `ordered="true"`, duplicates collapse into a set.

**XML attribute escaping**: TL Script expressions inside attribute *values* (not CDATA) must escape `&` as `&amp;` and `<` as `&lt;`. Prefer the `and` / `or` keywords over `&&` / `||`, and CDATA `<expr><![CDATA[...]]></expr>` when expressions are long.

## Creation / modification dates and authors

Every persistent object carries a full KB revision history, so "created / modified" and "who created it" need no bookkeeping — but there are two modelling choices with different trade-offs:

**A. Derive from the revision history (no stored column).** A `<storage-algorithm>` reading the object's create / last-change revision:

```xml
<!-- created -->  x -> $x.createRevision().get(`tl.core:Revision#date`)
<!-- modified --> x -> $x.modifiedRevision().get(`tl.core:Revision#date`)
<!-- author -->   x -> $x.createRevision().get(`tl.core:Revision#author`)
```

`Revision#date` is a `DateTime`; `Revision#author` is a `tl.core:Author` (an interface `Person` implements — type such a derived reference as `tl.core:Author`, not `Person`, so technical/unresolved authors still fit). Always accurate and un-spoofable, but **not settable** — preserving historical values on import means replaying history (one commit per change, committed as the original author). Fine for timestamps you never backfill.

**B. Store it, defaulting to the current user / now at creation.** A real attribute with a computed default:

```xml
<reference name="author" type="tl.accounts:Person">
    <annotations>
        <default-value>
            <default-by-expression value="ctx -> currentUser()"/>
        </default-value>
        <visibility value="read-only"/>   <!-- auto-fill and lock in the UI; omit to keep editable -->
    </annotations>
</reference>
```

Auto-filled like (A), but because it is a stored column an **import can set it explicitly** (`read-only` is only a UI constraint), preserving the original author/date in a single transaction.

Rule of thumb: **derive** timestamps (rarely backfilled); for an **author/reporter that must survive a bulk import**, prefer **B** (stored + `currentUser()` default), since deriving it would force a separate commit per imported object.

## Generated numbers (sequences)

To auto-assign a running number or formatted identifier at commit, annotate the attribute with a `<default-value>` provider — do **not** hand-write a create handler for this:

- integer attribute → `com.top_logic.element.structured.util.SequenceDefaultProvider`
- string attribute with formatting (e.g. `%NUMBER%` plus a number/date pattern) → `com.top_logic.element.structured.util.NumberHandlerDefaultProvider`

```xml
<property name="number" type="tl.core:Integer">
    <annotations>
        <default-value>
            <provider class="com.top_logic.element.structured.util.SequenceDefaultProvider"
                sequence-name="my.module.Thing.number"
            >
                <!-- Optional: restart the sequence per creation context (e.g. per container). -->
                <dynamic-sequence-name class="com.top_logic.element.structured.util.ContextAwareSequenceName"/>
            </provider>
        </default-value>
        <create-visibility value="hidden"/>
        <visibility value="read-only"/>
    </annotations>
</property>
```

The number is allocated during **commit** (to stay gap-free), so the attribute must be hidden in the create form (`<create-visibility value="hidden"/>`) and is normally `read-only`. `ContextAwareSequenceName` scopes the sequence to the object passed as the *creation context* — the container relative to which the object is created — so each context restarts at 1 (e.g. per-project ticket numbers). Omit `<dynamic-sequence-name>` for a single global sequence.

**Limitation — context and numbered object created in one transaction (TL #29380):** context-aware numbering builds the sequence discriminator from `context.tId()`. If the context object is created in the *same* transaction as the numbered object (e.g. an instance editor or an import that creates container and child in one commit), the context is still transient (`tId() == null`) and generation fails with a `NullPointerException` that rolls back the whole save. Avoid it by committing the context object first, or by supplying the number explicitly on import (a provided value skips the default). A global sequence (no `dynamic-sequence-name`) is never affected.

## Referential integrity: scoping options and preventing cycles

Two complementary annotations keep references pointing at *valid* targets. They operate at different layers — combine them.

**Scope the candidate list with `<options>`.** An `options-by-expression` computes the selectable values for a reference from the edited object (`self`), e.g. restrict a ticket's `component` to its own project's components:

```xml
<reference name="components" type="Component" multiple="true">
    <annotations>
        <options>
            <options-by-expression function="self -> $self.get(`m:Ticket#project`).get(`m:Project#components`)"/>
        </options>
    </annotations>
</reference>
```

Use the CDATA `<function><![CDATA[self -> { ... }]]></function>` form for multi-line logic (guard a null container with `if($x == null, null, …)`). **Caveat:** options only constrain what the *UI offers* — a programmatic write or an import can still set an out-of-scope value. For a hard guarantee use a constraint.

**Enforce rules at commit with `<constraints>`.** A `<constraint-by-expression>` runs a check `value -> object -> <error ResKey or null>` and rejects the transaction on violation (works for imports too). For the specific and common "this reference graph must stay acyclic" rule, use the built-in check instead of hand-rolling it:

```xml
<reference name="children" type="Ticket" inverse-reference="parent" kind="forwards" multiple="true">
    <annotations>
        <constraints>
            <constraint class="com.top_logic.model.util.NoAttributeCycle"/>
        </constraints>
    </annotations>
</reference>
```

`NoAttributeCycle` walks the whole reachable graph, so it rejects **deep/indirect** cycles (`a → b → c → a`), not just direct self-references — do **not** reimplement this with a `recursion()`-based options filter, which only sees immediate neighbours and is bypassable. Put it on **one** (the forwards) side of an inverse pair: editing either end updates the inverse, which triggers the constrained side. For a cycle that can span **several different** references, set `additional-observed-references` to the others (and add a matching `NoAttributeCycle` on each).

Rule of thumb: `<options>` for a clean candidate list (UX), `NoAttributeCycle` / `<constraint-by-expression>` for integrity that must hold regardless of how the value is written.

## Language convention: English identifiers, localized labels

Type names, attribute names, and enum classifier names in `*.model.xml` should be **English** — both for code-Java-binding consistency and for cross-locale clarity. Human-readable labels and tooltips live in `*.messages_<lang>.properties` files, **not** in the XML.

Standard property keys (one entry per type/attribute/classifier, plus optional `.tooltip` for descriptions shown as hover text in the UI):

```
model.<module> = <Module label>
model.<module>.<Type> = <Type label>
model.<module>.<Type>.tooltip = <Type description>
model.<module>.<Type>.<attribute> = <Attribute label>
model.<module>.<Type>.<attribute>.tooltip = <Attribute description>
model.<module>.<Enum>.<Classifier> = <Classifier label>
model.<module>.<Enum>.<Classifier>.tooltip = <Classifier description>  (optional)
```

Files live in `src/main/webapp/WEB-INF/conf/resources/model.<module>.messages_en.properties` and `..._de.properties` (and similar per locale). The bundle is registered in `WEB-INF/autoconf/model.<module>.config.xml` under `ResourcesModule`.

**Avoid** German classifier names like `<classifier name="Eröffnung"/>` — they bleed into generated Java identifiers, surface in URLs and IDs, and entangle code with one specific locale. Use `Opening` in the XML, "Eröffnung" in `messages_de.properties`. Document each type and non-obvious attribute with a `.tooltip` entry — these become the hover text users will rely on.

### Properties-file encoding

TopLogic follows the **Java Properties standard**: `*.properties` files are encoded in **ISO-8859-1** (Latin-1). Umlauts and other Latin-1 characters are stored as their native single-byte ISO-8859-1 representation — `Ä` is the byte `0xC4`, not the UTF-8 sequence `0xC3 0x84`. Writing the file in UTF-8 triggers the build warning `Resource check: Not normalized` and risks mojibake when read.

```properties
# Correct — Latin-1 encoded, umlauts stored as single Latin-1 bytes
model.finanzen.TransactionKind.Opening = Eröffnung
```

**Working with these files from Claude / tooling that defaults to UTF-8:** the file/edit tools read and write UTF-8, so directly editing an ISO-8859-1 file corrupts it. Use a UTF-8 working copy and convert with `iconv`:

1. Convert to UTF-8 for editing:
   ```bash
   iconv -f ISO-8859-1 -t UTF-8 messages_de.properties > messages_de.utf8.properties
   ```

2. Edit the UTF-8 working copy with normal tools.

3. Convert back to ISO-8859-1 and replace the original:
   ```bash
   iconv -f UTF-8 -t ISO-8859-1 messages_de.utf8.properties > messages_de.properties
   rm messages_de.utf8.properties
   ```

`iconv` errors out on characters that don't exist in ISO-8859-1 (e.g. `—`, `„`, `"`, `…`) — use plain ASCII substitutes (`--`, `"`, `"`, `...`) in the source, or escape them as `\uXXXX` if you really need them.

## ID / label conventions

The UI displays a model instance by reading one of its primitive properties. Pick it via `<id-column value="...">` on the class.

If no primitive identifier exists naturally, add a derived `name: String` property — the attribute named `name` is implicitly used as the ID. For polymorphic display (different shapes per subclass), give each concrete subclass its own derived `name` with type-specific formatting. Use `$obj.label()` from TL Script anywhere you want the label of an arbitrary object.

**Don't** point `id-column` at a reference. The referenced object would then need its own id-column, cascading the problem. Always resolve to a primitive (`String`, `Integer`, …) — derived if necessary.

## Wrapper generation

To generate Java interfaces / impl classes from the model, add `package-binding` to the module (see file layout above) and wire `tl-maven-plugin` in the app's `pom.xml`:

```xml
<plugin>
    <groupId>com.top-logic</groupId>
    <artifactId>tl-maven-plugin</artifactId>
    <executions>
        <execution>
            <id>generate-binding</id>
            <phase>generate-sources</phase>
            <goals>
                <goal>generate-java</goal>
            </goals>
            <configuration>
                <modules>module1,module2</modules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

Each class produces three files: `XxxBase.java` (interface with default methods for accessors), `Xxx.java` (user-editable subinterface), `XxxImpl.java` (user-editable impl). The factory class `ModuleFactory.java` is generated alongside. Check the files into source control.

## Build-cycle gotchas

These show up consistently and are easy to misdiagnose:

### 0. Verifying build success

Use `mvn install -DskipTests=true -B 2>&1 | grep -E "^\[ERROR\]|BUILD (SUCCESS|FAILURE)"`. This captures every Maven `[ERROR]` line plus the final banner — small on success, diagnostic on failure (each underlying error is on its own `[ERROR]` line, not buried in a stack trace).

Why not `-q`? TopLogic builds run an in-process app self-test that emits service-lifecycle logs via log4j; those bypass `-q` entirely, so a "quiet" build is in practice not quiet *and* suppresses the `BUILD SUCCESS` banner you'd want to see. Filtering on `[ERROR]` plus the banner avoids both problems.

Never mix `-q` with `tail`/`grep` (the banner is suppressed). Never run the build a second time "to be sure" — if the result is ambiguous, that's a reading problem, not a build problem.

The two-cycle wrapper regeneration in #1 below is an *intentional* doubled build for a specific reason, not a "safety" re-run — that exception is the one and only legitimate reason to invoke `mvn install` twice on the same model change.

### 1. The generator reads the m2-installed JAR, not your source XML

`generate-java` resolves the project's own artifact via Maven and reads `*.model.xml` from inside the installed JAR. So after **renaming an attribute that produces a Java-side conflict** (see #2), one `mvn install` cannot succeed: the generator regenerates wrappers from stale m2 XML, compile fails, install never happens, m2 stays stale.

**Workaround for renames**: temporarily comment out the `tl-maven-plugin` execution, delete the offending generated files under `src/main/java/.../model/`, run `mvn clean install` (puts new XML in m2 without wrapper regeneration), re-enable the plugin, run `mvn clean install` again.

For *adding* new attributes (no conflict), two normal `mvn install` cycles suffice: the first installs the new XML, the second regenerates wrappers from it.

### 2. Names clashing with `AttributedWrapper`

The generated impl extends `AttributedWrapper`, which has `getClasses()` returning `Collection<TLClass>`. An attribute named `classes` produces `getClasses()` returning `List<? extends YourType>` — return-type incompatible, compile fails. Same for any clash with `AttributedWrapper`'s public accessors.

Safe attribute names avoid: `classes`, anything matching `AttributedWrapper`'s method names. When in doubt, try and let compile catch it.

### 3. Java keywords

Attribute named `class` produces `getClass()` which collides with `Object.getClass()`. Pick a different name. Other Java keywords (`default`, `interface`, …) — same issue.

### 4. Naive English-plural quirk

The generator produces `add<Singular>` and `remove<Singular>` methods by stripping a trailing `s` from the attribute name. So `prefixes` produces `addPrefixe` / `removePrefixe`. Either pick a name where the stripped form is grammatical (`children` → `addChild`) or live with the typo. The `setXxx` / `getXxxModifiable` / `getXxx` methods use the original name unchanged.

### 5. Generator does not auto-trigger

The `generate-java` goal has no default phase. Bind it explicitly (the example above uses `generate-sources`).

## Locating example models

The TopLogic engine source contains hundreds of `*.model.xml` files exercising every construct. Clone it shallowly into the project's `tmp/` directory at the **tag matching the app's TopLogic version** (read from the `tl-parent-app` artifact version in the app's `pom.xml`):

```bash
# Resolve the version from the app's pom (parent <version>) — e.g. 7.10.2:
TL_VERSION=$(mvn -q help:evaluate -Dexpression=project.parent.version -DforceStdout)

# Shallow clone the matching tag into ./tmp/ (gitignored in most TL apps):
git clone --depth 1 --branch "$TL_VERSION" \
    https://github.com/top-logic/tl-engine.git tmp/tl-engine
```

Once cloned, browse examples:

```bash
find tmp/tl-engine -name "*.model.xml" -not -path "*/target/*"
```

Particularly informative files:
- `com.top_logic.demo/.../DemoTypes.model.xml` — broad construct coverage
- `com.top_logic.demo/.../test.dynamictable.model.xml` — derived attributes via `storage-algorithm`
- `com.top_logic.demo/.../test.containmentContext.model.xml` — composite + derived combo
- `com.top_logic.demo/.../test.fallback.model.xml` — `id-column` usage
- `com.top_logic.demo/.../test.polymorphism.model.xml` — inheritance + `form-definition` annotations

## When to invoke this skill

Trigger when:
- The user authors or edits a `*.model.xml` under `WEB-INF/model/`
- Adding `package-binding` or configuring wrapper generation
- Declaring derived attributes, id-columns, or class/attribute annotations
- Diagnosing a wrapper regeneration / compile-cycle problem
- The user asks "how do I declare X in TopLogic" where X is a type, attribute, or reference

Do **not** invoke for `*.layout.xml` (use the `tl-layout` skill) or pure TL Script questions (use `tl-script`).
