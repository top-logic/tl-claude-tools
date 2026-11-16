# The legacy TopLogic security concept (view and command security)

Read this before analysing an application. It explains the mechanism that object security builds
on: the legacy **roles on objects** stay the role source of the new layer, so you must understand
where every role lands. Verified against TL 8.0.0-alpha11 by code analysis; re-check class names
after an upgrade (tl-mcp tools: `show_source`, `callers_of`, `references_to`, `query_types`).

## L0. The one-sentence version

A command of command group **G** in view **V** is allowed for user **U** if U holds a role **R** on
V's **security object** **O** (on O itself or on one of O's security parents), and the **role
profile** of R grants G for V.

```
 roles: WHO has WHICH role on WHICH object   profiles: WHAT a role may do in WHICH view
 (AccessManager role rules)                  (security.xml / in-app security editor)
                 \                                 /
                  \---- security object O --------/
                       (chosen by the component / button)
```

## L1. Roles on objects: role rules

Configured on the `AccessManager` service in `<role-rules>` (app file usually
`autoconf/com.top_logic.tool.boundsec.manager.AccessManager.config.xml`). Roles belong to a security
structure (`SecurityStructure.X`). A role is always held **on an object**; a "global" role is a role
on the security root (singleton `SecurityStructure#ROOT`).

All rule kinds are declared on the type that **receives** the role (`meta-element`). They navigate
from an instance of that type along a path: `<step attribute=… inverse=…/>`,
`<script-step><expr>…</expr></script-step>` or `<singleton>`.

- **Assignment, `<rule>`:** the path ends at **users or groups** (via the `group-mapper`, e.g. a
  person contact → the account's representative group). Those users get `role` on the object. This
  is the object–user assignment ("the planners of this document").
- **Inheritance, `<inheritance-rule>`:** the path ends at **other objects**. Whoever holds
  `source-role` there gets `role` on the target. Empty `source-role` = same role. Rules chain
  transitively, up (part → container → root) or down (root → business objects).
- **Static group rule, `<singleton-rule role=… target="SecurityStructure#ROOT"><group-with-name name=…/>`:**
  members of a group get the role on the root ("global" permission).
- Common attributes: `role="A,B"`, `inherit="true"` (= also for **subtypes** of `meta-element`, not
  role inheritance), `source-meta-element`, `resource-key`.
- **Materialization:** results are stored (one row per object × group × role) and updated
  incrementally on model changes. Inheriting a role down to many objects produces many rows.
- **Stored attributes only:** a step over a derived (computed) attribute is rejected at startup.

## L2. Security parents (role parents): a role carrier instead of the object

`<security-parents><rule id=… meta-element=…><step …/></rule></security-parents>` (same file).
A type declares another object as its parent; role checks on an instance also count the roles held
on the parent (transitively, breadth-first, cycles skipped). Nothing is materialized, it is a
lookup-time widening. Typical use: all milestones of a project are checked on the project.

Engine traps:
- Types extending `tl.element:StructuredElement*` (e.g. `tl.doc:Page`) **ignore** configured
  security parents (`StructuredElementWrapper#getSecurityParents()` returns an empty list).
- "Who holds `source-role` on the source object" for an inheritance rule ignores security parents;
  the object check (`hasRole`) includes them.

## L3. Command groups and role profiles

- Every view declares command groups: `Read` (may the view be shown) plus one group per kind of
  command (`Write`, `Create`, `Delete`, `Export`, custom groups). A button's `group="…"` assigns it.
- **Role profile:** one per role, persisted in `WEB-INF/conf/security.xml`:
  ```xml
  <profile name="SecurityStructure.R">
    <view name="app/some/tabLayout.layout.xml#TabLayout">
      <commandGroup name="Read"/>
      <commandGroup name="Write"/>
    </view>
  </profile>
  ```
- The `view name` is the enclosing **CompoundSecurityLayout** (the security boundary, typically a
  tab or tile). Inner components inherit its profile data.

## L4. Security object: whose roles are checked

Every bound component (and a layout) has `securityObject`:

| Config | Meaning |
|---|---|
| *(nothing)* | **default `securityRoot`** |
| `securityRoot` | the security root |
| `model` | path element `Model` at index 0: **the model passed to the check**, i.e. the **target of the command** (a button targeting the selection checks the selected row!), and the component model only when the component itself is checked. The in-app label "Aktuelles Modell / current model" misleads. |
| `model(self())`, `model(<component>)`, `selection(<component>)` | channel expression: the model or selection of this or another component (use `model(self())` for "the component's own model") |
| `ref:<alias>` | named provider (tl-core `top-logic.config.xml`): model, securityRoot, null, master…, dialogParent…, selection, … |
| `path:…` | path-based provider (`component`, `master`, `parent`, `opener`, `window`, `selection`, `model`, `currentobject`) |
| `<securityObject class="…SecurityObjectByExpression" function="…"/>` | TL Script computes the object (unsecured) |
| `<securityObject class="…NullSecurityObjectProvider"/>` | no security object |

- A **button can override** the component's security object.
- **A null security object means no check, the command is allowed** (`BoundChecker#allow`). This
  also happens silently with `model` when the target is not a `BoundObject`: nothing selected, a
  multi-selection (a set), a transient row.
- Classic consequence: a grid with `securityObject="model"` and single selection checks the
  **selected row** on edit/delete. Rows without legacy roles (composition parts without own rules
  or security parents) are denied for non-admins; the same grid with `multiSelection="true"` is not
  checked at all.

## L5. Security master: who decides whether a layout is shown

`isSecurityMaster="true"` on a component makes it decide whether the **whole layout** (tab, tile) is
shown: the layout delegates its security object and hide reason to the master. A layout with exactly
one bound child uses that child implicitly. Without a master the layout checks its own security
object (default root) for `Read`.

## L6. The final check (call chain)

`AbstractCommandHandler#checkSecurity` → `BoundChecker#allowCommandOnSecurityObject` →
`BoundChecker#allow(user, O, G)`:
1. `O == null` → **allow**;
2. bypass: no user → deny; system group or admin (`ThreadContext.isAdmin()`, `Person.admin=true`)
   → allow; restricted user → deny certain groups;
3. `getRolesForCommandGroup(G)`: the roles whose profile grants G for this CompoundSecurityLayout (in
   a dialog the opener's checker); none → **deny**;
4. `AccessManager#hasRole(user, O, roles)`: stored roles on **O or its security parents**.

The grid row check (`GridComponent#allowEdit`) uses the "default checker" registered for the row
type (`defaultFor`) and checks Write on the row itself; transient rows are always editable.

## L7. Row-level filtering: `filterPermission`

`$objects.filterPermission(roles, account?)` keeps the objects on which the user holds one of the
roles (same lookup as L6, security parents included). It returns **everything for admins**. It is
the building block of the *second* restriction style: the model builder offers only permitted
objects and the view's commands are checked on a global object such as the root. Find these
filters (`grep filterPermission`), they encode requirements the object layer must reproduce.

## L8. Groups: global permissions

The security storage is **group-based**: every person has a representative group; a check queries
all group ids of the user. A `<singleton-rule>` gives a group a role on the root. Typical patterns:

| Pattern | Group role on root | Inherited down to | Profile grants |
|---|---|---|---|
| Superuser | `superuser` | every type used as a security object | everything |
| Area admin | `areaAdmin` | only the area's objects | the area's views |
| System admin | `sysadmin` | nothing (admin views check the root) | admin views only |

Rule of thumb: a group role must reach every object that serves as a security object in the views
its profile grants. The opposite direction exists too: business roles inherited **up** to the root
so that root-checked views work (common in older apps, and important for object security: every
role that reaches the root counts wherever the root is a role parent).

## L9. The key limitation (why object security exists)

Everything above decides **which views and commands** a user can see and run, checked against the
view's security object when a view or command is displayed or executed. There is **no check when an
object is accessed** as such. Inside a permitted view every reachable object is implicitly
permitted; master data without any restriction can be picked anywhere.

This breaks without a layout layer: a REST API or an AI agent in the user's context can call
`all(T)` and get every instance, and writes run unrestricted. Model-based object security
(`engine-reference.md` §2) closes this gap, using the same roles.

## L10. What to collect from an application (input for the migration)

| What | Where |
|---|---|
| Role rules, inheritance rules, singleton/group rules, security parents | AccessManager config (app autoconf; also framework `*-web-fragment.war` contributions, which an app file with `config:override="true"` discards) |
| Role profiles per view | `WEB-INF/conf/security.xml` |
| Security objects and masters of the views | layouts: `grep securityObject`, `isSecurityMaster="true"` |
| Row filters | layouts and functions: `grep filterPermission` |
| Project helper functions used as security objects | `SecurityObjectByExpression`, TL Script functions (e.g. "`xxxSecurityParent()`") |
| Groups and their members | admin UI / data import (`tl.accounts:Group`) |
| Which roles are internal / external, which accounts are admins | ask the developer |
