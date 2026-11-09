# Patterns for the object security configuration

Proven solutions from a real migration. Use them in steps 4 to 7 of the workflow in `SKILL.md`.
Every pattern is a proposal to the developer, not a default: present it as a decision (D#) with
the facts checked in the code.

## Pattern: master data (global and tenant-scoped)

Work out a master data concept early (workflow step 5): which types are global, which belong to a
tenant, who reads and who maintains them. Recommended baseline: whoever is authorized within a
tenant reads all its master data; the tenant admin maintains it; global master data is read by
internal roles and maintained by a system admin; externals get only what their objects reference.

When a type is global (no tenant or mandator reference) and no role is ever restricted to a subset:
- Make the **security root a role parent** of the type (`<security-parents>` rule with a
  `<singleton>` step). Every role held on the root then counts on every instance, without
  materialization.
- Prefer a role parent over an access parent here. An access parent would make the root object's
  grants decide, and turn Create/Delete into Write on the root.
- **First list the roles that are on the root.** Legacy apps often inherit many roles *up* to the
  root for root-checked views, external roles included. Grant only the roles whose root presence
  is meant to apply globally. Any role in any grant also gains Discover.
- A role that must see only *its own* instances (e.g. external supplier users) but also reaches the
  root needs a **separate role**: a plain assignment rule, never inherited upward.
- Ask which roles are **internal or external**, and don't infer it from role names (an "EXT_" role
  may mean "access via another path", not "external person"). Internals can usually read all global master
  data. Externals get a scoped helper role, inherited from the objects they legitimately see
  (e.g. supplier ← release ← their access) via `source-role`. The API makes over-exposure to
  externals real.
- Watch for legacy roles that are inherited separately along the same path. If their profiles
  match, a single onward role with several `source-role`s is leaner (later optimization).
- **Tenant-scoped master data** (types with a tenant/mandator reference or composed into it): a
  role parent to the tenant instead of the root (via the reference, e.g. `WithMandator#mandator`),
  or rely on the composition default. A side effect in secured scripts: `all(T)` then yields only
  the user's tenants, which often fixes cross-tenant bugs for single-tenant users.
- **Non-sensitive catalog data** (colors, markers, presets, documentation pages): root role parent
  plus Read for the role every logged-in user holds on the root (e.g. `USER`); maintenance for
  the admin role. Not `without-security`, which would also open writing via the API.
- **One root role on otherwise scoped types** (e.g. a non-root admin role with an "all
  objects" view on tenant-scoped data): don't make the root a role parent there, that would bring
  in *every* root role (in legacy apps planner/reader roles often reach the root through upward
  chains). Use an `inheritance-rule` restricted to that role (`role="X"`) with a
  `<singleton module=… singleton-name="ROOT"/>` step on each target type, plus Discover for that
  role on the referenced types the view shows as columns. The coverage check confirms that the
  rule delivers the role (no "never takes effect" message for the new grants).
- **Tenant master data for every role authorized within the tenant (recommended default).**
  Rule: whoever holds any role inside a tenant reads all master data of that tenant, and the
  tenant admin also writes it. Implement it with a helper role on the tenant object
  (e.g. `MANDATOR_MEMBER`, in no role profile so legacy views stay unchanged). Inherit it from
  every role that does not land on the tenant yet, reusing the legacy paths to the root
  without the last step. Then grant Read to that role on the tenant (and on parts with their
  own rules). The composition parts follow automatically. This is far simpler than
  per-reference helper paths or per-type "readable for all", it keeps the tenant
  separation, and it fixes `null` references (e.g. `indexBy` crashing on a filtered
  category) for all roles at once. Keep exceptions out of it: product data that externals
  don't need gets its own role parent and grants.
- **System admin inside every tenant:** a non-root system admin who creates tenants and maintains
  global data usually needs the tenant admin's rights in all tenants. Inherit only that role from
  the root singleton onto the tenant type and add it wherever the tenant admin has grants. As a
  side effect, "Create/Delete tenant" grants for that role start to take effect (coverage note
  gone).
- **External roles often bypass the tenant.** Legacy inheritance paths may go from the
  external's object straight to the root, without placing the role on the tenant in between.
  Tenant composition parts are then invisible to externals, and often to internal "reader" roles too.
  Check where each role actually lands before relying on the composition default.
- **Before adding the root as role parent** (e.g. "readable for everyone"), **check which write
  roles are on the root**. Legacy apps often inherit tenant admins up to the root, and every
  tenant admin would then edit everything. If you need it, inherit only `USER` from the root
  singleton instead. Global data such as supplier companies: maintenance by the system admin
  only, no tenant-admin grant.
- **Persons referenced from the business object** (responsible planner, journal author, lock
  holder, sender of an invitation): give external roles a helper role along exactly these
  references, with Read on the contact and Discover on the account. Internal roles can read all
  contacts. Inheritance follows the *current* references, so a historic view (e.g. a release
  showing an old revision) loses persons that were removed later. A blank field in a test can
  also just mean missing data, so log the role check (`filterPermission`) in a test setup task
  before changing rules.
- **Sub-object access in a hierarchy (e.g. a supplier assigned to root steps sees their subtree):**
  add the parent nodes as role parents over a *stored* path (computed parent/child attributes can't
  be used in rules). Role parents are transitive at check time, so a role on the root covers the
  subtree without materializing anything. If the role must also open the container (e.g. a list of
  sequences), don't inherit the same role there when the children delegate to the container,
  because it would reach every child. Use a separate helper role with Read on the container only.
- **The classic UI is no measure.** Classic tables and forms show references without object
  checks, while TL Script (charts, computed columns, PDF, API) filters them. "The column shows
  it, the footer doesn't" is exactly this difference.

## Pattern: permission granularity vs. locking granularity

If editing requires locking a larger unit (e.g. a whole document/sequence), object permissions at
that unit's level are usually good enough, even when the legacy UI shows finer permissions (e.g.
per section). That avoids expensive or ambiguous access-parent scripts:
- A script access parent must resolve to **exactly one** object, and `null`/empty **denies**.
  It is evaluated per object per check, cached only per interaction.
- If the fine-grained path has no direct reference, prefer the coarse container (composition
  default), or a stored shortcut reference, over a navigation script.
- Record the accepted inconsistency (finer in the UI, coarser on object level) as a note for later.

## Pattern: object-layer helper roles

The legacy checks (view security objects, `filterPermission`) use **the same role lookup**,
role parents included. Any new role parent or extended inheritance rule also changes legacy UI
behaviour, e.g. `filterPermission` filters suddenly let more objects through.
- Before adding a role parent or inheritance on a type, grep `filterPermission` and
  `securityObject` usages for that type and those roles.
- If they are affected, introduce a **helper role** that no profile and no `filterPermission`
  knows. Inherit it exactly where object access is needed, and grant only it. Legacy stays
  untouched.
- Never add a role parent from a child to a container whose roles come from *sibling* objects
  (e.g. release → area instance whose roles come from all its releases). That leaks access to
  the siblings.

## Pattern: operations that must see more than the user

Proposed pattern (TopLogic): move the operation into a stored function
`<privileged-script name=… command-group="<OwnGroup>" security-object="…">` in
ConfiguredTLScriptFunctions and call it from the layout. Use `canCall(fn(…))` for executability.
- The check is an **object grant** of `<OwnGroup>` on the security object (SecurityConfigurationService),
  not a security.xml profile. With the security root as security object, grant `<OwnGroup>` on
  `SecurityStructure:Root` to every role that may run the operation; the roles must be on the root.
  Derive that role list (and the Create grant on the created type) from **all** legacy profiles that
  have the command group on the view, not only from the role you are migrating: grep the view in
  `security.xml` and map each `<profile>` (e.g. invitations: INT_PLANNER, INT_READER, PACKAGE_MEMBER
  create, RELEASE_READER only forwards). Otherwise the other roles fail later with "may not create".
- Register the command group (app conf) and add labels `commandGroup.<name>` and `.tooltip`.
- A constant security object allows the operation globally: keep the function narrow and never
  take generic objects or attribute names from its arguments.
- Typical cases: duplicate or e-mail matching across other users' data, creating global master
  data during imports, account creation for invitations, reverting changes (change history).
- **Only** for transactions that must process data the user may not see. Everything else uses
  normal grants. If the secured filtering is actually *desired* (e.g. an import should only map
  the user's own tenant's master data), do **not** make it privileged.
- Prefer the **scope object** as security object (e.g. the target tenant/mandator, or the edited
  document) over the root, so the operation is limited to that scope.
- **Object initializers (`<object-initializers>`) run at commit, secured, outside any privileged
  function of the calling command.** Wrapping the command's operation does not cover them. Move
  the initializer body into a privileged function and let the initializer only call it
  (`obj -> provision($obj)`); the call is the switch to unsecured. The new object can't be the
  security object (uncommitted objects deny), so use the root or a committed scope object, and
  guard the body (act only on not-yet-processed objects) because the function is callable freely.
- **Existing functions that touch internal/unseen objects** (e.g. a delete that also removes
  internal follow-up objects): make them privileged with a **standard** command group on the
  affected object, e.g. `command-group="Delete" security-object="if($x.instanceOf(T), $x, null)"`.
  The check is then exactly the right the user needs anyway; no new group or grant. Otherwise a
  secured run silently skips the unseen part (navigation returns nothing) and leaves orphans.
- Moving a large layout operation: copy the body into the function verbatim, replace the form
  reads by parameters, and validate parameter consistency against the security object (e.g. "the
  type belongs to the checked mandator"), since only the security object was checked.
- Details: `tl-core.jar` `META-INF/tl-docs/access-privileged-scripts.md`.

## Pattern: visible because referenced from a readable object

The engine deliberately does **not** show referenced objects automatically: references are
filtered for Discover. When users (typically externals) must see labels of shared objects
(persons, master data) only where a readable object references them:
- Add a helper role (e.g. `REF_VIEWER`) with **Discover only** on the target types.
- Assign it by inheritance rules along each concrete reference path from the readable objects,
  using `source-role` = the roles that make those objects readable.
- Downward only: the helper role must never be the `source-role` of further inheritance.
- Engine example: "showing the parent path" (`PathViewer`) in `access-discover-spec.md`.
- Prefer this over Discover-for-everyone on shared objects such as persons; that would leak the
  full list via the API.
- Internal users can usually get Discover on all persons via a root role parent, with attribute
  grants (`<part>`) only where scripts really read attributes.
- **Grant on the container, the parts follow:** composition parts without their own role source
  are checked on their container with the same operation (only Create/Delete → Write). Discover on a
  project therefore also covers its milestones. Don't give such parts their own role rule, or the
  delegation stops.

## Questions to ask the developer early

- What does "permitted" mean per type: Discover or Read? Especially persons and master data.
- Should master data be scoped (mandator, tenant)? Which user assignment provides the scope?
- Which existing role carriers are `without-security` on purpose?
- Which project-specific "security object" helpers (TL Script functions used as `securityObject`)
  should become access parents or role parents?
- What must the API or agent be able to do (read only, or write)?
- Which roles are internal and which external?
- What is the locking granularity for editing? (It often justifies coarser object permissions.)
- Who maintains users, groups and role profiles: technical admins or a role like SYSADMIN?
- Do users need to see their own group memberships (profile page)? Then use an assignment rule
  `Group#members` → helper role with Discover only.
