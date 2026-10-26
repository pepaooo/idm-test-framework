# Profile creation and connection management

## Intent

Make profiles and lab connections manageable inside the workbench. A person should be able to create a profile without touching the file system, find a connection when there are many, give it a useful name, and remove an unused connection without leaving scenarios broken.

## Current behavior and constraints

- The profile selector is populated from `config/application-<profile>.yml` files when a workspace opens. It cannot create one. `New scenario` and `Add files` write under `scenarios/`, so neither creates a profile.
- `idmtest init` ships a fully commented `application-lab.yml` template. The existing profile editor can uncomment keys from that template when settings are saved.
- Lab connections come from scenario references and effective settings in the profile file plus shared `config/application.yml`. A connection referenced by a scenario will reappear as missing if its settings are removed. Shared settings continue to apply to every profile.
- Connection identifiers (`directory:ad`, `database:db-postgres`, `rest:idm`, `ldap`, `soap`) determine profile configuration keys and, for named kinds, the values inside scenarios. Their spelling is part of the run contract.
- The current Lab panel wraps all connection tabs into more rows, reducing space for the selected form as the list grows. It has an Add form, but no Delete or display-label editing.

## Goals

1. Add **New profile** beside the top-bar profile selector, including when there are no profiles. Creating one writes a new `config/application-<name>.yml` from the existing commented starter template, with its introductory comment naming the chosen profile. It does not copy values or secrets from the selected profile. After creation, refresh the profile list and workspace tree, select the new profile, and open its Lab panel. Handle unsaved Lab edits before switching profiles.
2. Keep the current shared-settings behavior. A newly created profile may show connections inherited from `config/application.yml`; the UI identifies those as shared rather than implying that its new file contains them.
3. Use the approved **A · Scannable list** layout from the brainstorming mockup: a fixed-height, independently scrolling, searchable connection navigator with status icons, an Add action, and a stable selected-connection form. Preserve the existing connection ordering by kind and identifier. Search matches display label, kind, and identifier. The selected form remains visible when the list grows. On narrow screens, stack a bounded list above the form. Keep keyboard navigation and accessible selection/focus semantics.
4. Add one optional `label` property inside each connection's YAML block, beside its existing fields such as `url` or `username`. A label is part of that connection's profile-specific settings. The same identifier can have different labels in different profiles. A profile without a label falls back to the existing localized kind-and-identifier name; a shared label is inherited until that profile overrides it. Show the stable identifier in the form even when a label is set. Saving a label never changes scenario references or run behavior. No description field.
5. Add **Delete connection** for existing, exclusively profile-owned connections with no scenario references and no effective default-directory dependency. Before removing, show a confirmation naming the connection and the profile file. Delete its configuration block from that profile file, then refresh the Lab view and workspace tree and select a sensible next connection. Preserve unrelated YAML comments, values, and formatting.
6. Block Delete when any scenario in the workspace uses the identifier. Show the referencing scenario filenames beside the disabled control. Block Delete on a connection supplied by shared `config/application.yml` or environment settings, including one with only a profile-specific label override; explain where it is managed. Do not offer a delete action that merely hides it in one profile while effective shared settings remain.

## Non-goals and anti-goals

- No identifier renaming or automatic scenario-file rewriting. The editable name is the display label only.
- No profile deletion, profile rename, profile duplication, or connection description field.
- No changes to the runner's shared-settings precedence or scenario syntax.
- Do not silently remove settings that scenarios still use, remove shared settings from `application.yml`, or automatically delete entries from `secrets.env`. Secret names may be shared by other profiles or settings; deletion of an unused connection removes only its profile YAML block. The UI should say that retained secrets can be reviewed separately.
- Do not leave a successful New profile or Delete operation visible only after reopening the workspace.

## Interaction details

### New profile

- A small dialog asks for a profile name and shows the destination `config/application-<name>.yml`. Validate against the runner's accepted profile names, reject blank/unsafe names and case-insensitive collisions, and never overwrite an existing file. Show write errors in the dialog. Disable creation in a read-only workspace.
- The default choice is an empty, commented template. The dialogue explains that shared settings can still appear in Lab. The new profile is selected on success and its Lab panel opens for configuration. Cancelling creates nothing.

### Connection navigator and form

- The panel can grow wider on desktop to fit the list and form while staying inside the viewport. Its heading, connection status, Save/Test actions, error states, and unsaved-change protections remain. The list owns its scroll area; the form and actions remain usable at desktop and mobile sizes.
- A selected list item shows status and display label. The form shows editable **Label** and a read-only **Identifier** before connection fields. Editing the label follows the current Save/Discard and unsaved-change behavior. Emptying a label restores the fallback name.
- The Add form still collects connection kind and immutable identifier. A label may be entered there; after save the new connection appears in the navigator.
- Delete appears only for a saved connection. Referenced, inherited, environment-managed, read-only, or currently unsaved connections cannot be deleted, with a short reason next to the control. A user can open the listed scenario files to inspect references, but this flow does not edit them.
- If any scenario cannot be read or parsed while checking references, fail closed: do not delete the connection, and show which file needs attention. An uncertain reference scan must never be treated as proof that the connection is unused.

## Approaches considered

| Decision | Chosen approach | Alternative and tradeoff |
| --- | --- | --- |
| Many connections | **A · Scannable list**: bounded navigator and stable form. The user chose this after viewing the mockup. | B · Compact selector retains more form width but hides other connections and their statuses until opened. |
| New profile | Empty commented starter, chosen by the user. | Copying the selected profile saves typing but risks carrying endpoints and secret references into a new environment. |
| Changing a connection name | Editable profile-specific display label, chosen by the user. | Renaming the technical identifier would require coordinated edits to configuration, defaults, and scenario contents. |
| Deleting a referenced connection | Block and show scenario dependencies, chosen by the user. | Allowing removal would produce missing connections and failed runs. |
| Shared settings in a new profile | Preserve inheritance, recommended by the builder and accepted by the user. | A truly isolated profile would require a change to existing configuration precedence. |

## Builder decisions and verification

- Choose the exact endpoint boundaries and persistence helpers so file operations remain confined to the current workspace, use atomic writes/locking, and preserve YAML comments. A failed operation must not partially change a file.
- Ensure adding `label` to YAML is tolerated by the connector binding model and has no effect on connection tests or runs; cover legacy LDAP and SOAP blocks as well as named directory, database, and REST blocks.
- Cover profile creation/selection with and without existing profiles, name collisions, read-only and unsaved states, label fallback/inheritance/override, search and keyboard selection at scale, and all Delete gate conditions. Verify both languages, light/dark themes, desktop and mobile layouts, and scenario use after profile or connection changes.
- Compare the rendered panel to the approved scannable-list mockup and the existing workbench visual system before handoff.
