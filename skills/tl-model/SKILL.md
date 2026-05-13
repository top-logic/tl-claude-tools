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

`<property>` carries primitives (literals stored on the row). `<reference>` carries links to other model instances.

### Primitive type literals

`tl.core:String`, `Integer`, `Long`, `Boolean`, `Double`, `Float`, `Date`, `DateTime`, `Time`, `Duration`, `Binary`, `Icon`, `Name`, `Password`, `Text`, `Tristate`, `Revision`.

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

### 0. Verify build success by exit code, not by grepping output

`mvn install -q` suppresses the `BUILD SUCCESS`/`BUILD FAILURE` banner. If you also pipe through `tail`/`grep`, you see side-effect logs (TopLogic service-lifecycle output, etc.) that look like a run but do not confirm success — a trap that easily leads to a second unnecessary build.

Pick **one**:
- **Quiet:** `mvn install -DskipTests=true -q -B`, then trust the Bash exit code (0 = ok). No `tail`/`grep`.
- **Verbose:** `mvn install -DskipTests=true -B 2>&1 | tail -5`. The banner is in the last lines.

Never mix `-q` with `tail`/`grep`. Never run the build a second time "to be sure" — if the exit code is ambiguous, that's a reading problem, not a build problem.

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
