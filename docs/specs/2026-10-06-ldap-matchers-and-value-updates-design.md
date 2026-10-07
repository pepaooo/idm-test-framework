# More ldap.verify matchers, and adding or removing values in ldap.update — design

**Ticket:** ITF-39. **Builds on:** ITF-38 (multi-valued attributes). **Split out:** ITF-41
(`db.verify` matchers); binary attributes moved to ITF-33.

## 1. The problem

ITF-38 gave `ldap.verify` an exact set (a plain list), `contains` and `excludes`, and made
`ldap.create`/`ldap.update` write lists. Three needs remain for group-membership and attribute
tests:
- **How many values:** "the user has exactly 3 groups" cannot be said without listing them.
- **Patterns and case:** a value can only be compared exactly (DNs ignore case, nothing else).
- **One value at a time:** `ldap.update` replaces a whole attribute, so adding one group means
  knowing all the others.

## 2. Decisions (jangulo, 2026-10-06)

1. **Scope:** `count`, regex / case-insensitive matching, and add/remove in `ldap.update`.
   `db.verify` matchers are ITF-41; binary attributes join ITF-33.
2. **Add/remove syntax:** a map per attribute inside `attributes`, `{ add: …, remove: … }`.
3. **Strict:** adding a value the entry already has, or removing one it does not have, fails
   the step with a clear message.
4. **Matching syntax:** a `matches` key (regex, some value matches the whole pattern) plus an
   `ignoreCase` modifier, with an `equals` key so `ignoreCase` can apply to an exact value or set.

## 3. `ldap.verify` matchers

A matcher map may hold any combination of these keys; every key given must hold, checked in this
order, and the first failure is reported:

| Key | Value | Passes when |
|---|---|---|
| `equals` | a value or a list | the attribute has exactly these values, in any order (what a plain value or list means today) |
| `contains` | a value or a list | every listed value is present (ITF-38) |
| `excludes` | a value or a list | no listed value is present; an absent attribute passes (ITF-38) |
| `count` | a non-negative integer | the attribute has this many values; an absent attribute has 0 |
| `matches` | a regex, or a list of them | each pattern matches the whole of at least one value (`Pattern.compile(p).matcher(value).matches()`) |
| `ignoreCase` | `true` or `false` | `equals`, `contains` and `excludes` compare text ignoring case (`equalsIgnoreCase`); DN values already compare ignoring case |

- Plain values and plain lists keep their ITF-38 meaning.
- `matches` is case-sensitive unless the pattern says otherwise (`(?i)`); `ignoreCase` does not
  apply to it.
- Values are compared as text (`toString()`), DN-aware as in ITF-38.

**Failure messages** (ITF-38 style; the first two exist already):
- `equals` uses the exact-set message: `Attribute 'ou': expected exactly [a, b] in any order; missing [b]; unexpected [c]`
- `contains` / `excludes`: unchanged
- `count`: `Attribute 'groupMembership': expected 3 values, found 2: [a, b]` (`found 0` when absent)
- `matches`: `Attribute 'mail': no value matches ^jdoe@.*$; actual=[x@y.com]` (`actual=null` when absent)

**Rejected before any I/O** (`StepOutcome.finalFailure`, naming step, entry and attribute), in
addition to the ITF-38 rules:
- `count` that is not a non-negative integer (YAML integer; `3.0`, `"3"`, `-1` rejected)
- an empty `matches`, a non-text pattern, or an invalid regex (the message names the pattern and
  the regex error)
- `ignoreCase` that is not `true`/`false`, or given without `equals`, `contains` or `excludes`
- `equals` that is empty, or holds a null, map or list item (as a plain list today)
- the same value in `contains` and `excludes` (ITF-38), compared ignoring case when `ignoreCase`
  is true

## 4. `ldap.update` add and remove

**Syntax:** in `attributes`, an attribute's value may be a map `{ add: …, remove: … }`, each a value
or a list, with at least one of the two keys. A plain value or list still replaces the attribute,
and `[]` still removes it.

```yaml
- ldap.update:
    dn: "${createdDn}"
    attributes:
      groupMembership:
        add: ["cn=VPN,ou=groups,o=data"]
        remove: ["cn=Finance,ou=groups,o=data"]
      title: Engineer
```

**Connector:** `EdirectoryConnector.update(dn, attributes)` builds one modify request: a plain
value or list → `REPLACE_ATTRIBUTE` (as today); `add` → `ADD_ATTRIBUTE` with those values;
`remove` → `REMOVE_ATTRIBUTE` with those values. The directory applies the request entirely or
not at all.

**Strict failures:** when the directory refuses because a value to add already exists (LDAP result
20, `attributeOrValueExists`) or a value to remove does not (16, `noSuchAttribute`), the connector
reads the entry once (only on that failure path) and reports which values caused it, comparing
DN-aware:
- `cannot add [cn=VPN,ou=groups,o=data] to groupMembership: the entry already has it`
- `cannot remove [cn=Finance,ou=groups,o=data] from groupMembership: the entry does not have it`

When several attributes are involved, every offending attribute is listed, separated by `; `. If
the read fails, or finds no offending value, the directory's own message is used. The step reports
`ldap.update failed for <dn>: <message>`.

**Rejected before any I/O** (step failure, as other `ldap.update` parameter problems):
- a map with keys other than `add` / `remove`, or with neither
- an empty `add` or `remove`, or one holding a null, map or list item
- the same value in both `add` and `remove` (DN-aware)
- `add` / `remove` in `ldap.create`: `ldap.create for <dn>: attribute 'x' uses add/remove, which only apply to ldap.update`

**Evidence:** `ldap.update` evidence keeps the attributes as written (maps included), masked by
name as in ITF-37.

## 5. Documentation

- `docs/reference/writing-scenarios.md`: the `ldap.verify` "Values" table (the new keys, the
  combination rule, `matches` semantics, `ignoreCase` scope, rejections) and the `ldap.update`
  section (add/remove, strictness, an example).
- README step table and the capability matrix: one line each.

## 6. Testing

**`LdapValueMatcherTest`:** each new key passing and failing with the exact messages; `count` on an
absent attribute; `matches` with several patterns, some value vs none, `(?i)`; `ignoreCase` with
`equals`, `contains`, `excludes`, and with a DN; combinations (`contains` + `count` + `ignoreCase`);
every new shape rejection, including an invalid regex.

**`EdirectoryConnectorTest`** (embedded LDAP): add; remove; add and remove together; mixed with a
replace in one request; adding an existing value and removing a missing one each fail with the
exact messages; after a refused request nothing changed.

**`LdapStepsTest`:** every new `ldap.update` shape rejection before I/O; `ldap.create` refusing
`add`/`remove`; an `ldap.update` with add/remove records evidence with the attributes as written;
the strict failure message reaches the step.

**Corpus:** `scenarios/ldap-multi-valued-attributes.yaml` gains an `ldap.update` with `add` and
`remove`, and checks with `count`, `matches` and `ignoreCase`; it runs embedded and in
`ContainerScenarios`.

**Also:** the full suite, the corpus (0 FAILED), `ContainerScenarios` if Docker is available.

## 7. Out of scope

- `db.verify` / `http.verify` matchers (ITF-41); binary attributes (ITF-33).
- A lenient "add if missing" mode; regex for every value; `count` ranges (`min`/`max`).
