# Model map for object security (<APP>)

Generated/written <DATE>. One row per persistent class of the app modules. Purpose: see at a glance
which types carry roles, which delegate to a container, which are master data and which are technical.

How to collect it: the `*.model.xml` files (compositions: `composite="true"`), the AccessManager
config (`meta-element` of role rules and security parents), the SecurityConfigurationService config
(marks, access parents, grants) and the coverage findings. A small script that reads these files and
prints the table pays off; it is re-run after every phase.

| Type | Contained in (composition) | Role source (rule / parent) | Object security today | Classification | Notes |
|---|---|---|---|---|---|
| `mod:Order` | – | rule `PLANNER` via `Order#planners` | grant Read/Write | role carrier | |
| `mod:OrderItem` | `Order#items` | – | delegates to container (default) | context-bound | |
| `mod:Supplier` | – | – | none | master data, global | decide reader/maintainer |
| `mod.ui:CreateForm` | – | – | – | technical (transient) | `without-security` via module |

Classification values: **role carrier** (grants), **context-bound** (access parent / composition
default), **master data global / per tenant** (concept, see patterns), **technical** (`without-security`
or `internal`), **framework type** (check whether the app needs it at all).

Findings worth a decision (examples): parts that are *not* composed where you expect (need an explicit
access parent), master data composed into a tenant, transient form types in business modules.
