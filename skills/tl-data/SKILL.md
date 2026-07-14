---
name: tl-data
description: Reference for delivering and importing TopLogic instance data (object instances, not the schema) — the `WEB-INF/data/*.objects.xml` format, the `InitialDataSetupService` load-once seeding mechanism, and the instance export/import round-trip. Invoke when shipping seed/master data with an app, seeding instances of a model type at startup, authoring or reading an `*.objects.xml` file, or exporting/importing model instances.
allowed-tools: Bash, Read, Glob, Grep
---

# TopLogic Instance Data Reference

This skill is about **instance data** — concrete objects of your model types — as
opposed to the model *schema* (types/attributes), which is the `tl-model` skill.
Use it to ship seed/master data with an application (workflow statuses, root
folders, catalogs, demo data) and to move instances between systems.

## Seeding at startup: `WEB-INF/data/*.objects.xml`

`InitialDataSetupService` (active by default) imports every file matching
`WEB-INF/data/*.objects.xml` at application startup. Key semantics:

- **Load-once, tracked in the database.** After importing a file it records a
  global `DBProperties` flag `initial-data.<filename>`. On every startup it
  imports only the files without that flag. So a **newly added** data file is
  picked up on the next startup of an *existing* database — no DB reset needed —
  while already-loaded files are never re-imported.
- **Editing a file after it loaded does nothing.** The flag is keyed by file
  name, not content. To force a re-import, rename the file (or clear the
  `initial-data.<name>` property). Treat a shipped data file as write-once.
- Files load in **sorted file-name order** (prefix with `X01_`, `Y02_`, … when
  one file references objects created by another).
- Files are produced by hand, by `XMLInstanceExporter`, or by the **instance
  editor's export command** in the running app — create the objects in-app, then
  export and drop the file into `WEB-INF/data/`.

## `objects.xml` format

```xml
<?xml version="1.0" encoding="utf-8" ?>
<objects>
    <!-- id is a file-local handle for cross-references (see <ref>). -->
    <object id="s-new" type="my.module:TicketStatus">
        <!-- Primitive value: value="..." (parsed via the attribute's storage mapping). -->
        <attribute name="name" value="New"/>
        <attribute name="initial" value="true"/>

        <!-- Enum classifier / any model part: <model-ref name="module:Type#part"/>, NOT value=. -->
        <attribute name="category">
            <model-ref name="my.module:StatusCategory#Open"/>
        </attribute>
    </object>

    <object id="p1" type="my.module:Project">
        <attribute name="name" value="P1"/>

        <!-- Composite reference: nest the owned objects inline. -->
        <attribute name="tickets">
            <object id="t1" type="my.module:Ticket">
                <attribute name="summary" value="First ticket"/>
                <!-- Non-composite reference to another object in THIS file: <ref id="..."/>. -->
                <attribute name="status"><ref id="s-new"/></attribute>
            </object>
        </attribute>
    </object>
</objects>
```

Value forms inside `<attribute>`:

| Target | Syntax |
|--------|--------|
| primitive (`String`, `Boolean`, `Date`, `Binary`, …) | `value="..."` (single) or nested `<value>` entries for a collection |
| model part (enum classifier, type, attribute) | `<model-ref name="module:Type#part"/>` |
| another object defined in the same file | `<ref id="localId"/>` |
| an object already present in the target system | `<global-ref kind="module:Type" id="..."/>` (resolved by a registered resolver) |

An `<object>` may also carry a `global-id` — if set, the importer resolves an
existing object with that id instead of creating a duplicate, making re-import
idempotent for that object.

## Producing and consuming instance data elsewhere

- **In-app:** the instance editor's export command writes an `objects.xml`; a
  matching import command reads one back.
- **Programmatically:** `com.top_logic.model.instance.exporter.XMLInstanceExporter`
  and `...importer.XMLInstanceImporter`.
- **In TL-Script:** the `instanceExport` / `instanceImport` operations.

## Lessons / pitfalls

- **Reference seeded data by a stable flag, not by name.** When a model default
  (or any expression) must point at a specific seeded instance, add a boolean
  marker attribute (e.g. `initial` on a status) and select on it —
  `all(\`m:Status\`).filter(s -> $s.get(\`m:Status#initial\`)).firstElement()` —
  rather than matching a display name like `== "New"`. Matching by name breaks
  silently the moment someone renames the instance.
- **Enum/model-part values are `<model-ref>`, never `value=`.** A `value="Open"`
  on a classifier-typed attribute will not resolve.
- **Changing a type's kind orphans its instances.** Turning an `enum` into a
  `class` (or vice versa) with the same name invalidates the stored values of
  existing instances; a mandatory attribute of that type then fails the
  startup model check and the app will not boot. Seed the new instances, and be
  prepared to migrate or discard pre-existing data (see the `tl-model` skill for
  the model-evolution behavior).
- **Seeding needs the referenced containers to exist first.** If a data file
  creates objects that reference project-scoped or other pre-existing objects,
  ensure ordering (file-name prefix) or use `<global-ref>`.

## When to invoke this skill

Trigger when:
- Shipping initial/master/demo data with an app, or seeding instances of a model
  type at startup.
- Authoring, reading, or debugging a `WEB-INF/data/*.objects.xml` file.
- A seeded instance did not appear, or a data file failed to (re-)import.
- Exporting or importing model instances between systems.

Do **not** invoke for defining types/attributes (use `tl-model`) or for layout
(`tl-layout`).
