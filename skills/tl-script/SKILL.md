---
name: tl-script
description: Reference for TL Script — TopLogic's expression/scripting language. Covers surface syntax, semantics, and a search strategy for locating registered script functions. Invoke when reading, writing, or debugging TL Script expressions (in layout XML, model configuration, search expressions, templated text).
allowed-tools: Bash, Read, Glob, Grep
---

# TL Script Reference

TL Script is TopLogic's embedded expression language. It is used for search expressions, computed model attributes, templated text, and layout/component configuration. The authoritative grammar lives in `com.top_logic.model.search/src/main/java/com/top_logic/model/search/expr/parser/SearchExpressionParser.jj` — consult it when in doubt. Operation implementations live under `com.top_logic.model.search/src/main/java/com/top_logic/model/search/expr/` (and may also be registered from other modules that depend on `com.top_logic.model.search`).

## Syntax cheat sheet

### Literals

| Kind | Syntax |
|---|---|
| null / booleans | `null`, `true`, `false` |
| Integer | `42`, `1_000` (underscore separator allowed) |
| Float | `3.14`, `1e10` |
| String | `'single'` or `"double"` with `\\ \' \" \t \b \n \r \f \uXXXX` escapes |
| Text block | `"""multi-line"""` |
| I18N string | `"Hello"@en`, `"""block"""@de` |
| I18N compound | `#("Hi"@en, "Hallo"@de)` with optional suffix groups `#(name: { "Max"@en })` |
| Resource key | `#'some.key'` |
| List | `[a, b, c]` |
| Dict | `{ key: value, key: value }` (parser picks dict via lookahead `{ expr :`) |
| Tuple | `tuple(name -> expr, optional? -> expr)` |
| Module | `` `my.module` `` |
| Type | `` `my.module:MyType` `` |
| Part (attribute) | `` `my.module:MyType#attribute` `` |
| Singleton | `` `my.module#SINGLETON` `` |

### Variables and blocks

- Variables: `$name` (sigil `$`)
- Assignment (only inside a block): `name = expr` — introduces `$name` for later statements
- Block: `{ stmt; stmt; last }` — statements separated by `;`; block value is the last expression
- `this` is a reserved word (lexer token); it has no production in the grammar, so it cannot appear as a bare expression. Its semantics, if any, would need separate verification.

### Operators (low → high precedence)

| Level | Operators |
|---|---|
| Ternary | `a ? b : c` |
| Logical OR | `or` or `\|\|` |
| Logical AND | `and` or `&&` |
| Comparison | `==` `!=` `>=` `>` `<=` `<` |
| Additive | `+` `-` |
| Multiplicative | `*` `/` `%` |
| Unary | `!` `-` |

### Call / access syntax

- Method call (UFCS — first argument moves before the dot): `$list.size()`, `$list.filter(x -> $x > 0)`
- **Chain (cascade) call**: `$x..method(args)` — result is `$x` itself, not the method's return. Use for side effects.
- **Index**: `$list[i]` — shortcut for `$list.elementAt(i)`
- **Static function call**: `name(args)` — calls a registered TL Script function. Example: `mathRandom()`.
- **Apply a lambda value**: `$f(x, y)` — when left side already yields a function
- **Named arguments**: `fn(name: value, other: value)`
- **Trailing comma** allowed in argument lists

### Lambdas

- `x -> expr` — single parameter. The parameter becomes a variable inside: reference as `$x`.
- Multiple parameters are curried: `x -> y -> $x + $y`
- Typical use: `$list.filter(x -> $x > 0)`, `$list.foreach(x -> log($x))`

### Control flow

- Ternary: `cond ? then : else`
- Switch (with subject):
  ```
  switch ($value) {
    case1Value: result1;
    case2Value: result2;
    default: resultN;
  }
  ```
- Switch (without subject — each case is a boolean test):
  ```
  switch {
    $x > 0: "positive";
    $x < 0: "negative";
    default: "zero";
  }
  ```

### HTML / text embedding

- HTML block: `{{{ <p>Hello {$name}</p> }}}` — `{expr}` embeds a script expression; `\{`, `\}`, `\<`, `\\` escape
- `<script type="text/tlscript">...</script>` — alternative embedding **inside an HTML block** (only valid in the HTML lexer mode). The `type` attribute must use double quotes exactly (`"text/tlscript"`). Body is a block: statements separated by `;`, value is the last expression.
- Plain text with embedded expressions has its own parser entry point (`textWithEmbeddedExpressions`) — used internally by templating

### Comments

- Line: `// ...`
- Block: `/* ... */`

### Reserved words

`true`, `false`, `null`, `this`, `and`, `or`, `tuple`, `switch`, `default`

## Semantics notes

- **UFCS**: `$x.f(a, b)` is equivalent to `f($x, a, b)` when `f` is a static script function. This is why methods like `size`, `filter`, `elementAt` can be written either way. The dot form is idiomatic.
- **Chain vs. access**: `$x.f()` evaluates to the return value of `f`. `$x..f()` evaluates to `$x` (useful when `f` mutates or has a side effect and you want to keep the receiver).
- **Index coercion**: `$list[expr]` accepts any numeric expression; the underlying `elementAt` truncates to `int`. For positive values this is equivalent to a floor.
- **Nullability / empty lists**: Many list operations treat a singleton and a list uniformly (`AbstractListAccess`); `evalOnEmpty` typically returns `null`.
- **I18N keys**: The `@lang` suffix on a string literal produces a `ResKey` with that language; `#(...)` composes multi-language keys.
- **Model literals** (backticks) are resolved at build time against the current `TLModel` (see `SearchBuilder.visit(...)` for `ModuleLiteral` / `TypeLiteral` / `PartLiteral` / `SingletonLiteral`). They evaluate to:
  - Module literal → `TLModule` (via `TLModelUtil.findModule`)
  - Type literal → `TLType` (via `TLModelUtil.findType`)
  - Part literal → `TLTypePart` (via `TLModelUtil.findPart`) — the generic part type, not only `TLStructuredTypePart`
  - Singleton literal → the singleton instance object (runtime-typed `Object`, concrete class depends on the model)

## How to look up a function

Script functions come from two registration mechanisms. Both can live in **any module** that depends on `com.top_logic.model.search`, not only in `com.top_logic.model.search` itself.

### Mechanism 1 — static methods on a `TLScriptFunctions` subclass

- The class has a `@ScriptPrefix("<prefix>")` annotation.
- Each public static method becomes a script function named **`<prefix><MethodName>`** — the prefix is prepended directly, the method name's first letter is capitalized, **no separator**. Example: `@ScriptPrefix("math")` + `random()` → `mathRandom()`.
- Parameters may have `@Mandatory`; `@Label` overrides the generated UI label; the JavaDoc provides the description.
- Side-effect-freeness may be declared via `@SideEffectFree`.

**Find all such classes:**

```bash
grep -rln "extends TLScriptFunctions" --include="*.java" .
```

**Given a script function name** (e.g. `mathRandom`), peel off the prefix and grep for the bare method:

```bash
# e.g. for "mathRandom": try splitting after known prefixes
grep -rn "static.*\brandom\s*(" --include="*.java" com.top_logic.model.search/
```

Then confirm the `@ScriptPrefix` on the containing class.

### Mechanism 2 — `MethodBuilder` implementations

- A class implements `MethodBuilder` (often via `AbstractSimpleMethodBuilder`, `SingleArgMethodBuilder`, `TwoArgsMethodBuilder`, `ThreeArgsMethodBuilder`, etc.).
- The script function name is set via `super("<name>", ...)` in the `SearchExpression` subclass the builder produces (see e.g. `ElementAt` → `"elementAt"`).
- Builders must be registered in module configuration (typed-config XML), usually under a `MethodResolver` / `TLScriptMethodResolver` entry.

**Find builders:**

```bash
grep -rln "implements MethodBuilder\|extends AbstractSimpleMethodBuilder\|extends SingleArgMethodBuilder\|extends TwoArgsMethodBuilder\|extends ThreeArgsMethodBuilder" --include="*.java" .
```

**Given a function name** (e.g. `elementAt`), search for the `super("name",` call:

```bash
grep -rn "super(\"elementAt\"" --include="*.java" .
```

**Find the module configuration that registers it** (if needed):

```bash
grep -rn "ElementAtBuilder\|elementAt" --include="*.xml" com.top_logic.model.search/src/main/webapp/WEB-INF/
```

### Mechanism 3 — the big index (bulk exploration)

To enumerate **all** script functions in the worktree at once:

```bash
# Static-method functions (with their prefix)
grep -rln "extends TLScriptFunctions" --include="*.java" . | while read f; do
  awk '/@ScriptPrefix/,/class /' "$f"
  grep -n "public static " "$f"
  echo "---"
done

# MethodBuilder-registered functions
grep -rEn 'super\("[a-zA-Z_][a-zA-Z_0-9]*"\s*,' --include="*.java" \
  $(grep -rl "extends AbstractSimpleMethodBuilder\|implements MethodBuilder" --include="*.java" . | xargs -I{} dirname {} | sort -u)
```

### Documentation sources (in order of usefulness)

1. **Online help HTML pages** — `MethodBuilder`-registered functions each ship with a dedicated HTML help page, served by the app as context-sensitive help in the TL-Script editor. Location pattern:

   ```
   <module>/src/main/webapp/doc/{en,de}/DeveloperGuide/TLScript/<Category>/<functionName>/
     ├── page.properties   # title, uuid
     └── index.html        # the actual documentation
   ```

   The bulk lives under `com.top_logic.model.search/src/main/webapp/doc/` (hundreds of pages across categories: `ListAndSet/`, `Strings/`, `ArithmeticOperations/`, `SystemFunctions/`, etc.). Find a function's help page by directory name:

   ```bash
   find . -type d -name "elementAt" -path "*/doc/en/*"
   # then read .../elementAt/index.html
   ```

   To get a readable summary without the HTML markup, strip tags:
   ```bash
   cat .../index.html | sed 's/<[^>]*>//g' | sed '/^\s*$/d'
   ```

   Indexing mechanism: `HelpPageIndex` (`com.top_logic.model.search/.../ui/help/HelpPageIndex.java`) scans `doc/<locale>/` under every `FileManager` path at startup; any directory with both `page.properties` and `index.html` is a page.

2. **`@Label("...")`** at class or method level — UI label (used in dropdowns / autocomplete).

3. **JavaDoc on the method / builder class / produced `SearchExpression` subclass** — long description. Starts with `@en` for the English default text (see `messages_en.properties` auto-generation). For `MethodBuilder`-style functions, the JavaDoc often just points at the help page; the HTML page is authoritative.

When answering a user's "what does function X do" — always show:
1. The function's signature (parameters, types, `@Mandatory`)
2. The JavaDoc summary
3. `file_path:line_number` of the definition, so the user can jump to it

## When to invoke this skill

Trigger when:
- The user asks about TL Script syntax, operators, literals, or control flow
- The user writes or edits a TL Script expression (search expressions, templated text, computed attributes, layout configuration with scripted attributes)
- The user asks "is there a TL Script function that…" or "how do I X in TL Script"
- A file contains a `text/tlscript` script block or expressions in `{...}` / `{{{...}}}` templating

Do **not** invoke for unrelated scripting languages (GWT/JavaScript, Groovy, shell). TL Script is specific to TopLogic's expression engine.
