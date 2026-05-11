---
name: tl-layout
description: Reference for TopLogic layout XML — declarative UI configuration in `*.layout.xml` (tables, trees, tabs, tiles, forms, master-detail). Covers the template-call pattern, channel binding for cross-component selections, common templates and their inner-component names, and the most frequent pitfalls. Invoke when authoring or modifying `*.layout.xml` files under `WEB-INF/layouts/`.
allowed-tools: Bash, Read, Glob, Grep
---

# TopLogic Layout Reference

Application UI is assembled from `*.layout.xml` files under `src/main/webapp/WEB-INF/layouts/`. Each file is a *template-call* that instantiates one of the standard templates with parameters. Layouts compose by referencing each other.

## Template-call shape

```xml
<?xml version="1.0" encoding="utf-8" ?>

<config:template-call
    xmlns:config="http://www.top-logic.com/ns/config/6.0"
    template="com.top_logic/<template>.template.xml"
>
    <arguments
        attr1="value"
        attr2="value"
    >
        <name key="i18n.key.here">
            <en>English label</en>
            <de>Deutsche Bezeichnung</de>
        </name>
        <child-element>...</child-element>
    </arguments>
</config:template-call>
```

The templates live in the engine under `com.top_logic/.../layouts/com.top_logic/*.template.xml` — that's the authoritative source of accepted arguments per template.

## Catalog of common templates

| Template | Purpose | Inner selector name | Key arguments |
|---|---|---|---|
| `tabbar` | Tab bar with multiple tabs | `Tabbar` | `<components>` (list of `tab` references) |
| `tab` | One tab inside a tabbar | (wrapper only) | `tabLabel`, `tabIcon`, `<components>` |
| `contentLayout` | Layout containing one component | (wrapper) | `<components>` |
| `layout` | Horizontal/vertical split | `InnerLayout` | `orientation="HORIZONTAL"\|"VERTICAL"`, `resizable`, `<components>` |
| `table` | Flat table | `Table` | `type`, `modelBuilder`, `defaultColumns`, `model` |
| `treetable` | Tree-table (hierarchical rows) | `TreeTable` | `modelBuilder` (TreeModelBuilder), `rootVisible`, `expandRoot` |
| `tree` | Tree (single column) | `Tree` | `modelBuilder` (TreeModelBuilder) |
| `form` | Form view of a single object (from `com.top_logic.element/form.template.xml`) | `Form` | `type`, `model`, `defaultFor`, `forms` |
| `tileTable` | Context tile wrapping a table — selecting a row drills into a detail | `Table` | `type`, `modelBuilder`, `<component>` (the detail) |
| `tileTreetable` | Same as `tileTable` but the master is a tree-table | `TreeTable` | `modelBuilder` (TreeModelBuilder), `<component>` |
| `tileGroup` | Group of tiles (a single tile-context level) | (wrapper) | `<components>` (list of `tile` references) |
| `tile` | One tile inside a `tileGroup` | (wrapper) | `tileLabel`, `preview`, `<components>` |
| `tiles` | Top-level tile container | (wrapper) | `<component>` |
| `tabbar` (nested) | Tab bar inside tile context | as above | — |
| `placeholder` | "Click to configure" placeholder for in-app editor | — | — |

The **inner selector name** matters for channel binding: when you write `selection(some.layout#TreeTable)`, the `TreeTable` is the name the template assigns to its inner table component, not the file name.

## Component model & channel binding

A component has a `model` channel (its current object) and a `selection` channel (its currently-selected sub-object). Inner components don't auto-inherit the parent's model — wire them explicitly.

### Reading another component's selection

```xml
<arguments model="selection(diamond/settings/ontologiesContextTable.layout.xml#Table)" ...>
```

This binds the current component's model to the selection of the table named `Table` inside `ontologiesContextTable.layout.xml`. Use the inner-selector name from the table above (not always `Table` — `TreeTable` for treetable templates, etc.).

### Combining two channels

When a component needs *two* sources (e.g., master-detail with an outer class context and an inner property selection):

```xml
<model class="com.top_logic.layout.channel.linking.impl.CombineLinking">
    <channel name="selection">
        <target name="diamond/settings/ontologies/classesTable.layout.xml#TreeTable"/>
    </channel>
    <channel name="selection">
        <target name="diamond/settings/ontologies/classes/classPropertiesUsedTable.layout.xml#Table"/>
    </channel>
</model>
```

Then `$model` in the model-builder's expression is a list: `$model[0]` is the first source's selection, `$model[1]` is the second's.

## Model builders

The `modelBuilder` is the function that, given the component's model, produces the rows / nodes to show. The two by-expression builders cover most cases:

### `ListModelByExpression` — for flat tables

```xml
<modelBuilder class="com.top_logic.model.search.providers.ListModelByExpression"
    elements="model -> $model.get(`my.module:Container#items`).filter(i -> $i.instanceOf(`my.module:Item`))"
    supportsElement="element -> $element.instanceOf(`my.module:Item`)"
    supportsModel="model -> $model.instanceOf(`my.module:Container`)"
/>
```

- `elements` — the list of rows (TL Script lambda on the component's model)
- `supportsElement` — predicate over a row, used for goto and DnD
- `supportsModel` — predicate filtering which component models this builder accepts

### `TreeModelByExpression` — for trees / tree-tables

```xml
<modelBuilder class="com.top_logic.model.search.providers.TreeModelByExpression"
    rootNode="model -> $model"
    children="node -> $node.instanceOf(`my.module:Group`) ? $node.get(`my.module:Group#items`) : $node.get(`my.module:Item#subItems`)"
    parents="node -> $node.get(`my.module:Item#parent`)"
    nodePredicate="node -> $node.instanceOf(`my.module:Item`) or $node.instanceOf(`my.module:Group`)"
    leafPredicate="node -> $node.instanceOf(`my.module:Item`) and $node.get(`my.module:Item#subItems`).size() == 0"
    modelPredicate="model -> $model.instanceOf(`my.module:Group`)"
/>
```

- `rootNode` — function from the component model to the root. Default: `model -> $model`.
- `children` — function from a node to its children. Dispatch on `instanceOf` if the tree mixes types.
- `parents` — optional, supports breadcrumbs.
- `leafPredicate` — early termination; otherwise children are queried.
- `modelPredicate` — accept-filter for the component model.

Pair with `rootVisible="false" expandRoot="true"` on the `treetable` / `tileTreetable` arguments to hide the synthetic root and start the tree at its children.

## Selectable context tiles (master-detail)

A `tileTable` / `tileTreetable` is *selectable*: clicking a row drills into the `<component>` you nest inside `<arguments>`. The inner component receives the selected row as its model automatically (via the `ContextTileComponent` wiring), but its *children* do not — wire them explicitly with `model="selection(<outer-file>#<selector-name>)"`.

```xml
<config:template-call template="com.top_logic/tileTable.template.xml">
    <arguments type="my.module:Item" ...>
        <modelBuilder .../>
        <component class="com.top_logic.mig.html.layout.LayoutReference$LayoutReferenceComponent"
            resource="my/path/itemDetail.layout.xml"
        >
            <content-layouting class="com.top_logic.layout.structure.ContentLayouting">
                <contextMenuFactory class="com.top_logic.layout.basic.contextmenu.component.factory.PlainComponentContextMenuFactory">
                    <titleProvider class="com.top_logic.layout.provider.label.NoLabelProvider"/>
                    <customCommands class="com.top_logic.layout.basic.contextmenu.config.NoContextMenuCommands"/>
                </contextMenuFactory>
            </content-layouting>
        </component>
    </arguments>
</config:template-call>
```

Inside a tile-context (where `tileGroup` and `tile` are valid), build tile groups and individual tiles:

```xml
<config:template-call template="com.top_logic/tileGroup.template.xml">
    <arguments>
        <name>...</name>
        <components>
            <layout-reference resource=".../detailTile1.layout.xml"/>
            <layout-reference resource=".../detailTile2.layout.xml"/>
        </components>
    </arguments>
</config:template-call>
```

Each individual tile:

```xml
<config:template-call template="com.top_logic/tile.template.xml">
    <arguments>
        <tileLabel>...</tileLabel>
        <preview class="com.top_logic.mig.html.layout.tiles.component.StaticPreview"
            icon="css:fas fa-cube"
        />
        <components>
            <layout-reference resource=".../tileContent.layout.xml"/>
        </components>
    </arguments>
</config:template-call>
```

`tile` and `tileGroup` are annotated `usable-in-context="...IsInTileContext"` — they only render inside a tile component (i.e., inside a `tileTable`/`tileTreetable`/`tiles` chain). A `tabbar` inside a tile context will *not* render. The reverse — a `tile` outside a tile context — also fails silently.

## TL Script in layouts

Layout attribute values and certain child elements accept TL Script expressions. The same syntax rules apply (see the `tl-script` skill).

**XML attribute escaping**: in an attribute *value*, `&&` is an unescaped entity reference and breaks parsing. Use the keyword `and` / `or` (TL Script alternatives), or wrap the expression in `<![CDATA[...]]>` inside a child element (e.g., `<elements><![CDATA[...]]></elements>` in `ListModelByExpression`).

## Common pitfalls

### 1. Wrong inner-selector name

The single most frequent break: writing `selection(...#Table)` when the source is a treetable. Tree templates use `TreeTable`, not `Table`. Check the template source (`com.top_logic/.../*.template.xml`) for the `<selector name="...">` or `<tableView name="...">` line.

### 2. `horizontal="true"` does not work on the `layout` template

The configurable argument is `orientation="HORIZONTAL"` (or `VERTICAL`). `horizontal` is a derived property on the underlying component and rejects configuration with `"Derived property 'horizontal' cannot be configured"`.

### 3. Missing parent context for tile / tileGroup / tab

`tile` and `tileGroup` require a tile context; `tab` requires a tabbar. Putting them at the wrong level produces silently empty render output rather than an error. When a tile / tab doesn't appear, check the parent chain.

### 4. Inner component models stay empty without explicit channels

Inside a `tileTable`/`tileTreetable`'s `<component>`, the `ContextTileComponent` propagates the master's selection to its content's `model` channel. But that content's *children* (e.g., a table nested two levels deeper) get their model only if you wire it: `model="selection(<outer-layout>#<selector>)"`. Without this, model-builder predicates return false and content is empty.

### 5. `referers` and the part literal

`$entity.referers(\`my.module:OtherType#attr\`)` returns objects whose `attr` reference points to `$entity`. The part literal is resolved at parse time — if you typo the attribute name, you get an error at component load (often as a TLScript parse exception in the log).

### 6. CDATA in attribute values

`<![CDATA[...]]>` only works *inside an element body*, not inside an attribute. For long expressions, prefer the child-element form:

```xml
<modelBuilder class="...">
    <elements><![CDATA[
        model -> { ... long expression ... }
    ]]></elements>
    <supportsElement>element -> $element.instanceOf(`...`)</supportsElement>
</modelBuilder>
```

## Locating example layouts

```bash
# In the TL engine checkout (if available):
find tmp/tl-engine-7 -name "*.layout.xml" -not -path "*/target/*"

# Particularly informative directories:
#   com.top_logic.demo/.../technical/components/        — most templates, many channel patterns
#   com.top_logic.demo/.../technical/components/tiles/  — tile / tileTable nesting
#   com.top_logic.demo/.../technical/components/combiningChannel/ — CombineLinking examples
#   com.top_logic.demo/.../technical/components/tabellenUndBaeume/ — table / tree templates
```

## When to invoke this skill

Trigger when:
- The user authors or edits a `*.layout.xml` under `WEB-INF/layouts/`
- Composing tables / trees / tabs / tiles / forms in TopLogic
- Wiring channels between components (selection, model)
- Master-detail / two-source layouts (CombineLinking)
- Diagnosing "tile doesn't render", "table is empty", "horizontal property cannot be configured" and similar

Do **not** invoke for pure TL Script expression questions (use `tl-script`) or for `*.model.xml` work (use `tl-model`).
