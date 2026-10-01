# Multi-valued LDAP attributes in ldap.verify, ldap.create and ldap.update — design

**Ticket:** ITF-38.

## 1. The problem

A user's groups (`groupMembership` in eDirectory, `memberOf` in AD), and often `ou`, `cn` and
`mail`, hold several values. Today:

- **`ldap.verify` compares them badly** (`EdirectoryConnector.verifyAttributes` / `valuesEqual`):
  - only an exact match: there is no way to say "has at least these groups"
  - order-sensitive (`List.equals`), though LDAP does not guarantee value order
  - a single value is read as a scalar, so a one-item list does not match it
  - DNs compared as raw text, so case and spacing differences fail
  - `cn`, `sn`, `givenName`, `mail`, `uid`, `telephoneNumber`, `title`, `ou` and `description`
    keep only their first value
- **`ldap.create` and `ldap.update` cannot write them:**
  - `ldap.create` turns a list for one of the ten built-in attributes into the text `"[a, b]"`
    and hands any other list to Spring as one value
  - `ldap.update` builds `BasicAttribute(name, list)`, which is also one value

## 2. Decisions (jangulo, 2026-10-01)

1. **A plain list in `expect` is the exact set of values, in any order.** "At least these" is
   written explicitly.
2. **Two matcher keys, `contains` and `excludes`, usable together** in one map.
3. **Values compare DN-aware when both sides are DNs;** everything else keeps its exact text
   comparison.
4. **The ten built-in attributes are included:** `ldap.verify` sees all their values.
5. **`ldap.create` and `ldap.update` write YAML lists as multi-valued attributes** in this
   ticket.

## 3. Components

### `EdirectoryConnector.readAttributes(String dn)` (new)

Returns `OperationResult<Map<String, Object>>` with every attribute the directory returns for the
entry, keyed by the directory's attribute name:

- a single value is a scalar, several values are a `List`
- the ten built-in attributes are handled like any other
- `userPassword` is excluded, as in `read`
- failures are reported as `read` reports them (not found, LDAP error)

`LdapUser` and `read` are unchanged: the typed getters still return the first value, and
`LdapUser`'s custom attributes are what `create` writes, so the full lists are deliberately not
put there.

### `LdapValueMatcher` (new)

In `com.idm.test.connector.ldap.client`, pure, no I/O.
`static String match(String attribute, Object expected, Object actual)` returns `null` on a match,
or the failure message.

**Comparing two values:**
- if both parse as a `javax.naming.ldap.LdapName` with at least one RDN, `LdapName.equals`
  decides (attribute types and values ignore case; spacing around `,` and `=` does not matter)
- otherwise their `toString()` must be equal (so `"200"` matches `200`, as now)

### `EdirectoryConnector.verifyAttributes(dn, expected)`

Same signature. It now reads through `readAttributes` and delegates each expected attribute to
`LdapValueMatcher`; the first mismatch is the failure message. Java tests that call it get the new
behaviour too.

### `LdapVerifyStep`

- **Validates the shape** of each `expect` value before any I/O (section 5).
- **Its `as:` capture** is built from `readAttributes` plus `dn`, so `${entry.ou}` is a list when
  the entry has several values and a string when it has one. `LdapAttributes` is removed if
  nothing else uses it.

### `ldap.create` and `ldap.update`

- **A YAML list becomes one attribute with several values:**
  - `ldap.create`: a list for one of the ten built-in attributes goes to the custom attributes
    instead of its typed field; `EdirectoryConnector.create` writes a custom attribute whose value
    is a `Collection` with `setAttributeValues`
  - `ldap.update`: `EdirectoryConnector.update` builds the `BasicAttribute` with each value added
- **An empty list:**
  - in `ldap.update`, replaces the attribute with no values, which removes it (documented: "remove
    all groups")
  - in `ldap.create`, fails the step, since there is nothing to write
- **A list item that is `null`, a map or a list** fails the step.
- A capture from `ldap.verify … as:` can be written back by an `ldap.update` in `cleanup:`,
  restoring every value.

## 4. Matching rules

The actual value is treated as a set: an absent attribute is the empty set, a scalar is one value,
a list is its values. Duplicates are ignored.

| `expect` value | Passes when | Failure message |
|---|---|---|
| scalar `x` | the actual set is exactly {x} | `Attribute 'title': expected=Manager, actual=[Manager, Lead]` (`actual=null` when absent) |
| list `[a, b]` | the actual set equals {a, b}, in any order | `Attribute 'groupMembership': expected exactly [a, b] in any order; missing [b]; unexpected [c]` |
| `{ contains: [...] }` | every listed value is present; others are ignored | `Attribute 'groupMembership': expected to contain [a, b]; missing [b]; actual=[a, c]` |
| `{ excludes: [...] }` | none of the listed values is present; an absent attribute passes | `Attribute 'groupMembership': expected not to contain [f]; found [f]` |
| `{ contains, excludes }` | both | the first that fails, `contains` first |

- In the exact-set message, `missing` and `unexpected` are each left out when empty.
- A single value is shown as itself (`actual=Manager`), several as a list.
- `contains` and `excludes` accept a list or a single value (`contains: "cn=VPN,…"`).

## 5. Rejected before any I/O

Each ends the wait at once (`StepOutcome.finalFailure`), naming the step, the entry and the
attribute:

- an empty list (`groupMembership: []`), because a misspelled attribute name would pass
  trivially; the message points to `excludes`
- a map with keys other than `contains`/`excludes`, or with neither
- an empty `contains` or `excludes`
- a `null`, a map or a list inside `contains`/`excludes` or inside a plain list
- the same value in both `contains` and `excludes` (compared DN-aware)

The existing checks are unchanged: both or neither of `expect`/`exists`, an empty `expect`, a
`null` expected value.

**Known limitation, documented:** `excludes` on a misspelled attribute name always passes, since a
missing attribute cannot be told from an absent one. The docs recommend pairing it with a
`contains` on the same attribute, or another positive check in the same step.

## 6. Documentation

- **`docs/reference/writing-scenarios.md`:**
  - `ldap.verify`: the value forms, a group-membership example, the `excludes` caveat, the
    capture's lists
  - `ldap.create` and `ldap.update`: lists write several values; `[]` in `ldap.update` removes the
    attribute
- **The capability matrix and README:** one line each.

## 7. Testing

**`LdapValueMatcherTest`** (unit):
- scalar, plain list, `contains`, `excludes` and both together, each passing and failing, with the
  exact messages
- an absent attribute against each form; a scalar against a one-item list
- DN case and spacing; non-DN text keeps the exact match; `"200"` against `200`

**`EdirectoryConnectorTest`** (embedded LDAP):
- `readAttributes` returns a list for a multi-valued `ou` and a multi-valued custom attribute,
  and omits `userPassword`
- `verifyAttributes` passes with values in reverse order and with a differently-cased DN
- `create` and `update` write a list as several values; `update` with an empty list removes the
  attribute

**`LdapStepsTest`:**
- each malformed shape in section 5 is a final failure, before the connector is called
- the `as:` capture holds a list for a multi-valued attribute
- `ldap.create` puts a built-in attribute's list in the custom attributes; an empty list fails it

**Corpus scenario `scenarios/ldap-multi-valued-attributes.yaml`** (embedded, so
`ContainerScenarios` runs it too):
- creates a user with several `ou` values and a multi-valued custom attribute
- verifies with a plain list in another order, then `contains`, then `excludes`
- updates the values and verifies the change with `excludes` and `wait:`

**Done when:** the full suite is green, both corpus result files have 0 FAILED, and
`ContainerScenarios` passes if Docker is available.

## 8. Out of scope

- A value count matcher, regular expressions, or case-insensitive text matching.
- Binary attributes.
- Add/remove of single values in `ldap.update` (it replaces the whole attribute).
- The same matchers for `db.verify` or `http.verify`.
