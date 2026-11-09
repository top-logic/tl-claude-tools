# Engine reference: legacy and object-based security (TopLogic 8.0.0-alpha11)

Verified by code analysis and tests in a real migration, 2026-10-21 to 2026-10-29. Read engine
classes with the **tl-mcp** tools (`show_source`, `callers_of`, `references_to`, `query_types`).
**Primary docs:** inside `tl-core.jar` under `META-INF/tl-docs/`: `access-configuration.md`,
`access-discover-spec.md`, `access-privileged-scripts.md`, `upgrade-7.11-to-8.0.md` (§3.5 legacy
layouts). **Version-dependent:** everything here refers to TL 8.0.0-alpha11. On another version,
re-verify the items you rely on (class names, decision order, known issues in §3).

The legacy concept as a whole (profiles, security objects, masters, groups, call chain) is explained
in `legacy-concept.md`. This file adds the details that matter for object security.

## 1. Legacy layer (still the role source)

### Role rules
Configured in the AccessManager config `<role-rules>` (`StorageAccessManager` → `ElementAccessManager`).
All rule kinds are declared on the type that **receives** the role (`meta-element`). Paths use
`<step attribute= inverse=>`, `<script-step><expr>`, or `<singleton>`.
- **`<rule>`** (type reference): the path ends at users or groups; those users get `role` on the object.
- **`<inheritance-rule>`**: the path ends at objects. Whoever holds `source-role` there (empty =
  same as `role`) gets `role` on the target.
- **Role rules accept only stored attributes.** An attribute step over a derived (computed)
  attribute is rejected at startup ("references attribute … that is derived (computed) … cannot be
  tracked for role-rule invalidation"). The same goes for a **script step** that navigates
  through a derived attribute (e.g. `tl.core:Revision#author`, `createRevision()`). Workarounds:
  - for a computed *backward* reference (e.g. `origin` with `inverse-reference`), use the stored
    forward reference with `inverse="true"`;
  - for data that only exists computed (e.g. the creator via the create revision), a **stored
    attribute** is needed (model change, set at creation, migration for existing data). Proven
    shape: an additional hidden reference with
    `<default-value><default-by-expression value="currentUser()"/></default-value>` (the create
    revision does not exist yet at creation, so `creator` itself can't be the default). Document
    the purpose in the attribute's `.tooltip`. Dev mode applies it by incremental model upgrade and
    logs the matching migration processors (`create-reference`, `reorder-part`): copy them into
    the migration, plus a data fill for existing objects.

  Check this before designing rules on attributes with `<storage-algorithm>` or `kind="backwards"`.
  Role parents (`<security-parents>`) are evaluated at check time; a `container()` script step
  works there.
- **Source-role lookup ignores role parents:** for an inheritance rule, "who holds `source-role`
  on the source object" counts only that object's own rules and direct assignments
  (`HasRoleGroupMapper` → `ElementAccessManager#getGroups` / storage). The **object check**
  (`hasRole`) does include role parents. So a role that is polluted through a root role parent
  is still a clean source for inheritance.
- **Giving a composition part its own role rule disables the composition default.** Add an
  explicit role parent (or access parent) back to the container.
- **`<singleton-rule target="SecurityStructure#ROOT" role=…><group-with-name name=…/>`**: a static
  group → role on a singleton.
- `inherit="true"` applies the rule to subtypes of `meta-element` (type inheritance only).
- **Results are materialized** in the security storage, updated incrementally
  (`ElementSecurityUpdateManager#handleSecurityUpdate`).
- The storage is **group-based**: each Person has a representative group
  (`ContactGroupMapper`). A check queries all of the user's groups.

### Role parents
`<security-parents><rule id= meta-element=><step…/></rule></security-parents>` on the
AccessManager. They pass on **roles only**, resolved at check time (object plus all transitive
parents, `StorageAccessManager#getSecurityParentIDs`, `BoundHelper#visitAllSecurityParents`), and
are not materialized.

### Profiles and view checks
- `security.xml` holds `<profile name=role><view name="<CompoundSecurityLayout>"><commandGroup/>`.
- A view's `securityObject` has the **default `securityRoot`**. Other values: `model`, channel
  expressions, `ref:<alias>`, `SecurityObjectByExpression`, and `NullSecurityObjectProvider`.
  **A null security object means no check, so the command is allowed.**
- **`securityObject="model"` is the command target**, not the component model (path element
  `Model`, index 0 = the model passed to the check). Grid edit/delete buttons therefore check the
  selected row; use `model(self())` for the component model. Details: `legacy-concept.md` L4.
- A button can override the security object.
- `isSecurityMaster="true"` makes one component decide whether its layout or tab is shown. A
  single child component is the master implicitly (`BoundLayout`).
- The check chain is `AbstractCommandHandler#checkSecurity` → `BoundChecker#allow`:
  1. a null security object allows;
  2. admin bypass;
  3. profile roles for the command group;
  4. `AccessManager#hasRole` on the object plus its role parents.
- TL Script `filterPermission(objects, roles, account?)` keeps the objects where the user holds a
  role. It returns **everything for admins**: `[$user].filterPermission(role, $user)` is a known
  "is admin" idiom.

## 2. Object layer: `SecurityConfigurationService`

### Schema (`<security-config>`)
- Entry kinds:
  - `<class name= without-security= internal=>`, with an optional `<access-parent>`;
  - `<module name= without-security= internal=>`;
  - `<singleton name=>`;
  - `<part name="mod:Type#attr">`.
- Rules: `<grant operation= roles= inherit=>` and `<revoke …>`, applied in order. `inherit` means
  the rule passes to **subtypes**.
- Operations are command groups: Read, Write, Create, Delete, Export, Discover, Finish,
  restrictedWrite, System, plus registered custom groups. An unknown group or role is a startup
  error.
- **No implication** between operations, except **Discover**, which is the union of all class
  grants, all part grants, and the Create grants of types creatable in the class's compositions.
- Effective class rights = rules passed on by supertypes ∪ module rules ∪ class rules (additive;
  `<revoke>` removes).
  - A part grant overrides the class for that attribute; an empty part grant denies everyone.
  - Singleton grants replace class grants.
  - Marks propagate to subtypes; a module mark covers its classes. A module covers **its own
    classes only**, not submodules.
- `internal`: only code accesses the type; users are denied, and coverage stays quiet. It cannot be
  combined with `without-security`.
- No entry means deny.

### Decision order (`SecurityConfigurationService#isAllowed` → `#compute`)
1. A non-BoundObject, or an uncommitted/transient object: allow.
2. Bypass: system context → allow; no user → deny; system group → allow; `ThreadContext.isAdmin()`
   → allow; restricted user → deny.
3. `without-security` → allow.
4. **Access parent:** delegate to the parent object. Create and Delete become **Write** on the parent.
5. Otherwise `AccessManager#hasRole(user, object, grantedRoles)`, i.e. legacy roles on the object
   plus its role parents. `user.isAdmin()` → true.
- Results are cached per interaction and **knowledge-base revision** (`AccessDecisionCache`, key =
  person, object id, operation). An uncommitted transaction creates no new revision, so a decision
  made before a change in the same interaction is reused after it.
- **Composition parts in transactions:** a part is checked against its **current** container. Once
  detached (removed from the composition reference), it has no container and every check denies:
  `delete()` fails ("may not delete"), `get` silently returns empty values (copied attributes become
  null when moving a part), and because of the cache the outcome depends on whether the part was
  checked earlier in the interaction. Delete parts while attached; when moving, attach to the new
  container first or don't touch the part in between; restructure in a privileged function.

### Create check (`SecurityConfigurationService#isAllowedCreate`, verified)
- `isAllowedCreate(person, type, context)`:
  1. bypass (system context, admin) →
  2. `without-security` type → allow →
  3. context uncommitted (created in the same transaction) → allow →
  4. type has an access parent → **Write on the context** (no context → allow) →
  5. otherwise the Create roles of the type must be held **on the context**. No context: **on
     the security root** (`createContext` → `BoundHelper#getDefaultObject`).
- `isAllowedCreate(person, parent, compositionAttr, type)`: Create on the type in the parent
  context **and** Write on the parent's composition attribute.
- Callers: TL Script `new(type, context: X)` (`CreateObject#eval`, without `context:` → root),
  `CopyOperationImpl#allocate` (`copy()`), React `BufferSave`/`RowSetEditSession`.
  `instanceImport` (`XMLInstanceImporter`) does **not** check Create.

### Transient objects are never checked (and what that implies)
`isAllowed` returns `true` for transient and uncommitted objects (by design). Good: form/dialog
objects and computed helper structures need no grants (e.g. `changeLog()` change sets used to revert
a lock work for every user). Risk: functions that **fill transient objects with data from persistent
objects** bypass object security. Only references to persistent objects inside them are filtered
(Discover), not computed values. Known case: TL Script `changeLog()` (all arguments optional, no admin
check) builds `TransientChangeSet`s with `Modification#oldValue/newValue` for the **whole database
history** when called without an object. **Confirmed leak** (REST probe as a restricted reader:
plain old/new values of objects he may not read, incl. other users' invitations and group
memberships). Not fixable by configuration; report to the engine team; must be closed before an
API/agent goes live. `all(T)` does not find transient objects.

### Structured elements ignore role parents
`StructuredElementWrapper#getSecurityParents()` (tl-element) is hard-coded to return an empty list.
For all types extending `tl.element:StructuredElementContainer`/`StructuredElement` (e.g.
`tl.doc:Page`, the in-app documentation pages), configured `<security-parents>` rules are **silently
ignored**, and the coverage check does not notice. Use an `<access-parent>` instead (evaluated by
the SecurityConfigurationService itself), e.g. `<script expr="p -> \`SecurityStructure#ROOT\`"/>`,
and put the grants on the root type. Verify role-parent rules empirically with
`filterPermission(objects, role, "<account>")`.

### Known engine issue (TL 8.0.0-alpha11): deleted objects
`SecurityConfigurationService#decide` calls `isWithoutSecurity(instance)` → `tType()` **first**,
without checking that the object still exists. When a secured script receives a deleted object as
input (e.g. a transforming channel on a selection that still holds a just-deleted object, as
`FlowChartComponent#receiveModelDeletedEvent` does), `DeletedObjectAccess` is thrown for **every
non-admin user** (root/admins pass `isAllowedBypass` before `decide`). The change event delivery
aborts, leaving the UI stale. This cannot be worked around in configuration (`WithSecurityCheck` is
programmatic only). If UI breakage after deletes appears only for non-admins on TL8, check the
stack for `decide`/`isWithoutSecurity` before suspecting your own rules. Always re-check "also as
root" claims: make sure the comparison really ran as root.

### Script access parent (`com.top_logic.model.search.rules.ScriptAccessParent`, verified)
- `<access-parent><script expr="x -> …"/></access-parent>` is evaluated unsecured; derived
  attributes are allowed.
- The result must be **exactly one** BoundObject (or a one-element collection). `null`/empty →
  **denied**; several results → denied plus an error log; a transient object → denied.
- Evaluated per object per decision; cached only per interaction and revision. Costly for list
  filtering if the script navigates far (recursion, inverse lookups).

### Historic objects (derived from code, not documented; verify by test)
- The role lookup ID is `BoundWrapper#getID` = `KBUtils.getWrappedObjectName`, which is
  revision-independent. So historic versions use the **current** roles of the same identity.
- Access and role parents are resolved on the historic object (historic references → historic
  parents → their current roles).
- An object deleted in the current revision has no stored roles: its historic versions become
  invisible to non-admins unless an access or role parent still leads to a live carrier.
- Unclear: how role-rule materialization treats inverse navigation over historic references.
  Check this whenever snapshots, baselines or releases reference historic objects.

### Access parents
- Inside `<class>`: `<access-parent>` containing `<container [reference=]/>`,
  `<target reference=/>`, `<script expr=/>` or `<self/>`. Inherited by subtypes.
- **Default:** a composition part whose type has no role rule, no role-parent rule and no mark
  delegates to its **container** (`#getAccessParent`, `AccessManager#hasRoleSource`).

### Enforcement
- **Secured TL Script:** `all`, navigation, referers, `container` and object resolution are
  filtered for Discover, silently. `get` without Read returns the empty value. `set`, new, delete
  and add throw. `canAccess`, `canAccessAttribute`, `canCreate` and `canExecute` check rights.
  Copy operations are checked too.
- **React:** `BufferSave`, row-set edits, `ModelAccessPolicy` (hidden vs. disabled), dropdowns mask
  entries as `*****`.
- **Classic UI Java code** (forms, tables, BoundChecker) does **not** check object rights. But its
  TL Script is secured by default (`WithSecurityCheck#usesSecurity` = true): model builders,
  `OptionsByExpression`, `DefaultByExpression`, `ExecutabilityByExpression`, channels, commands.
- **Labels are unsecured**, which is why Discover shows the label.
- **Model elements (`tl.model`):** the Discover filter never drops `TLModelPart`s, but reading their
  attributes (`$attr.get(tl.model:TLStructuredTypePart#type)`, `TLClass#specializations`,
  `TLReference#composite`, …) is a normal Read check on a persistent `BoundObject`. Nobody configures
  `tl.model` (neither framework nor app), so reflective scripts get **empty values for non-admins**:
  dynamic grid columns crash (`columnType` → NPE in `ColumnInfoFactory`), merge dialogs filter
  wrongly. Fix once: access parent on `tl.model:TLModelPart` → security root (Read for the role
  every user holds there). Not `without-security` (would allow writing the model by script/API).
- **Derived attributes vs. computed columns:** a `<storage-algorithm><query>` attribute
  (`AttributeByExpression`) runs **always unsecured** ("definer's rights", deliberately, for
  caching/determinism); only the Read grant on the attribute itself applies. The same expression as
  a layout column (`ComputedColumnProviderByExpression`) runs secured. So a derived attribute that
  collects objects from a secured substructure shows all of them, a computed column only the
  permitted ones. `MacroAttribute` keeps security.
- **Classic UI is no measure:** classic tables/forms render references without object checks,
  while TL Script (charts, computed columns, PDF, API) filters them. A column showing a value that
  the chart footer doesn't show is exactly this difference. Test object access via the API (REST
  probe, §Tooling), not by clicking.

### Unsecured / privileged evaluation
- **Always unsecured:** derived attributes, constraints (incl. uniqueness), delete constraints,
  labels, role-rule and access-parent scripts, `SecurityObjectByExpression`, lock strategies,
  `ScriptTask`, migration scripts.
- **Opt-out:**
  - `ModelAccessRights.uncheckedSecurity(…)` (Java);
  - `QueryExecutor#disableSecurity`;
  - `<privileged-script command-group= security-object= on-denied=>` in ConfiguredTLScriptFunctions:
    checks the group once, runs unsecured, and filters the result for Discover.
- **Not unsecured: object initializers.** `ModelService#applyInitializers` runs them as a commit
  modification (`createModification`), i.e. at commit time, not inside `new()`, in the security
  setting of the committing thread (secured for UI commands). Effects: they see the final attribute
  values set after `new()`, and their reads/creates are checked, outside any privileged function
  that created the object. Remedy: initializer calls a privileged function.
- `new(type, context: X)`: `CreateObject#eval` checks `isAllowedCreate(user, type, X)`; the factory
  uses `context` only for default values (`TLFactory.setupDefaultValues`), so for a non-part type it
  has no side effect unless a default expression reads the context.
- A `privileged-script` may use a standard command group (`Delete`, `Write`, ...) resolved like a
  grant operation; then the existing object grants decide.

### Config snippets

Object layer: `autoconf/com.top_logic.model.security.SecurityConfigurationService.config.xml`,
inside `<security-config>`:
```xml
<module name="app.ui" without-security="true"/>             <!-- transient form types -->
<class name="app.core:Order">                                <!-- role carrier -->
  <grant operation="Read"  roles="Struct.PLANNER, Struct.READER" inherit="true"/>
  <grant operation="Write" roles="Struct.PLANNER" inherit="true"/>
</class>
<class name="app.core:EditLock">                             <!-- explicit delegation -->
  <access-parent><target reference="app.core:EditLock#locked"/></access-parent>
</class>
<part name="app.core:Order#price">                           <!-- attribute override -->
  <grant operation="Read" roles="Struct.PLANNER"/>
</part>
```

Legacy layer (AccessManager config): a role parent, i.e. roles of the project count on its milestones:
```xml
<security-parents>
  <rule id="milestoneProject" meta-element="app.core:Milestone">
    <step attribute="app.core:Milestone#project"/>
    <!-- or: <script-step><expr>m -> $m.get(`app.core:Milestone#project`)</expr></script-step> -->
  </rule>
</security-parents>
```

### Test-user setup (proven pattern)
- **Accounts:** a `*.objects.xml` in the **local deploy aspect** (`deploy/local/webapp/WEB-INF/data/`).
  `InitialDataSetupService` imports each file **once per file name** (DB property
  `initial-data.<name>`), so a new file also reaches existing dev DBs. A changed, already-imported
  file is never re-imported. Cross-file references only work for accounts
  (`<global-ref kind="tl.accounts:Person" id="…"/>`).
- **Assignments:** an idempotent `ScriptTask` with `<on-startup/>`, in
  `deploy/local/webapp/WEB-INF/autoconf/<scheduler autoconf name>.zz-….config.xml`. `conf/`
  (metaConf) loads **before** `autoconf/`, and an autoconf with `config:override="true"` would
  discard it. Autoconf files load alphabetically, so the `zz-` suffix merges after.
- **ScriptTask pitfalls:**
  - Errors go **only to the task log in the DB**, not the log file, and the whole transaction rolls
    back. Bisect with `log("…")` lines.
  - The TL Script list literal is `[a, b]` (there is no `list()`).
  - Attributes with `<storage-algorithm>` are computed and cannot be `set`.
  - ScriptTasks run unsecured, so `all(T)` sees **all mandators/tenants**. Always resolve master
    data by name **within the tenant** (e.g. `$mandator.get(…#organizations)`), because names
    repeat across tenants.
  - An unknown TL Script method in a task script is a config error: it fails the **whole
    scheduler service**, not just the task (e.g. `tValid()` does not exist in TL Script).
  - Model constraints are checked **at commit**: a script can log every step and still fail with
    "Cannot store, constraints violated".
  - Read the real error from the DB task log. With the dev H2 DB: stop the app, then
    `java -cp h2-<ver>.jar org.h2.tools.Shell -url "jdbc:h2:file:<app>/tmp/h2;IFEXISTS=TRUE" -user user -password password`.
    Query `TASK_RESULT` (`TASK_NAME`, `RESULT_TYPE`, `EXCEPTION_DUMP`), and export long dumps with
    `CALL CSVWRITE(...)`.
- Test data that must be imported in the UI with a context (e.g. `instanceImport(context: …)`)
  can't go into the startup data import. Build a synthetic fixture in the ScriptTask instead,
  copying what the app's own "create" dialog does.
- Scripted tests can run each action as another user (`user-id="T_…"`, no password needed).

### Tooling
- **`SecurityCoverageCheck`** runs at startup and logs at INFO (`log-findings`):
  - "No rule can deliver these roles…" = dead grant;
  - "neither a role rule nor a role parent rule, and no composition holds its objects" = no role source;
  - "No role is granted an operation" = no grant;
  - shadowed rules.

  Status per type: covered, delegated, exempt or incomplete.
- Sample lines (log prefix `…SecurityCoverageCheck - `):
  ```
  app.supplier:Supplier: The operation "Read" is granted on the type "app.supplier:Supplier" to the roles "Struct.PLANNER". No rule can deliver these roles on the type or on one of its role parents, therefore the grant never takes effect.
  app.core:ProductVersion: The type "app.core:ProductVersion" has neither a role rule nor a role parent rule, and no composition holds its objects. No user can hold a role on its objects ...
  tl.model:TLClass: No role is granted an operation on the type "tl.model:TLClass" or on one of its attributes. Its objects are inaccessible to every user ...
  ```
- Summary line: `<n> types analysed, <m> with findings.`
- **Which types a role reaches:** combine the role-rule paths (the `meta-element`s, their
  inheritance targets and role parents) with the composition default (parts delegate to their
  container). Dead-grant lines tell you directly where a role does *not* arrive.
- UI: React Administration → Access control → Coverage. The `SecurityDefinitionEditor` writes the
  autoconf files.
- Test: `test-security-coverage="true"` (ElementTestCollector → `TestSecurityCoverage`).
- **REST security probe** (the most effective test tool): the classic UI lets a non-admin run no
  script, admin consoles bypass security. An OpenAPI server endpoint with
  `<basic-authentication in-user-context="true"/>` runs its `<implementation><operation>` script in
  the caller's person context, **secured** (`ServiceMethodByExpression` → `TLContextManager.inPersonContext`,
  compiled without `disableSecurity`), i.e. exactly what an API client or agent would get.
  Template: `assets/security-probe.config.xml` (deploy it in the local deploy aspect only). Call
  `curl -s -u <user>:<password> http://localhost:<port>/<context>/api/securityProbe/<path>`.
  Useful functions in probe scripts: `all(T)`, `canRead/canWrite/canDelete(obj)`,
  `canReadAttribute/canWriteAttribute(obj, attr)`, `currentUser()`. New paths need an app restart.
- **Passwords of test users** are stored by the DB authentication device in its own table, not in
  the model: they can't be set by `*.objects.xml` or TL Script. Set them once in the admin UI.

### Engine class index (for verification after an upgrade)

Find full names with `query_types(pattern=…)`, read with `show_source(fqn, member)`.

| Topic | Classes / members |
|---|---|
| Object security decision | `com.top_logic.model.security.SecurityConfigurationService` (`$Config#getSecurityConfig`, `#isAllowed`, `#decide`, `#compute`, `#computeClassRights`, `#getAccessParent`, `#computeDiscoverRoles`, `#isAllowedCreate`); `ModelAccessRights` (`#isAllowed`, `#isReadAllowed`, `#isDiscoverAllowed`, `#uncheckedSecurity`); `AccessDecisionCache` |
| Script security | `SearchExpression#filterSecurity`, `#discoverable`; `AccessLike#lookupValue`; `QueryExecutor#executeWith` / `#disableSecurity`; `WithSecurityCheck#usesSecurity`; `PrivilegedScript`, `ConfiguredTLScriptFunctions#call`; `ScriptAccessParent`; `AttributeByExpression` (derived attributes, unsecured) |
| Checks in TL Script | `CanAccess` (`canRead/canWrite/canDelete`), `CanAccessAttribute`, `CanCreate`, `CanExecute`, `CanCall`, `FilterPermission` |
| React / UI | `ModelAccessPolicy`, `BufferSave`, `RowSetEditSession` |
| Tooling | `SecurityCoverageCheck`, `com.top_logic.element.boundsec.manager.coverage.SecurityDefinitionEditor`; REST: `ServiceMethodByExpression`, `BasicAuthentication` (`in-user-context`) |
| Legacy roles | `AccessManager` (`#hasRole`, `#getAllowedBusinessObjects`, `#hasRoleSource`); `ElementAccessManager`; `StorageAccessManager` (`#getSecurityParentIDs`, `#getGroups`); `RoleRulesImporter`; `HasRoleGroupMapper`; `ContactGroupMapper`; `ElementSecurityUpdateManager#handleSecurityUpdate`; `RoleComputation` |
| Legacy view checks | `AbstractCommandHandler#checkSecurity`; `BoundChecker` (`#allow`, `#allowCommandOnSecurityObject`); `BoundHelper` (`#getDefaultChecker`, `#visitAllSecurityParents`); `BoundLayout` (security master); `SecurityObjectProviderConfig`, `PathSecurityObjectProvider` (path elements incl. `Model`); `GridComponent#allowEdit`, `DefaultGridRowSecurityObjectProvider` |

## 3. Known engine gaps (TL 8.0.0-alpha11) to report or to document as accepted

| Gap | Effect | Handling |
|---|---|---|
| Roles are revision-independent | releasing one revision (role on a tag with a historic reference) also exposes the current working state and all other revisions via API | document; engine proposal: revision-bound roles |
| Referenced objects outside compositions | need a broad grant or a helper role along the reference; the helper role follows the *current* state, so historic views lose/gain values | document; engine proposal: access parent "any of these referers" |
| Own role rule disables the composition default | adding a role to a part loses the container's roles | re-add the container as role parent and replicate grants |
| Role parent passes on *all* roles | root as role parent opens every role on the root, incl. write roles of all tenants | inherit only the needed role from the root singleton |
| Unsecured computations | derived attributes, labels, transient helper objects (`changeLog()` leak) carry data of unreadable objects | review derived attributes/labels of types readable by externals |
| Deleted objects in security checks | `DeletedObjectAccess` for non-admins after deletes (chart stays broken) | report; no app workaround |
| `tl.model` not readable by default | reflective scripts break for non-admins | access parent to the root (see §Enforcement) |

Write these up per project (template `templates/gaps-and-edge-cases.md`), together with the
project-specific accepted gaps, so the team can discuss them.
