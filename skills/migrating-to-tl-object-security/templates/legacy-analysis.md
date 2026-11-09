# Legacy security of <APP> (analysis for the object security migration)

Written <DATE> from the configuration and code (TL <VERSION>). Engine mechanisms are explained in the
skill (`legacy-concept.md`); this file only records what <APP> configures. Keep it factual: every
statement with its file and line.

## 1. Roles

| Role | Internal / external (ask!) | Who gets it (assignment) | Where it lands (objects) | Notes |
|---|---|---|---|---|
| `SecurityStructure.<ROLE>` | internal | `<Type>#<attr>` → users / group `<G>` | `<Type>`, inherited to … | |

Accounts with `admin=true` bypass every check and can't be used for testing: <list>.

## 2. Role rules and paths

Per role, the full path from the assignment to every object type it reaches (assignment rule, then
each inheritance rule, up and down, including inheritance to the security root):

```
<ROLE>:  <Type A> (assignment via <attr>)
           → <Type B> (inheritance, path <attr>, inverse)
           → SecurityStructure#ROOT (inheritance)          ← roles on the root matter for object security!
```

Security parents (role parents): `<Type>` → `<Parent>` via `<attr>`.

Roles that reach the **security root** (all of them count wherever the root becomes a role parent):
<list, mark write roles such as tenant admins>.

## 3. Groups and global roles

| Group | Role on root (`singleton-rule`) | Members (how maintained) |
|---|---|---|

## 4. Role profiles (views per role)

| Role | Views (CompoundSecurityLayout) | Command groups |
|---|---|---|

Which views of a role are reachable from which menu; which views are shared by several roles.

## 5. Security objects, masters and filters in the layouts

| View / component | `securityObject` | Effective object checked | Notes |
|---|---|---|---|
| `<layout>#<component>` | `model` | the command target (selected row!) | see `legacy-concept.md` L4 |

- `isSecurityMaster` usages: …
- `filterPermission` usages (row filters in model builders): … (they encode requirements the object
  layer must reproduce)
- Project helper functions used as security objects or filters: …

## 6. Worked example: one role in two views

Pick the central role and describe one view with per-object checks and one with root check plus
filtering. This makes the two restriction styles visible.

## 7. Findings / review items

Inconsistencies found in the legacy setup (redundant rules, roles that reach the root by accident,
views without security object). They become decisions or follow-up topics.
